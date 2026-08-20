package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Always-on entity burn subsystem, independent of the cooking tick (which only runs for stoves holding
// food): it burns any living entity standing on a LIT stove, so freshly-placed empty stoves (placed lit
// by default) burn too. Owns its own per-chunk indexed set of burn-enabled stoves plus the repeat task.
final class StoveBurnLogic {

    // Burn poll: cadence + how far around each player mobs are scanned. The poll is bounded by online
    // player count (not stove count), so getNearbyEntities here is far cheaper than the per-stove scan.
    private static final long BURN_PERIOD_TICKS = 4L;
    static final double DEFAULT_BURN_MOB_RADIUS = 12.0D;
    // Vanilla GRILLING_AREA = Block.box(3,0,3, 13,1,13): only the central 10x10 top surface burns.
    private static final double GRILL_MIN = 3.0D / 16.0D;
    private static final double GRILL_MAX = 13.0D / 16.0D;

    private final FarmersDelightPlugin plugin;
    // Includes empty stoves as well as cooking stoves. It gates the always-on burn poll and lets each
    // player task cheaply reject worlds/areas where no loaded stove could possibly burn an entity.
    private final Map<UUID, Map<Long, Set<Location>>> burnStovesByChunk = new ConcurrentHashMap<>();
    private volatile PluginTask burnTask;
    // Alternates the mob sweep between burn passes; only ever touched on the burn task's thread.
    private boolean burnMobSweep;
    // Resolved lazily once (cleared on /fd reload): the custom farmersdelight:stove_burn damage type from
    // FD's datapack (correct death message + mob panic), or HOT_FLOOR if the datapack isn't loaded so the
    // burn still works either way.
    private volatile DamageType stoveBurnType;
    private volatile double burnMobRadius = DEFAULT_BURN_MOB_RADIUS;

    StoveBurnLogic(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.burnTask = plugin.scheduler().runRepeating(this::burnTick, 1L, BURN_PERIOD_TICKS);
    }

    // Called on /fd reload: re-resolve the damage type next hit and refresh the mob scan radius.
    void reload(double mobRadius) {
        this.stoveBurnType = null;
        this.burnMobRadius = Math.max(0.0D, mobRadius);
    }

    void shutdown() {
        if (burnTask != null) {
            burnTask.cancel();
            burnTask = null;
        }
    }

    void clearWorld(UUID worldId) {
        burnStovesByChunk.remove(worldId);
    }

    void clearAll() {
        burnStovesByChunk.clear();
    }

    void trackBurnStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null || normalized.getWorld() == null) {
            return;
        }
        burnStovesByChunk.computeIfAbsent(normalized.getWorld().getUID(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(normalized), ignored -> ConcurrentHashMap.newKeySet())
                .add(normalized);
    }

    void untrackBurnStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null || normalized.getWorld() == null) {
            return;
        }
        UUID worldId = normalized.getWorld().getUID();
        Map<Long, Set<Location>> chunks = burnStovesByChunk.get(worldId);
        if (chunks == null) return;
        long key = chunkKey(normalized);
        Set<Location> locations = chunks.get(key);
        if (locations != null) {
            locations.remove(normalized);
            if (locations.isEmpty()) {
                chunks.remove(key, locations);
            }
        }
        if (chunks.isEmpty()) {
            burnStovesByChunk.remove(worldId, chunks);
        }
    }

    void untrackBurnStoveChunk(World world, int chunkX, int chunkZ) {
        if (world == null) return;
        UUID worldId = world.getUID();
        Map<Long, Set<Location>> chunks = burnStovesByChunk.get(worldId);
        if (chunks == null) return;
        chunks.remove(chunkKey(chunkX, chunkZ));
        if (chunks.isEmpty()) {
            burnStovesByChunk.remove(worldId, chunks);
        }
    }

    boolean hasBurnStoves() {
        return !burnStovesByChunk.isEmpty();
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private long chunkKey(Location location) {
        return chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void burnTick() {
        if (burnStovesByChunk.isEmpty()) return;
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) return;
        // Mobs are swept every other pass: the per-entity damage-invulnerability window already limits
        // the burn rate, so halving the entity-index scans costs at most one extra poll period of
        // first-contact latency for a mob while players keep the full poll rate.
        boolean sweepMobs = burnMobSweep = !burnMobSweep;
        Set<UUID> sweptMobIds = sweepMobs ? ConcurrentHashMap.newKeySet() : Set.of();
        boolean folia = plugin.scheduler().isFolia();
        for (Player player : players) {
            if (folia) {
                // Schedule on the PLAYER's own region (entity scheduler), not a fixed location. runForEntity
                // follows the player to whatever region currently owns them, so reading the block at their feet
                // stays same-region even if they moved or teleported since this poll was queued. runAt pinned the
                // task to the schedule-time region and threw "Cannot read world asynchronously" once the player
                // had crossed into another region by the time it ran.
                plugin.scheduler().runForEntity(player, () -> burnAroundPlayer(player, sweepMobs, sweptMobIds));
            } else {
                burnAroundPlayer(player, sweepMobs, sweptMobIds);
            }
        }
    }

    private void burnAroundPlayer(Player player, boolean sweepMobs, Set<UUID> sweptMobIds) {
        Location playerLocation = player.getLocation();
        double relevantRadius = sweepMobs ? burnMobRadius + 1.5D : 1.5D;
        if (!hasBurnStoveNear(playerLocation, relevantRadius)) {
            return;
        }
        tryBurnEntityOnStove(player);
        if (!sweepMobs) {
            return;
        }
        // Mobs standing on a stove burn too (vanilla burns any LivingEntity). Bounded to near the player
        // so this stays cheap; sweptMobIds also deduplicates overlap between nearby players.
        boolean folia = plugin.scheduler().isFolia();
        for (LivingEntity living : player.getWorld().getNearbyLivingEntities(playerLocation, burnMobRadius)) {
            if (living instanceof Player) {
                continue;
            }
            if (!sweptMobIds.add(living.getUniqueId())) {
                continue;
            }
            if (folia) {
                // Each mob's block read must run on the region that owns that mob — a mob just across a region
                // boundary from the player would be a cross-region read from the player's region thread.
                plugin.scheduler().runForEntity(living, () -> tryBurnEntityOnStove(living));
            } else {
                tryBurnEntityOnStove(living);
            }
        }
    }

    private boolean hasBurnStoveNear(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) return false;
        Map<Long, Set<Location>> chunks = burnStovesByChunk.get(world.getUID());
        if (chunks == null || chunks.isEmpty()) return false;
        int chunkRadius = Math.max(1, (int) Math.ceil(radius / 16.0D));
        int centerChunkX = center.getBlockX() >> 4;
        int centerChunkZ = center.getBlockZ() >> 4;
        double radiusSq = radius * radius;
        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                Set<Location> locations = chunks.get(chunkKey(chunkX, chunkZ));
                if (locations == null) continue;
                for (Location stove : locations) {
                    double dx = center.getX() - (stove.getBlockX() + 0.5D);
                    double dy = center.getY() - (stove.getBlockY() + 1.0D);
                    double dz = center.getZ() - (stove.getBlockZ() + 0.5D);
                    if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("UnstableApiUsage")
    private void tryBurnEntityOnStove(LivingEntity entity) {
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if (world == null) return;
        // The stove is the block directly beneath the entity's feet (feet rest on the stove's top face).
        Block stoveBlock = world.getBlockAt(loc.getBlockX(), (int) Math.floor(loc.getY() - 0.05D), loc.getBlockZ());
        // No Material fast-filter here: a CraftEngine custom block's Bukkit getType() is the configurable
        // deceive-bukkit-material (often bricks), NOT the note_block auto-state, so getType() can neither
        // identify a stove nor rule one out. The cheap entity gates below (valid / sneaking / gamemode) run
        // first, then the CE custom-state + StoveCookingBlockBehavior lookup is the authoritative reject.
        if (!entity.isValid() || entity.isDead()) return;
        if (entity instanceof Player player) {
            if (player.isSneaking()) return;
            GameMode gm = player.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(stoveBlock);
        if (state == null || state.isEmpty()) return;
        StoveCookingBlockBehavior behavior = CustomBlockUtils.getBehavior(state, StoveCookingBlockBehavior.class);
        if (behavior == null || !behavior.isBurnEnabled()) return;
        trackBurnStove(stoveBlock.getLocation());
        if (!behavior.isLit(state)) return;
        double amount = behavior.getBurnDamage();
        if (amount <= 0D) return;
        // Only the central grilling surface burns (vanilla GRILLING_AREA = 3..13px), so standing on the
        // block's rim is safe. Overlap the entity's horizontal bounding box against that inset square.
        org.bukkit.util.BoundingBox bb = entity.getBoundingBox();
        double gx1 = stoveBlock.getX() + GRILL_MIN, gx2 = stoveBlock.getX() + GRILL_MAX;
        double gz1 = stoveBlock.getZ() + GRILL_MIN, gz2 = stoveBlock.getZ() + GRILL_MAX;
        if (bb.getMaxX() <= gx1 || bb.getMinX() >= gx2 || bb.getMaxZ() <= gz1 || bb.getMinZ() >= gz2) return;
        entity.damage(amount, DamageSource.builder(stoveBurnDamageType()).build());
    }

    // RegistryKey.DAMAGE_TYPE is the modern stable lookup; the old Registry.DAMAGE_TYPE accessor was
    // deprecated in 1.20.6. Still wrapped in try/catch with a HOT_FLOOR fallback for server flavours
    // without the registry accessor.
    @SuppressWarnings("UnstableApiUsage")
    private DamageType stoveBurnDamageType() {
        DamageType type = this.stoveBurnType;
        if (type == null) {
            DamageType custom = null;
            NamespacedKey key = NamespacedKey.fromString("farmersdelight:stove_burn");
            if (key != null) {
                try {
                    custom = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).get(key);
                } catch (Throwable ignored) {
                    // Registry unavailable on this server flavour → fall back below.
                }
            }
            type = custom != null ? custom : DamageType.HOT_FLOOR;
            this.stoveBurnType = type;
        }
        return type;
    }
}