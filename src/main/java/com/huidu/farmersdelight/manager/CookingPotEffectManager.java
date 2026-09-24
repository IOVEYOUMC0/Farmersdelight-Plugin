package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ManagerSupport;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

// Renders the bubble/steam/sound feedback for cooking pots. Owns the per-chunk packet budget and the
// shared tracked-player lookup so a dense pocket of pots pays one getPlayersSeeingChunk per chunk per tick.
class CookingPotEffectManager {

    private final FarmersDelightPlugin plugin;
    // Reassigned on reload, read on Folia region tick threads — volatile for a happens-before edge.
    private volatile EffectSpec bubbleEffect = new EffectSpec(true, Particle.BUBBLE_POP, 0.20f, 1,
            0.02D, 0.0D, 0.0D, 0.0D, 0.01D);
    private volatile EffectSpec steamEffect = new EffectSpec(true, Particle.CLOUD, 0.05f, 1,
            0.08D, 0.0D, 0.03D, 0.0D, 0.02D);
    private volatile EffectSpec secondarySteamEffect = new EffectSpec(false, Particle.SMOKE, 1.0f, 1,
            0.05D, 0.0D, 0.025D, 0.0D, 0.02D);

    // Squared player-proximity radius for gating cooking-pot particle/sound broadcasts. Default 32 blocks =
    // vanilla particle/sound range upper bound; read from cooking-pot.effects.viewer-distance on reload.
    private static final double DEFAULT_EFFECT_VIEWER_DISTANCE = 32.0D;
    // Written in reloadConfig, read on Folia region tick threads — volatile for a happens-before edge.
    private volatile double effectViewerDistanceSquared = DEFAULT_EFFECT_VIEWER_DISTANCE * DEFAULT_EFFECT_VIEWER_DISTANCE;
    // Cooking-pot particle/sound emission is throttled to 1-in-N TickManager passes (each pass =
    // TICK_INTERVAL ticks). Raise to 2-3 to cut particle + getPlayersSeeingChunk cost on dense farms;
    // cooking PROGRESS is unaffected (it runs earlier in tickCookingPot).
    private volatile int cookingPotEffectInterval = 1;
    // Per-chunk hard cap on cooking-pot particle+sound packets emitted per dispatch, so a packed pocket
    // must not steamroll the packet queue in one Bukkit tick. Reset once per bukkit tick across the pass.
    private volatile int cookingPotChunkEffectBudgetLimit = 50;
    // Per-chunk tracked-player list + budget, so pots sharing a chunk pay one getPlayersSeeingChunk lookup
    // that tick. World-keyed so identical chunk coordinates in different worlds never collide.
    private final Map<UUID, Map<Long, CookingPotFxContext>> chunkFx = new ConcurrentHashMap<>();
    private static final ThreadLocal<List<Player>> NEARBY_VIEWER_SCRATCH = ThreadLocal.withInitial(ArrayList::new);

    private static final class CookingPotFxContext {
        // Bukkit tick this chunk's budget and viewer snapshot belong to. Stamped per chunk instead of
        // clearing the whole world table on every tick change: the global clear also discarded the budget a
        // concurrently running region had already spent in the same tick, and it rebuilt an inner map per
        // world per tick. A context is dropped by the chunk/world unload cleanup like the other managers.
        volatile long budgetTick = Long.MIN_VALUE;
        final AtomicInteger budget = new AtomicInteger();
        volatile List<Player> seeing;
    }

    void cleanupChunk(UUID worldId, long chunkKey) {
        Map<Long, CookingPotFxContext> chunks = chunkFx.get(worldId);
        // Other Folia regions can add entries to the same world concurrently.
        if (chunks != null) chunks.remove(chunkKey);
    }

    void cleanupWorld(UUID worldId) {
        chunkFx.remove(worldId);
    }

    void cleanup() {
        chunkFx.clear();
    }

    // Memo of sound resolution keyed on (configured, defaultSound). The vanilla sound registry is frozen
    // at bootstrap, so a given key always resolves the same way; replaces a per-cooking-pot-per-tick
    // NamespacedKey.fromString + Registry.SOUNDS.get with one map lookup. Cap guards unbounded config.
    private static final Map<String, ResolvedSound> SOUND_RESOLUTION_CACHE = new ConcurrentHashMap<>();
    private static final int SOUND_RESOLUTION_CACHE_MAX = 512;

    CookingPotEffectManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    void reloadConfig() {
        SOUND_RESOLUTION_CACHE.clear();
        ConfigurationSection effectSection = plugin.getFirstConfigSection("cooking-pot.effects", "cooking-pot-effects");
        ConfigurationSection bubbleSection = effectSection != null ? effectSection.getConfigurationSection("bubble") : null;
        ConfigurationSection steamSection = effectSection != null ? effectSection.getConfigurationSection("steam") : null;
        ConfigurationSection secondarySection = steamSection != null ? steamSection.getConfigurationSection("secondary") : null;

        bubbleEffect = loadEffectSpec(bubbleSection, true, Particle.BUBBLE_POP, 0.20f,
                0.02D, 0.0D, 0.01D);
        steamEffect = loadEffectSpec(steamSection, true, Particle.CLOUD, 0.05f,
                0.08D, 0.03D, 0.02D);
        secondarySteamEffect = loadEffectSpec(secondarySection, false, Particle.SMOKE, 1.0f,
                0.05D, 0.025D, 0.02D);
        double viewerDistance = Math.max(0.0D, plugin.getConfigDouble(DEFAULT_EFFECT_VIEWER_DISTANCE,
                "cooking-pot.effects.viewer-distance"));
        effectViewerDistanceSquared = viewerDistance * viewerDistance;
        cookingPotEffectInterval = Math.max(1, plugin.getConfigInt(1, "cooking-pot.effects.interval"));
        cookingPotChunkEffectBudgetLimit = Math.max(1, plugin.getConfigInt(50,
                "performance.chunk-effect-packet-budget"));
    }

    void emit(World world, BlockPosKey posKey, CookingPotBlockEntity entity, boolean hasHeat,
              CookingPotBlockBehavior behavior, long currentBukkitTick) {
        if (!hasHeat) {
            return;
        }

        // Skip all state, location and viewer work on throttled passes.
        if (cookingPotEffectInterval > 1 && (currentBukkitTick / 4) % cookingPotEffectInterval != 0) {
            return;
        }

        boolean hasActivity = entity.hasInput()
                || entity.hasPendingOutput()
                || entity.hasMealDisplayItem()
                || entity.getCookingProgress() > 0
                || entity.getCurrentRecipe() != null;
        if (!hasActivity) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location center = ManagerSupport.toLocation(world, posKey);
        if (center == null) {
            return;
        }
        center.add(0.5, 0.9, 0.5);

        int chunkX = posKey.x() >> 4;
        int chunkZ = posKey.z() >> 4;
        long effectChunkKey = ManagerSupport.chunkKey(chunkX, chunkZ);
        CookingPotFxContext fx = chunkFx.computeIfAbsent(world.getUID(), w -> new ConcurrentHashMap<>())
                .computeIfAbsent(effectChunkKey, k -> new CookingPotFxContext());
        if (fx.budgetTick != currentBukkitTick) {
            fx.budgetTick = currentBukkitTick;
            fx.budget.set(0);
            fx.seeing = null;
        }
        List<Player> seeing = fx.seeing;
        if (seeing == null) {
            seeing = world.isChunkLoaded(chunkX, chunkZ)
                    ? List.copyOf(world.getChunkAt(chunkX, chunkZ).getPlayersSeeingChunk())
                    : List.of();
            fx.seeing = seeing;
        }
        List<Player> nearbyViewers = NEARBY_VIEWER_SCRATCH.get();
        nearbyViewers.clear();
        double cx = center.getX();
        double cy = center.getY();
        double cz = center.getZ();
        for (Player p : seeing) {
            if (p.getWorld() != world) {
                continue;
            }
            double dx = p.getX() - cx;
            double dy = p.getY() - cy;
            double dz = p.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= effectViewerDistanceSquared) {
                nearbyViewers.add(p);
            }
        }
        if (nearbyViewers.isEmpty()) {
            nearbyViewers.clear();
            return;
        }
        AtomicInteger chunkBudget = fx.budget;
        if (chunkBudget.get() >= cookingPotChunkEffectBudgetLimit) {
            nearbyViewers.clear();
            return;
        }

        EffectSpec bubble = bubbleEffect;
        if (bubble.enabled() && random.nextFloat() < bubble.chance() && chunkBudget.get() < cookingPotChunkEffectBudgetLimit) {
            chunkBudget.incrementAndGet();
            double x = center.getX() + (random.nextDouble() * 0.6D - 0.3D);
            double y = center.getY() + bubble.yOffset();
            double z = center.getZ() + (random.nextDouble() * 0.6D - 0.3D);
            ManagerSupport.spawnParticleFor(
                    nearbyViewers, bubble.particle(),
                    x, y, z,
                    bubble.count(),
                    bubble.offsetX(),
                    bubble.offsetY(),
                    bubble.offsetZ(),
                    bubble.speed()
            );
        }

        EffectSpec steam = steamEffect;
        if (steam.enabled() && random.nextFloat() < steam.chance() && chunkBudget.get() < cookingPotChunkEffectBudgetLimit) {
            chunkBudget.incrementAndGet();
            double x = center.getX() + (random.nextDouble() * 0.4D - 0.2D);
            double y = center.getY() + steam.yOffset();
            double z = center.getZ() + (random.nextDouble() * 0.4D - 0.2D);
            ManagerSupport.spawnParticleFor(
                    nearbyViewers, steam.particle(),
                    x, y, z,
                    steam.count(),
                    steam.offsetX(),
                    steam.offsetY(),
                    steam.offsetZ(),
                    steam.speed()
            );

            EffectSpec secondary = secondarySteamEffect;
            if (secondary.enabled() && chunkBudget.get() < cookingPotChunkEffectBudgetLimit) {
                chunkBudget.incrementAndGet();
                ManagerSupport.spawnParticleFor(
                        nearbyViewers, secondary.particle(),
                        x,
                        y + secondary.yOffset(),
                        z,
                        secondary.count(),
                        secondary.offsetX(),
                        secondary.offsetY(),
                        secondary.offsetZ(),
                        secondary.speed()
                );
            }
        }

        float soundChance = soundFloat(behavior, CookingPotBlockBehavior::getSoundChance, 0.10f);
        if (random.nextFloat() < soundChance && chunkBudget.get() < cookingPotChunkEffectBudgetLimit) {
            chunkBudget.incrementAndGet();
            boolean soupReady = entity.hasPendingOutput() || entity.hasMealDisplayItem();
            String configuredSound;
            if (soupReady) {
                String soupBoilSound = behavior != null ? behavior.getSoupBoilSound() : null;
                configuredSound = firstNonBlank(soupBoilSound, Constants.SOUND_COOKING_POT_BOIL_SOUP);
            } else {
                String boilSound = behavior != null ? behavior.getBoilSound() : null;
                configuredSound = firstNonBlank(boilSound, Constants.SOUND_COOKING_POT_BOIL);
            }
            ResolvedSound boilSound = resolveSound(
                    configuredSound,
                    soupReady ? Sound.BLOCK_BREWING_STAND_BREW : Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP
            );
            float volume = soundFloat(behavior, CookingPotBlockBehavior::getSoundVolume, 0.5f);
            float pitchMin = soundFloat(behavior, CookingPotBlockBehavior::getSoundPitchMin, 0.9f);
            float pitchMax = soundFloat(behavior, CookingPotBlockBehavior::getSoundPitchMax, 1.1f);
            float pitch = pitchMin;
            if (pitchMin < pitchMax) {
                pitch = pitchMin + random.nextFloat() * (pitchMax - pitchMin);
            }
            playConfiguredSound(nearbyViewers, center, boilSound, volume, pitch);
        }
        // Drop the player references as soon as the emission ends: this scratch list is cached on the region
        // thread, which outlives the players it last saw.
        nearbyViewers.clear();
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private static float soundFloat(CookingPotBlockBehavior behavior,
                                    Function<CookingPotBlockBehavior, Double> value, float fallback) {
        Double configured = behavior == null ? null : value.apply(behavior);
        return configured == null ? fallback : configured.floatValue();
    }

    private EffectSpec loadEffectSpec(
            ConfigurationSection section,
            boolean defaultEnabled,
            Particle defaultParticle,
            float defaultChance,
            double defaultYOffset,
            double defaultOffsetY,
            double defaultSpeed
    ) {
        return new EffectSpec(
                section == null ? defaultEnabled : ConfigSectionReader.optionalBoolean(section, "enabled", defaultEnabled),
                ManagerSupport.resolveParticle(section == null ? null : ConfigSectionReader.optionalString(section, "type"), defaultParticle),
                section == null ? defaultChance : (float) ConfigSectionReader.optionalDouble(section, "chance", defaultChance),
                Math.max(1, section == null ? 1 : ConfigSectionReader.optionalInt(section, "count", 1)),
                section == null ? defaultYOffset : ConfigSectionReader.optionalDouble(section, "y-offset", defaultYOffset),
                section == null ? 0.0 : ConfigSectionReader.optionalDouble(section, "offset-x", 0.0),
                section == null ? defaultOffsetY : ConfigSectionReader.optionalDouble(section, "offset-y", defaultOffsetY),
                section == null ? 0.0 : ConfigSectionReader.optionalDouble(section, "offset-z", 0.0),
                Math.max(0.001D, section == null ? defaultSpeed : ConfigSectionReader.optionalDouble(section, "speed", defaultSpeed))
        );
    }

    private ResolvedSound resolveSound(String configured, Sound defaultSound) {
        String cacheKey = (configured == null ? "" : configured) + ' ' + defaultSound;
        ResolvedSound cached = SOUND_RESOLUTION_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        ResolvedSound resolved = resolveSoundUncached(configured, defaultSound);
        if (SOUND_RESOLUTION_CACHE.size() < SOUND_RESOLUTION_CACHE_MAX) {
            SOUND_RESOLUTION_CACHE.put(cacheKey, resolved);
        }
        return resolved;
    }

    private ResolvedSound resolveSoundUncached(String configured, Sound defaultSound) {
        if (configured == null || configured.isBlank()) {
            return ResolvedSound.fromBukkit(defaultSound);
        }

        String trimmed = configured.trim();
        String registryKey;
        if (trimmed.contains(":")) {
            registryKey = trimmed.toLowerCase(Locale.ROOT);
        } else {
            registryKey = "minecraft:" + trimmed.toLowerCase(Locale.ROOT).replace('_', '.');
        }
        NamespacedKey parsedRegistryKey = NamespacedKey.fromString(registryKey);
        Sound registrySound = parsedRegistryKey == null ? null : Registry.SOUNDS.get(parsedRegistryKey);
        if (registrySound != null) {
            return ResolvedSound.fromBukkit(registrySound);
        }

        NamespacedKey customKey;
        if (trimmed.contains(":")) {
            customKey = NamespacedKey.fromString(trimmed);
        } else {
            customKey = NamespacedKey.fromString(registryKey);
        }
        if (customKey != null) {
            return ResolvedSound.fromKey(customKey.toString());
        }

        return ResolvedSound.fromBukkit(defaultSound);
    }

    private void playConfiguredSound(List<Player> viewers, Location location, ResolvedSound sound, float volume, float pitch) {
        if (sound.bukkitSound() != null) {
            for (Player viewer : viewers) {
                viewer.playSound(location, sound.bukkitSound(), volume, pitch);
            }
            return;
        }
        if (sound.soundKey() != null && !sound.soundKey().isBlank()) {
            for (Player viewer : viewers) {
                viewer.playSound(location, sound.soundKey(), SoundCategory.BLOCKS, volume, pitch);
            }
        }
    }

    private record ResolvedSound(Sound bukkitSound, String soundKey) {
        private static ResolvedSound fromBukkit(Sound sound) {
            return new ResolvedSound(sound, null);
        }

        private static ResolvedSound fromKey(String key) {
            return new ResolvedSound(null, key);
        }
    }

    private record EffectSpec(
            boolean enabled,
            Particle particle,
            float chance,
            int count,
            double yOffset,
            double offsetX,
            double offsetY,
            double offsetZ,
            double speed
    ) {
    }
}
