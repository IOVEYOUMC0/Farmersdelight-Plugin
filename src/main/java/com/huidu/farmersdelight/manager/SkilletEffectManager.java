package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.SoundUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class SkilletEffectManager {

    // Squared player-proximity radius for gating per-tick smoke/sizzle broadcasts. Default 32 blocks =
    // vanilla particle/sound range; read from skillet.effects.viewer-distance on reload.
    private static final double DEFAULT_EFFECT_VIEWER_DISTANCE = 32.0D;
    private static final double DEFAULT_SMOKE_CHANCE = Constants.SKILLET_PARTICLE_CHANCE;
    private static final double DEFAULT_SIZZLE_CHANCE = Constants.SKILLET_SIZZLE_CHANCE;

    private final FarmersDelightPlugin plugin;

    // Written in reloadConfig (reload thread), read on Folia region tick threads — volatile for a
    // happens-before edge, matching the other reload-mutated tick-read fields.
    private volatile double effectViewerDistanceSquared = DEFAULT_EFFECT_VIEWER_DISTANCE * DEFAULT_EFFECT_VIEWER_DISTANCE;
    private volatile boolean smokeEnabled = true;
    private volatile Particle smokeParticle = Particle.SMOKE;
    private volatile double smokeChance = DEFAULT_SMOKE_CHANCE;
    private volatile int smokeCount = 2;
    private volatile double smokeYOffset = 0.2D;
    private volatile double smokeOffsetX = 0.1D;
    private volatile double smokeOffsetY = 0.1D;
    private volatile double smokeOffsetZ = 0.1D;
    private volatile double smokeSpeed = 0.02D;
    private volatile boolean sizzleEnabled = true;
    private volatile double sizzleChance = DEFAULT_SIZZLE_CHANCE;
    private volatile float sizzleVolume = 0.5F;
    private volatile float sizzlePitch = 1.0F;

    // Per-chunk hard cap on particle+sound packets emitted per dispatch from this manager, mirroring
    // StoveManager's budget — stops a dense pocket of cooking skillets from steamrolling the packet
    // queue when many cook rolls land in one Bukkit tick. Reset once per bukkit tick, shared across the
    // whole tick pass.
    private volatile int chunkEffectBudgetLimit = 50;
    // Keyed by world UID (like StoveManager) so two worlds' chunks sharing a chunkKey don't collide on
    // one budget entry. The outer map is cleared wholesale once per Bukkit tick across the whole tick pass.
    private final Map<UUID, Map<Long, AtomicInteger>> chunkEffectBudget = new ConcurrentHashMap<>();
    // volatile: Folia ticks skillets in different regions concurrently, so this per-tick budget-reset guard is
    // read/written across region threads (matches StoveManager and TickManager).
    private volatile long effectBudgetResetTick = -1L;
    // Reusable per-thread recipient list for targeted particle/sound sends (per-thread for Folia's
    // concurrent per-region skillet ticks; refilled per skillet and consumed synchronously).
    private static final ThreadLocal<List<Player>> NEARBY_VIEWER_SCRATCH = ThreadLocal.withInitial(ArrayList::new);

    public SkilletEffectManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void reloadConfig() {
        double viewerDistance = Math.max(0.0D, plugin.getConfigDouble(DEFAULT_EFFECT_VIEWER_DISTANCE,
                "skillet.effects.viewer-distance"));
        this.effectViewerDistanceSquared = viewerDistance * viewerDistance;
        this.chunkEffectBudgetLimit = Math.max(1, plugin.getConfigInt(50,
                "performance.chunk-effect-packet-budget"));
        loadEffectsConfig();
    }

    private void loadEffectsConfig() {
        ConfigurationSection effectsSection = plugin.getFirstConfigSection("skillet.effects");
        ConfigurationSection smokeSection = effectsSection != null ? effectsSection.getConfigurationSection("smoke") : null;
        smokeEnabled = smokeSection == null || ConfigSectionReader.optionalBoolean(smokeSection, "enabled", true);
        smokeParticle = ManagerSupport.resolveParticle(smokeSection == null ? null : ConfigSectionReader.optionalString(smokeSection, "type"), Particle.SMOKE);
        smokeChance = ManagerSupport.clampChance(smokeSection == null
                ? DEFAULT_SMOKE_CHANCE
                : ConfigSectionReader.optionalDouble(smokeSection, "chance", DEFAULT_SMOKE_CHANCE));
        smokeCount = Math.max(1, smokeSection == null ? 2 : ConfigSectionReader.optionalInt(smokeSection, "count", 2));
        smokeYOffset = smokeSection == null ? 0.2D : ConfigSectionReader.optionalDouble(smokeSection, "y-offset", 0.2D);
        smokeOffsetX = Math.max(0.0D, smokeSection == null ? 0.1D : ConfigSectionReader.optionalDouble(smokeSection, "offset-x", 0.1D));
        smokeOffsetY = Math.max(0.0D, smokeSection == null ? 0.1D : ConfigSectionReader.optionalDouble(smokeSection, "offset-y", 0.1D));
        smokeOffsetZ = Math.max(0.0D, smokeSection == null ? 0.1D : ConfigSectionReader.optionalDouble(smokeSection, "offset-z", 0.1D));
        smokeSpeed = Math.max(0.0D, smokeSection == null ? 0.02D : ConfigSectionReader.optionalDouble(smokeSection, "speed", 0.02D));

        ConfigurationSection sizzleSection = effectsSection != null ? effectsSection.getConfigurationSection("sizzle") : null;
        sizzleEnabled = sizzleSection == null || ConfigSectionReader.optionalBoolean(sizzleSection, "enabled", true);
        sizzleChance = ManagerSupport.clampChance(sizzleSection == null
                ? DEFAULT_SIZZLE_CHANCE
                : ConfigSectionReader.optionalDouble(sizzleSection, "chance", DEFAULT_SIZZLE_CHANCE));
        sizzleVolume = (float) Math.max(0.0D, sizzleSection == null ? 0.5D : ConfigSectionReader.optionalDouble(sizzleSection, "volume", 0.5D));
        sizzlePitch = (float) Math.max(0.0D, sizzleSection == null ? 1.0D : ConfigSectionReader.optionalDouble(sizzleSection, "pitch", 1.0D));
    }

    // Dispatches one batch of smoke particles/sizzle sounds for a cooking skillet. Returns the collected
    // nearby-viewer list so callers can avoid a second proximity scan.
    public void dispatchTickEffects(World world, Location location, ImmutableBlockState carrierState) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Player> nearbyViewers = ManagerSupport.collectNearbyPlayers(
                world, location, effectViewerDistanceSquared, NEARBY_VIEWER_SCRATCH.get());
        long chunkKey = ManagerSupport.chunkKey(location);
        long currentBukkitTick = Bukkit.getCurrentTick();
        if (currentBukkitTick != effectBudgetResetTick) {
            chunkEffectBudget.clear();
            effectBudgetResetTick = currentBukkitTick;
        }
        AtomicInteger chunkBudget = nearbyViewers.isEmpty()
                ? null
                : chunkEffectBudget.computeIfAbsent(world.getUID(), w -> new ConcurrentHashMap<>())
                        .computeIfAbsent(chunkKey, k -> new AtomicInteger());
        boolean canSpawnEffects = chunkBudget != null && chunkBudget.get() < chunkEffectBudgetLimit;
        if (canSpawnEffects && smokeEnabled && random.nextDouble() < smokeChance) {
            spawnCookingParticles(nearbyViewers, location);
            chunkBudget.incrementAndGet();
        }
        if (canSpawnEffects && chunkBudget.get() < chunkEffectBudgetLimit
                && sizzleEnabled && random.nextDouble() < sizzleChance) {
            SoundUtils.play(nearbyViewers, location, getSizzleSound(carrierState),
                    Sound.BLOCK_CAMPFIRE_CRACKLE, sizzleVolume, sizzlePitch);
            chunkBudget.incrementAndGet();
        }
    }

    private void spawnCookingParticles(List<Player> viewers, Location location) {
        double px = location.getX() + 0.5;
        double py = location.getY() + smokeYOffset;
        double pz = location.getZ() + 0.5;
        ManagerSupport.spawnParticleFor(viewers, smokeParticle, px, py, pz,
                smokeCount, smokeOffsetX, smokeOffsetY, smokeOffsetZ, smokeSpeed);
    }

    // Resolves the sizzle sound from the already-fetched carrier state, avoiding a second CE custom-state
    // fetch during the sizzle branch.
    private String getSizzleSound(ImmutableBlockState state) {
        SkilletBlockBehavior behavior = CustomBlockUtils.getBehavior(state, SkilletBlockBehavior.class);
        if (behavior != null) {
            return behavior.getSizzleSound();
        }
        return Constants.SOUND_SKILLET_SIZZLE;
    }
}