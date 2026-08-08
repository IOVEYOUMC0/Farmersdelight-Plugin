package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CampfireRecipeCache;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.DisplayTransformUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class StoveManager {

    private static final int SLOT_COUNT = 6;
    private static final int DEFAULT_COOK_TIME = 600;
    private static final int HEARTBEAT_LOG_INTERVAL = 20;
    private static final int DEFAULT_TICK_BUDGET = 512;
    private static final int DEFAULT_COOLING_DECREMENT = 2;
    // Burn poll: cadence + how far around each player mobs are scanned. The poll is bounded by online
    // player count (not stove count), so getNearbyEntities here is far cheaper than the per-stove scan.
    private static final long BURN_PERIOD_TICKS = 4L;
    private static final double DEFAULT_BURN_MOB_RADIUS = 12.0D;
    // Vanilla GRILLING_AREA = Block.box(3,0,3, 13,1,13): only the central 10x10 top surface burns.
    private static final double GRILL_MIN = 3.0D / 16.0D;
    private static final double GRILL_MAX = 13.0D / 16.0D;
    private static final double DEFAULT_SMOKE_CHANCE = Constants.STOVE_PARTICLE_CHANCE;
    private static final double DEFAULT_CRACKLE_CHANCE = Constants.STOVE_CRACKLE_CHANCE;

    private final FarmersDelightPlugin plugin;
    private final Map<Location, StoveData> stoves = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Location>> stovesByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Set<Location>>> stovesByChunk = new ConcurrentHashMap<>();
    // Includes empty stoves as well as cooking stoves. It gates the always-on burn poll and lets each
    // player task cheaply reject worlds/areas where no loaded stove could possibly burn an entity.
    private final Map<UUID, Map<Long, Set<Location>>> burnStovesByChunk = new ConcurrentHashMap<>();
    // Blocked-above freshness lives on StoveData (tick-stamp TTL + event invalidation); this constant
    // is the recheck period for non-event shape changes (pistons, falling blocks), ~30s at 20 TPS.
    private static final long BLOCKED_RECHECK_TICKS = 600L;
    private final Set<Location> scheduledStoveTicks = ConcurrentHashMap.newKeySet();
    private final AtomicLong tickLocationsVersion = new AtomicLong();
    private volatile List<Location> tickLocationsSnapshot = List.of();
    private volatile long tickLocationsSnapshotVersion = -1L;
    private final CampfireRecipeCache campfireRecipes = new CampfireRecipeCache("stove", this::debug);
    // R-CONC-002 (#010 precedent): tickTask is written by region threads (ensureTaskRunning, reached from
    // block events) and read+nulled by the global tick thread (stopTaskIfIdle) — volatile for visibility
    // + a dedicated lock so the check-then-schedule / check-then-cancel are atomic (no double-schedule).
    private volatile PluginTask tickTask;
    private final Object tickTaskLock = new Object();
    // Always-on (independent of the cooking tick, which only runs for stoves holding food): burns any
    // living entity standing on a LIT stove, so freshly-placed empty stoves (placed lit by default) burn too.
    private volatile PluginTask burnTask;
    // Alternates the mob sweep between burn passes; only ever touched on the burn task's thread.
    private boolean burnMobSweep;
    // Resolved lazily once (cleared on /fd reload): the custom farmersdelight:stove_burn damage type from
    // FD's datapack (correct death message + mob panic), or HOT_FLOOR if the datapack isn't loaded so the
    // burn still works either way.
    private volatile DamageType stoveBurnType;
    private int heartbeatTicks;
    private int tickCursor;
    private int tickBudget;
    private int defaultCookTime = DEFAULT_COOK_TIME;
    private int coolingDecrement = DEFAULT_COOLING_DECREMENT;
    private boolean smokeEnabled = true;
    private Particle smokeParticle = Particle.SMOKE;
    private double smokeChance = DEFAULT_SMOKE_CHANCE;
    private int smokeCount = 1;
    private double smokeYOffset = 0.0D;
    private double smokeOffsetX = 0.0D;
    private double smokeOffsetY = 0.0D;
    private double smokeOffsetZ = 0.0D;
    private double smokeSpeed = 0.02D;
    private boolean crackleEnabled = true;
    private double crackleChance = DEFAULT_CRACKLE_CHANCE;
    private float crackleVolume = 1.0F;
    private float cracklePitch = 1.0F;
    private boolean fireParticlesEnabled = true;
    private double fireParticleChance = 0.15D;
    // Written in reloadConfig (reload thread), read on Folia region tick threads (effect/burn) — volatile
    // for a happens-before edge, matching the other reload-mutated tick-read fields.
    private volatile double effectViewerDistance = 32.0D;
    private volatile double burnMobRadius = DEFAULT_BURN_MOB_RADIUS;
    // Per-chunk per-tick effect context: the packet budget (hard cap so a dense pocket of stoves —
    // 60/chunk × 4 slot rolls — can't steamroll the packet queue in one Bukkit tick) plus the tick's
    // chunk-tracked player list, fetched once and shared by every stove in the chunk. World-keyed so
    // identical chunk coordinates in different worlds never collide. Cleared on tick rollover.
    private volatile int chunkEffectBudgetLimit = 50;
    private final Map<UUID, Map<Long, ChunkFxContext>> chunkFx = new ConcurrentHashMap<>();
    private volatile long effectBudgetResetTick = -1L;

    private static final class ChunkFxContext {
        final AtomicInteger budget = new AtomicInteger();
        volatile List<Player> seeing;
    }

    // Reusable per-thread recipient list for targeted particle/sound sends. Per-thread so it stays safe
    // under Folia's concurrent per-region stove ticks; refilled (cleared) at the start of each stove's
    // effect emission and consumed synchronously within the same tick, so it never escapes.
    private static final ThreadLocal<List<Player>> NEARBY_VIEWER_SCRATCH = ThreadLocal.withInitial(ArrayList::new);

    private volatile double[][] slotOffsets = StoveDisplayOffsets.defaults();

    public static class StoveData {
        final Location location;
        final ItemStack[] items = new ItemStack[SLOT_COUNT];
        final int[] cookingTime = new int[SLOT_COUNT];
        final int[] maxTime = new int[SLOT_COUNT];
        final int[] displayEntities = new int[SLOT_COUNT];
        final UUID[] ownerIds = new UUID[SLOT_COUNT];
        final String[] ownerNames = new String[SLOT_COUNT];
        // Blocked-above flag with a tick-stamp TTL, replacing the old Location-keyed cache map: the
        // steady-state per-tick cost is two volatile reads instead of a CHM lookup + lambda. MIN_VALUE
        // marks "never checked / event-invalidated" and must be compared explicitly — a plain
        // subtraction against it overflows.
        volatile long blockedAboveCheckedTick = Long.MIN_VALUE;
        volatile boolean blockedAbove;

        StoveData(Location location, int defaultCookTime) {
            this.location = location;
            Arrays.fill(maxTime, defaultCookTime);
            Arrays.fill(displayEntities, -1);
        }
    }

    public StoveManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
        campfireRecipes.rebuild();
        burnTask = plugin.scheduler().runRepeating(this::burnTick, 1L, BURN_PERIOD_TICKS);
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    public void trackBurnStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null || normalized.getWorld() == null) {
            return;
        }
        burnStovesByChunk.computeIfAbsent(normalized.getWorld().getUID(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(normalized), ignored -> ConcurrentHashMap.newKeySet())
                .add(normalized);
    }

    public void untrackBurnStove(Location location) {
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

    private void untrackBurnStoveChunk(World world, int chunkX, int chunkZ) {
        if (world == null) return;
        UUID worldId = world.getUID();
        Map<Long, Set<Location>> chunks = burnStovesByChunk.get(worldId);
        if (chunks == null) return;
        chunks.remove(chunkKey(chunkX, chunkZ));
        if (chunks.isEmpty()) {
            burnStovesByChunk.remove(worldId, chunks);
        }
    }

    private long chunkKey(Location location) {
        return chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public void reloadConfig() {
        // Re-resolve the stove_burn damage type next hit (the datapack may have just been installed + reloaded).
        this.stoveBurnType = null;
        this.tickBudget = Math.max(1, plugin.getConfigInt(DEFAULT_TICK_BUDGET,
                "stove.tick-budget",
                "performance.stove-tick-budget"));
        this.defaultCookTime = Math.max(1, plugin.getConfigInt(DEFAULT_COOK_TIME,
                "stove.cooking.default-cook-time",
                "stove.default-cook-time"));
        this.coolingDecrement = Math.max(0, plugin.getConfigInt(DEFAULT_COOLING_DECREMENT,
                "stove.cooking.cooling-decrement",
                "stove.cooling-decrement"));
        this.burnMobRadius = Math.max(0.0D, plugin.getConfigDouble(DEFAULT_BURN_MOB_RADIUS, "stove.burn.mob-scan-radius"));
        this.chunkEffectBudgetLimit = Math.max(1, plugin.getConfigInt(50, "performance.chunk-effect-packet-budget"));
        loadEffectsConfig();
        this.slotOffsets = StoveDisplayOffsets.load(plugin, SLOT_COUNT);
        refreshVisualsAfterConfigReload();
    }

    private void loadEffectsConfig() {
        ConfigurationSection effectsSection = plugin.getFirstConfigSection("stove.effects");
        ConfigurationSection smokeSection = effectsSection != null ? effectsSection.getConfigurationSection("smoke") : null;
        smokeEnabled = smokeSection == null || smokeSection.getBoolean("enabled", true);
        smokeParticle = ManagerSupport.resolveParticle(smokeSection == null ? null : smokeSection.getString("type"), Particle.SMOKE);
        smokeChance = ManagerSupport.clampChance(smokeSection == null
                ? DEFAULT_SMOKE_CHANCE
                : smokeSection.getDouble("chance", DEFAULT_SMOKE_CHANCE));
        smokeCount = Math.max(1, smokeSection == null ? 1 : smokeSection.getInt("count", 1));
        smokeYOffset = smokeSection == null ? 0.0D : smokeSection.getDouble("y-offset", 0.0D);
        smokeOffsetX = Math.max(0.0D, smokeSection == null ? 0.0D : smokeSection.getDouble("offset-x", 0.0D));
        smokeOffsetY = Math.max(0.0D, smokeSection == null ? 0.0D : smokeSection.getDouble("offset-y", 0.0D));
        smokeOffsetZ = Math.max(0.0D, smokeSection == null ? 0.0D : smokeSection.getDouble("offset-z", 0.0D));
        smokeSpeed = Math.max(0.0D, smokeSection == null ? 0.02D : smokeSection.getDouble("speed", 0.02D));

        ConfigurationSection crackleSection = effectsSection != null ? effectsSection.getConfigurationSection("crackle") : null;
        crackleEnabled = crackleSection == null || crackleSection.getBoolean("enabled", true);
        crackleChance = ManagerSupport.clampChance(crackleSection == null
                ? DEFAULT_CRACKLE_CHANCE
                : crackleSection.getDouble("chance", DEFAULT_CRACKLE_CHANCE));
        crackleVolume = (float) Math.max(0.0D, crackleSection == null ? 1.0D : crackleSection.getDouble("volume", 1.0D));
        cracklePitch = (float) Math.max(0.0D, crackleSection == null ? 1.0D : crackleSection.getDouble("pitch", 1.0D));

        ConfigurationSection fireSection = effectsSection != null ? effectsSection.getConfigurationSection("fire") : null;
        fireParticlesEnabled = fireSection == null || fireSection.getBoolean("enabled", true);
        fireParticleChance = ManagerSupport.clampChance(fireSection == null
                ? 0.15D
                : fireSection.getDouble("chance", 0.15D));
        effectViewerDistance = Math.max(0.0D, effectsSection == null
                ? 32.0D
                : effectsSection.getDouble("viewer-distance", 32.0D));
    }

    private void ensureTaskRunning() {
        if (tickTask != null) {
            return;
        }
        synchronized (tickTaskLock) {
            if (tickTask != null) {
                return;
            }
            debug("tick task: starting stove tick task");
            tickTask = plugin.scheduler().runRepeating(this::tick, 1L, 4L);
        }
    }

    private void stopTaskIfIdle() {
        if (tickTask == null || !stoves.isEmpty()) {
            return;
        }
        synchronized (tickTaskLock) {
            if (tickTask != null && stoves.isEmpty()) {
                debug("tick task: stopping stove tick task because no stoves remain");
                tickTask.cancel();
                tickTask = null;
                heartbeatTicks = 0;
            }
        }
    }

    public StoveData getOrCreateStove(Location location) {
        ensureTaskRunning();
        Location normalized = ManagerSupport.normalize(location);
        trackBurnStove(normalized);
        StoveData existing = stoves.get(normalized);
        if (existing != null) {
            return existing;
        }

        // Saved data may still be parked on the controller (deferred startup load, or a chunk served from
        // CraftEngine's chunk cache where loadCustomData never re-ran); apply it before creating a blank
        // entry that would shadow the stored contents and let the late apply overwrite this interaction.
        if (normalized.getWorld() != null) {
            CustomBlockUtils.notifyControllerChanged(normalized.getWorld(), new BlockPosKey(normalized),
                    com.huidu.farmersdelight.block.behavior.StoveBlockEntityController.class, null,
                    com.huidu.farmersdelight.block.behavior.StoveBlockEntityController::loadPendingDataIfReady);
            StoveData loaded = stoves.get(normalized);
            if (loaded != null) {
                return loaded;
            }
        }

        StoveData created = new StoveData(normalized, defaultCookTime);
        StoveData previous = stoves.putIfAbsent(normalized, created);
        if (previous != null) {
            return previous;
        }
        indexStove(normalized);
        markTickLocationsDirty();
        return created;
    }

    public void collectLiveDisplayIds(java.util.Set<Integer> out) {
        for (StoveData stove : stoves.values()) {
            for (int id : stove.displayEntities) {
                if (id >= 0) {
                    out.add(id);
                }
            }
        }
    }

    public boolean handleInteract(Player player, Block block, ItemStack itemInHand) {
        if (itemInHand == null || itemInHand.getType().isAir()) {
            return false;
        }
        // Inner defense: never consume equippable items as cooking ingredients, even if the
        // CraftEngine useOnBlock PASSTHROUGH path didn't catch them (armor-swap timing race).
        if (StoveCookingBlockBehavior.isEquippable(itemInHand)) {
            return false;
        }
        Location location = ManagerSupport.normalize(block.getLocation());
        // One-shot user-click path, often before any StoveData exists — an uncached check is exact
        // semantics and computes a single collision shape.
        if (isStoveBlockedAbove(location)) {
            debug("Stove interact blocked above for " + formatItem(itemInHand) + " at " + formatLocation(location));
            return false;
        }

        StoveData stove = getOrLoadStove(location);

        CookingRecipe<?> recipe = findCampfireRecipe(itemInHand);
        if (recipe == null) {
            debug("Stove interact no campfire recipe for " + formatItem(itemInHand) + " at " + formatLocation(location));
            return false;
        }

        // Atomic findEmpty + claim: without the lock, two concurrent right-clicks from different Folia
        // regions can each receive the same slot, both write, last write wins — first player's food is
        // consumed (heldItem.setAmount-1) but the slot now holds B's food, so A loses the item silently.
        int emptySlot;
        synchronized (stove) {
            emptySlot = findEmptySlot(stove);
            if (emptySlot < 0) {
                debug("Stove interact no empty slot for " + formatItem(itemInHand) + " at " + formatLocation(location));
                return false;
            }
            ItemStack toPlace = itemInHand.clone();
            toPlace.setAmount(1);
            stove.items[emptySlot] = toPlace;
            stove.cookingTime[emptySlot] = 0;
            stove.maxTime[emptySlot] = recipe.getCookingTime() > 0 ? recipe.getCookingTime() : defaultCookTime;
            stove.ownerIds[emptySlot] = player.getUniqueId();
            stove.ownerNames[emptySlot] = player.getName();
            debug("create state: slot=" + emptySlot + ", stored=" + formatItem(toPlace)
                    + ", duration=" + stove.maxTime[emptySlot] + ", location=" + formatLocation(location));
        }

        createVisual(location, stove, emptySlot, CustomBlockUtils.getFacing(block).getOppositeFace());
        saveStove(location, stove);
        if (player.getGameMode() != GameMode.CREATIVE) {
            debug("consume: slot=" + emptySlot + ", before=" + itemInHand.getAmount() + ", after=" + (itemInHand.getAmount() - 1)
                    + ", item=" + formatItem(itemInHand) + ", location=" + formatLocation(location));
            itemInHand.setAmount(itemInHand.getAmount() - 1);
        } else {
            debug("consume: skipped for creative mode, slot=" + emptySlot + ", item=" + formatItem(itemInHand)
                    + ", location=" + formatLocation(location));
        }

        location.getWorld().playSound(location, Sound.BLOCK_LANTERN_PLACE, 0.5f, 1.0f);
        return true;
    }

    public boolean handleRetrieve(Player player, Block block) {
        if (player == null || block == null) {
            return false;
        }

        Location location = ManagerSupport.normalize(block.getLocation());
        StoveData stove = getOrLoadStove(location);
        return retrieveItem(player, location, stove);
    }

    public boolean canCook(ItemStack item) {
        return findCampfireRecipe(item) != null;
    }

    public String findRecipeId(ItemStack item) {
        CookingRecipe<?> recipe = findCampfireRecipe(item);
        if (recipe != null) {
            return recipe.getKey().toString();
        }
        return "null";
    }

    public void breakStove(Location blockLocation, Location dropLocation) {
        breakStove(blockLocation, dropLocation, true);
    }

    private void flushControllerPendingData(Location location) {
        if (location == null || location.getWorld() == null) return;
        CustomBlockUtils.notifyControllerChanged(location.getWorld(), new BlockPosKey(location),
                com.huidu.farmersdelight.block.behavior.StoveBlockEntityController.class, null,
                com.huidu.farmersdelight.block.behavior.StoveBlockEntityController::loadPendingDataIfReady);
    }

    public void breakStove(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        Location normalized = ManagerSupport.normalize(blockLocation);
        untrackBurnStove(normalized);
        flushControllerPendingData(normalized);
        StoveData stove = removeTrackedStove(normalized);
        if (stove != null) {
            cleanupAllVisuals(stove);
            if (shouldDropItems) {
                for (ItemStack item : stove.items) {
                    if (item != null && !item.getType().isAir()) {
                        normalized.getWorld().dropItemNaturally(dropLocation, item.clone());
                    }
                }
            }
        }
        // Only clean up when a stove actually exists (in memory), to avoid a wasted CEWorld dirty mark on every normal block break.
        if (stove != null) {
            removeStoredData(normalized);
        }
    }

    public boolean isStoveStateBlock(Location location) {
        return CustomBlockUtils.hasBehavior(location, StoveCookingBlockBehavior.class)
                || CustomBlockUtils.hasId(location, Constants.BLOCK_STOVE);
    }

    public void saveAllData() {
        ManagerSupport.saveAllData(stoves, this::saveStove);
    }

    public void saveWorldData(World world) {
        if (world == null) {
            return;
        }
        Set<Location> locations = stovesByWorld.get(world.getUID());
        if (locations == null || locations.isEmpty()) {
            return;
        }
        for (Location location : List.copyOf(locations)) {
            StoveData stove = stoves.get(location);
            if (stove == null) {
                continue;
            }
            // Same contract as saveAndUnloadChunk: snapshot into the controller, then drop the live entry.
            // CE serializes the world's chunks at WorldUnloadEvent HIGHEST (after this NORMAL handler and
            // its cleanup), so a removed entry without a snapshot would export nothing and wipe the data.
            // If the unload gets cancelled by another plugin, the first interaction re-hydrates from the
            // snapshot via the entry-creation flush.
            if (passivateToController(world, location)) {
                removeStove(location);
            } else {
                saveStove(location, stove);
            }
        }
    }

    public Collection<Location> getTrackedLocations(World world) {
        if (world == null) {
            return List.of();
        }

        Set<Location> indexed = stovesByWorld.get(world.getUID());
        if (indexed == null || indexed.isEmpty()) {
            return List.of();
        }

        List<Location> result = new ArrayList<>(indexed.size());
        for (Location loc : indexed) {
            result.add(loc.clone());
        }
        return result;
    }

    public void cleanupWorld(UUID worldId) {
        burnStovesByChunk.remove(worldId);
        Set<Location> locations = stovesByWorld.remove(worldId);
        stovesByChunk.remove(worldId);
        if (locations == null || locations.isEmpty()) {
            return;
        }
        boolean removedAny = false;
        for (Location location : List.copyOf(locations)) {
            StoveData stove = stoves.remove(location);
            if (stove != null) {
                removedAny = true;
                scheduledStoveTicks.remove(location);
                cleanupAllVisuals(stove);
            }
        }
        if (removedAny) {
            markTickLocationsDirty();
        }
        stopTaskIfIdle();
    }

    public void saveAndUnloadChunk(World world, int minX, int maxX, int minZ, int maxZ) {
        if (world == null) {
            return;
        }
        untrackBurnStoveChunk(world, minX >> 4, minZ >> 4);
        Map<Long, Set<Location>> worldChunks = stovesByChunk.get(world.getUID());
        Set<Location> locations = worldChunks == null ? null : worldChunks.get(chunkKey(minX >> 4, minZ >> 4));
        if (locations == null || locations.isEmpty()) {
            return;
        }
        for (Location location : List.copyOf(locations)) {
            if (location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ) {
                StoveData stove = stoves.get(location);
                if (stove == null) {
                    removeStove(location);
                    continue;
                }
                // Snapshot into the controller BEFORE removing the entry: CE serializes this chunk at
                // ChunkUnloadEvent HIGHEST by pulling from this manager, which runs after this HIGH
                // handler — removing first would make it export nothing and wipe the persisted data.
                if (passivateToController(world, location)) {
                    removeStove(location);
                } else {
                    // Controller unreachable: keep the entry so the pull-serialization can still export
                    // it; the entry is reconciled on the next chunk load.
                    saveStove(location, stove);
                }
            }
        }
    }

    private boolean passivateToController(World world, Location location) {
        boolean[] stashed = {false};
        CustomBlockUtils.notifyControllerChanged(world, new BlockPosKey(location),
                com.huidu.farmersdelight.block.behavior.StoveBlockEntityController.class, null,
                controller -> stashed[0] = controller.passivate());
        return stashed[0];
    }

    public boolean loadStove(World world, net.momirealms.craftengine.core.world.BlockPos pos, Map<String, Object> data) {
        return loadStove(world, new BlockPosKey(pos), data);
    }

    public boolean loadStove(World world, BlockPosKey posKey, Map<String, Object> data) {
        if (world == null || posKey == null || data == null) return true;

        Location location = ManagerSupport.toLocation(world, posKey);
        if (location == null) return false;
        if (!isStoveStateBlock(location)) {
            // The CE state can be transiently unresolvable (a /ce reload unbinds states for the parse
            // window); keep the data parked instead of discarding it, so a live stove's contents are
            // not destroyed. A genuinely replaced block just carries inert leftover NBT.
            return false;
        }
        trackBurnStove(location);
        if (stoves.containsKey(ManagerSupport.normalize(location))) {
            // A live entry exists (created by an interaction before this deferred load applied); the live
            // state is newer than the saved snapshot, so consume the snapshot without overwriting it.
            return true;
        }

        StoveData stove = new StoveData(location, defaultCookTime);
        boolean hasAnyItem = false;
        BlockFace facing = CustomBlockUtils.getFacing(location.getBlock()).getOppositeFace();

        for (int i = 0; i < SLOT_COUNT; i++) {
            Object itemObject = data.get("slot_" + i + "_item");
            if (itemObject instanceof ItemStack item && !item.getType().isAir()) {
                stove.items[i] = item.clone();
                stove.cookingTime[i] = data.get("slot_" + i + "_progress") instanceof Number progress ? progress.intValue() : 0;
                stove.maxTime[i] = data.get("slot_" + i + "_duration") instanceof Number duration ? duration.intValue() : defaultCookTime;
                if (data.get("slot_" + i + "_owner_id") instanceof String ownerId) {
                    try {
                        stove.ownerIds[i] = UUID.fromString(ownerId);
                    } catch (IllegalArgumentException ignored) {
                        stove.ownerIds[i] = null;
                    }
                }
                if (data.get("slot_" + i + "_owner_name") instanceof String ownerName) {
                    stove.ownerNames[i] = ownerName;
                }
                createVisual(location, stove, i, facing);
                hasAnyItem = true;
            }
        }

        if (hasAnyItem) {
            putStove(location, stove);
            markStoveDirty(location);
            ensureTaskRunning();
        } else {
            removeStoredData(location);
        }
        return true;
    }

    public com.huidu.farmersdelight.api.block.StoveSnapshot snapshot(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null) {
            return null;
        }
        StoveData stove = stoves.get(normalized);
        if (stove == null) {
            return null;
        }
        boolean lit = isStoveLit(CustomBlockUtils.getState(normalized));
        List<ItemStack> items = new ArrayList<>(SLOT_COUNT);
        List<Integer> progress = new ArrayList<>(SLOT_COUNT);
        List<Integer> durations = new ArrayList<>(SLOT_COUNT);
        synchronized (stove) {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                items.add(stove.items[slot]);
                progress.add(stove.cookingTime[slot]);
                durations.add(stove.maxTime[slot]);
            }
            return new com.huidu.farmersdelight.api.block.StoveSnapshot(
                    normalized, items, progress, durations, lit, stove.blockedAbove);
        }
    }

    private StoveData getOrLoadStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData stove = stoves.get(normalized);
        if (stove != null) {
            ensureTaskRunning();
            return stove;
        }

        return getOrCreateStove(normalized);
    }

    public void cleanup() {
        synchronized (tickTaskLock) {
            if (tickTask != null) {
                tickTask.cancel();
                tickTask = null;
            }
        }
        if (burnTask != null) {
            burnTask.cancel();
            burnTask = null;
        }
        for (StoveData stove : stoves.values()) {
            cleanupAllVisuals(stove);
        }
        stoves.clear();
        stovesByWorld.clear();
        stovesByChunk.clear();
        burnStovesByChunk.clear();
        scheduledStoveTicks.clear();
        chunkFx.clear();
        tickLocationsSnapshot = List.of();
        markTickLocationsDirty();
    }

    public void reloadRecipeCache() {
        campfireRecipes.rebuild();
    }

    private void removeStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData stove = removeTrackedStove(normalized);
        if (stove != null) {
            cleanupAllVisuals(stove);
        }
        stopTaskIfIdle();
        if (false) {
            removeStoredData(normalized);
        }
    }

    private void putStove(Location location, StoveData stove) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData previous = stoves.put(normalized, stove);
        if (previous != null && previous != stove) {
            // Overwriting a still-tracked stove (double chunk-load / reload re-scan): destroy the old stove's
            // item displays so they don't orphan (the incoming stove already created its own visuals).
            cleanupAllVisuals(previous);
        }
        indexStove(normalized);
        markTickLocationsDirty();
    }

    private StoveData removeTrackedStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData removed = stoves.remove(normalized);
        if (removed != null) {
            scheduledStoveTicks.remove(normalized);
            deindexStove(normalized);
            markTickLocationsDirty();
        }
        return removed;
    }

    private void markTickLocationsDirty() {
        tickLocationsVersion.incrementAndGet();
    }

    private List<Location> getTickLocationsSnapshot() {
        long version = tickLocationsVersion.get();
        List<Location> snapshot = tickLocationsSnapshot;
        if (tickLocationsSnapshotVersion == version) {
            return snapshot;
        }

        List<Location> refreshed = new ArrayList<>(stoves.size());
        for (Location location : stoves.keySet()) {
            if (location != null && location.getWorld() != null) {
                refreshed.add(location);
            }
        }
        List<Location> updated = refreshed.isEmpty() ? List.of() : Collections.unmodifiableList(refreshed);
        tickLocationsSnapshot = updated;
        tickLocationsSnapshotVersion = version;
        return updated;
    }

    private void indexStove(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        stovesByWorld
                .computeIfAbsent(location.getWorld().getUID(), ignored -> ConcurrentHashMap.newKeySet())
                .add(location);
        stovesByChunk
                .computeIfAbsent(location.getWorld().getUID(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(location), ignored -> ConcurrentHashMap.newKeySet())
                .add(location);
    }

    private void deindexStove(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        UUID worldId = location.getWorld().getUID();
        Set<Location> locations = stovesByWorld.get(worldId);
        if (locations == null) {
            return;
        }
        locations.remove(location);
        if (locations.isEmpty()) {
            stovesByWorld.remove(worldId);
        }

        Map<Long, Set<Location>> worldChunks = stovesByChunk.get(worldId);
        if (worldChunks == null) {
            return;
        }
        long chunkKey = chunkKey(location);
        Set<Location> chunkLocations = worldChunks.get(chunkKey);
        if (chunkLocations == null) {
            return;
        }
        chunkLocations.remove(location);
        if (chunkLocations.isEmpty()) {
            worldChunks.remove(chunkKey);
        }
        if (worldChunks.isEmpty()) {
            stovesByChunk.remove(worldId);
        }
    }

    private int findEmptySlot(StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] == null || stove.items[i].getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasAnyItem(StoveData stove) {
        for (ItemStack item : stove.items) {
            if (item != null && !item.getType().isAir()) {
                return true;
            }
        }
        return false;
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

    // Registry.DAMAGE_TYPE is deprecated (since 1.20.6) but not for removal, so it stays stable. The suggested
    // replacement goes through the ApiStatus.Experimental RegistryKey API; using the deprecated-but-stable
    // accessor (already wrapped in try/catch with a HOT_FLOOR fallback) is the more version-robust choice.
    @SuppressWarnings({"deprecation", "UnstableApiUsage"})
    private DamageType stoveBurnDamageType() {
        DamageType type = this.stoveBurnType;
        if (type == null) {
            DamageType custom = null;
            NamespacedKey key = NamespacedKey.fromString("farmersdelight:stove_burn");
            if (key != null) {
                try {
                    custom = Registry.DAMAGE_TYPE.get(key);
                } catch (Throwable ignored) {
                    // Registry unavailable on this server flavour → fall back below.
                }
            }
            type = custom != null ? custom : DamageType.HOT_FLOOR;
            this.stoveBurnType = type;
        }
        return type;
    }

    private void tick() {
        if (stoves.isEmpty()) {
            stopTaskIfIdle();
            return;
        }

        if (++heartbeatTicks >= HEARTBEAT_LOG_INTERVAL) {
            heartbeatTicks = 0;
            debug(() -> "tick heartbeat: activeStoves=" + stoves.size());
        }
        List<Location> snapshot = getTickLocationsSnapshot();
        int size = snapshot.size();
        if (size == 0) {
            tickCursor = 0;
            stopTaskIfIdle();
            return;
        }
        int budget = Math.min(tickBudget, size);
        int start = tickCursor >= size ? 0 : tickCursor;

        for (int processed = 0; processed < budget; processed++) {
            Location location = snapshot.get((start + processed) % size);
            StoveData stove = stoves.get(location);
            if (stove == null) {
                markTickLocationsDirty();
                continue;
            }

            scheduleStoveTick(location, stove);
        }
        tickCursor = (start + Math.max(1, budget)) % size;
        stopTaskIfIdle();
    }

    private void scheduleStoveTick(Location location, StoveData stove) {
        if (!plugin.scheduler().isFolia()) {
            tickStove(location, stove);
            return;
        }

        if (!scheduledStoveTicks.add(location)) {
            return;
        }
        try {
            plugin.scheduler().runAt(location, () -> {
                try {
                    tickStove(location, stove);
                } finally {
                    scheduledStoveTicks.remove(location);
                }
            });
        } catch (RuntimeException e) {
            scheduledStoveTicks.remove(location);
        }
    }

    private void tickStove(Location location, StoveData stove) {
        if (stoves.get(location) != stove) {
            return;
        }

        World world = location.getWorld();
        if (world == null) return;
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        Block block = location.getBlock();
        if (block.getType().isAir()) {
            debug(() -> "tick remove: stove carrier block is air at " + formatLocation(location));
            cleanupAllVisuals(stove);
            removeStoredData(location);
            removeTrackedStove(location);
            return;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) {
            debug(() -> "tick state: stove custom state not available yet, skipping this tick at " + formatLocation(location));
            return;
        }

        if (!CustomBlockUtils.hasBehavior(state, StoveCookingBlockBehavior.class)
                && !CustomBlockUtils.hasId(state, Constants.BLOCK_STOVE)) {
            debug(() -> "tick state: stove state id mismatch, skipping this tick at " + formatLocation(location));
            return;
        }

        long currentBukkitTick = Bukkit.getCurrentTick();
        if (stove.blockedAboveCheckedTick == Long.MIN_VALUE
                || currentBukkitTick - stove.blockedAboveCheckedTick > BLOCKED_RECHECK_TICKS) {
            stove.blockedAbove = isStoveBlockedAbove(location);
            stove.blockedAboveCheckedTick = currentBukkitTick;
        }
        if (stove.blockedAbove) {
            debug(() -> "tick remove: stove blocked above, ejecting all items at " + formatLocation(location));
            ejectAllItems(location, stove);
            cleanupAllVisuals(stove);
            removeStoredData(location);
            removeTrackedStove(location);
            return;
        }

        boolean isLit = isStoveLit(state);
        // Entity burn moved out of the cooking tick into the always-on burnTick() poll: this tick only
        // runs for tracked (food-holding) stoves, but an empty lit stove must burn too, so the burn now
        // polls players/mobs independently of the cooking tracker.
        // CE stores `facing` with the vanilla furnace convention: the value points out of the stove's
        // front (toward the placing player). The display/slot pipeline expects the opposite face; the
        // ambient fire/smoke must use the front itself, mirroring StoveBlock.animateTick.
        BlockFace front = CustomBlockUtils.getFacing(state);
        BlockFace facing = front.getOppositeFace();
        // Cache the debug flag so per-slot debug lambdas are only allocated when debug is enabled.
        boolean debugStove = plugin.isDebugEnabled("stove");
        // Lazily resolve the crackle sound (one CE block-state lookup), at most once per tick
        // instead of once per crackling slot.
        String crackleSound = null;
        boolean crackleResolved = false;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (debugStove) {
            debug(() -> "tick state: lit=" + isLit + ", hasAnyItem=" + hasAnyItem(stove)
                    + ", location=" + formatLocation(location));
        }
        // Cache the "any player within particle/sound range" check once per tick so the per-slot
        // random rolls and broadcast calls below are skipped on empty regions. Bukkit drops packets
        // for far players internally, but the random + spawnParticle call cost still scales with
        // (stove count × tick rate). The cookingTime progression stays unconditional.
        // Use the chunk-holder tracked-player set (R-PERF-005) + per-player distance²: spark showed
        // world.getNearbyPlayers is the #2 CPU hot (~65k samples in a 3000-stove test) because it
        // walks the full online-player list per stove. The chunk-tracked set is typically <10 and
        // Paper maintains it as O(1) off the chunk holder.
        int stoveChunkX = location.getBlockX() >> 4;
        int stoveChunkZ = location.getBlockZ() >> 4;
        // Per-chunk per-tick effect context: one getPlayersSeeingChunk lookup shared by every stove in
        // the chunk this tick (the tracked set cannot change mid-region-tick), plus the packet budget
        // that caps a dense pocket of stoves to chunkEffectBudgetLimit particle/sound packets per Bukkit
        // tick. The outer map is world-keyed so identical chunk coordinates in different worlds never
        // share a budget. No chunk stagger is applied: the manager dispatches on a period-4 timer at a
        // fixed tick residue, so a Bukkit.getCurrentTick()-derived stagger never rotates — it would
        // permanently silence 3/4 of chunks. The chunk was checked loaded at the top of this method and
        // cannot unload within the same region tick, so getChunkAt cannot trigger a sync load here.
        long chunkKey = ((long) stoveChunkX << 32) | (stoveChunkZ & 0xffffffffL);
        if (currentBukkitTick != effectBudgetResetTick) {
            chunkFx.clear();
            effectBudgetResetTick = currentBukkitTick;
        }
        ChunkFxContext fx = chunkFx.computeIfAbsent(world.getUID(), w -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey, k -> new ChunkFxContext());
        List<Player> seeingPlayers = fx.seeing;
        if (seeingPlayers == null) {
            seeingPlayers = List.copyOf(world.getChunkAt(stoveChunkX, stoveChunkZ).getPlayersSeeingChunk());
            fx.seeing = seeingPlayers;
        }
        // Filter the cached chunk set down to this stove's effect range once, into a reusable per-thread
        // list. The list is both the "any player near?" gate and the exact recipient set for the
        // particle/sound sends below, so we target player.spawnParticle/playSound instead of
        // world.spawnParticle, which re-walks the whole world player list per call (R-PERF-006).
        List<Player> nearbyViewers = NEARBY_VIEWER_SCRATCH.get();
        nearbyViewers.clear();
        double viewDsq = effectViewerDistance * effectViewerDistance;
        // Compute squared distance by hand to avoid allocating a Location per candidate. Same world is
        // already guaranteed below, so this is equivalent to distanceSquared.
        double cx = location.getX();
        double cy = location.getY();
        double cz = location.getZ();
        for (Player p : seeingPlayers) {
            if (p.getWorld() != world) {
                continue;
            }
            double dx = p.getX() - cx;
            double dy = p.getY() - cy;
            double dz = p.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= viewDsq) {
                nearbyViewers.add(p);
            }
        }
        AtomicInteger chunkBudget = nearbyViewers.isEmpty() ? null : fx.budget;
        boolean canSpawnEffects = chunkBudget != null && chunkBudget.get() < chunkEffectBudgetLimit;
        if (isLit && canSpawnEffects && fireParticlesEnabled && random.nextDouble() < fireParticleChance) {
            spawnAmbientFireParticles(nearbyViewers, location, front, random);
            chunkBudget.addAndGet(2); // SMOKE + FLAME = 2 packets
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] == null || stove.items[i].getType().isAir()) {
                continue;
            }

            ensureVisualExists(location, stove, i, facing);
            int slot = i;
            if (debugStove) {
                debug(() -> "tick slot: slot=" + slot + ", progress=" + stove.cookingTime[slot] + "/" + stove.maxTime[slot]
                        + ", item=" + formatItem(stove.items[slot]) + ", lit=" + isLit
                        + ", location=" + formatLocation(location));
            }

            if (isLit) {
                stove.cookingTime[i]++;

                boolean canSpawnSlotEffects = canSpawnEffects && chunkBudget.get() < chunkEffectBudgetLimit;
                if (canSpawnSlotEffects && smokeEnabled && random.nextDouble() < smokeChance) {
                    spawnCookingParticles(nearbyViewers, location, i, facing);
                    chunkBudget.incrementAndGet();
                }
                if (canSpawnSlotEffects && crackleEnabled && random.nextDouble() < crackleChance) {
                    if (!crackleResolved) {
                        crackleSound = getCrackleSound(state);
                        crackleResolved = true;
                    }
                    SoundUtils.play(nearbyViewers, location, crackleSound, Sound.BLOCK_CAMPFIRE_CRACKLE, crackleVolume, cracklePitch);
                    chunkBudget.incrementAndGet();
                }
                if (stove.cookingTime[i] >= stove.maxTime[i]) {
                    int finishedSlot = i;
                    if (debugStove) {
                        debug(() -> "tick finish: slot=" + finishedSlot + ", item=" + formatItem(stove.items[finishedSlot])
                                + ", location=" + formatLocation(location));
                    }
                    finishCooking(location, stove, i);
                }
            } else {
                stove.cookingTime[i] = Math.max(0, stove.cookingTime[i] - coolingDecrement);
            }
        }

        if (!hasAnyItem(stove)) {
            removeStoredData(location);
            removeTrackedStove(location);
        }
    }

    private boolean isStoveLit(ImmutableBlockState state) {
        StoveCookingBlockBehavior behavior = CustomBlockUtils.getBehavior(state, StoveCookingBlockBehavior.class);
        return behavior != null && behavior.isLit(state);
    }

    public void invalidateBlockedAboveCache(Location location) {
        StoveData stove = stoves.get(ManagerSupport.normalize(location));
        if (stove != null) {
            stove.blockedAboveCheckedTick = Long.MIN_VALUE;
        }
    }

    public void invalidateBlockedAboveCacheNear(Location location) {
        invalidateBlockedAboveCache(location);
        invalidateBlockedAboveCache(location.clone().add(0, -1, 0));
    }

    private boolean isStoveBlockedAbove(Location location) {
        Block aboveBlock = location.clone().add(0, 1, 0).getBlock();
        var blockShape = aboveBlock.getBlockData().getCollisionShape(aboveBlock.getLocation());
        if (blockShape == null || blockShape.getBoundingBoxes().isEmpty()) {
            return false;
        }

        org.bukkit.util.BoundingBox grillingArea = new org.bukkit.util.BoundingBox(
                3.0D / 16.0D,
                0.0D,
                3.0D / 16.0D,
                13.0D / 16.0D,
                1.0D / 16.0D,
                13.0D / 16.0D
        );
        return blockShape.overlaps(grillingArea);
    }

    private void finishCooking(Location location, StoveData stove, int slot) {
        ItemStack input = stove.items[slot];
        if (input == null) return;

        CookingRecipe<?> recipe = findCampfireRecipe(input);
        debug("finish cooking: slot=" + slot + ", input=" + formatItem(input)
                + ", recipe=" + (recipe != null ? recipe.getKey() : "null")
                + ", location=" + formatLocation(location));
        ItemStack result = recipe != null ? recipe.getResult() : input;
        if (result != null && !result.getType().isAir()) {
            if (recipe != null && stove.ownerIds[slot] != null) {
                Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                        stove.ownerIds[slot],
                        stove.ownerNames[slot],
                        "stove",
                        result,
                        recipe.getExperience(),
                        location
                ));
            }
            location.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), result.clone());
        }

        stove.items[slot] = null;
        stove.cookingTime[slot] = 0;
        stove.maxTime[slot] = defaultCookTime;
        stove.ownerIds[slot] = null;
        stove.ownerNames[slot] = null;
        removeVisual(location, stove, slot);

        // Mark unconditionally: with other slots still occupied the disk copy would otherwise keep the
        // finished slot until the next unrelated write, and a crash would restore the already-dropped item.
        markStoveDirty(location);
    }

    private void ejectAllItems(Location location, StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] != null && !stove.items[i].getType().isAir()) {
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), stove.items[i].clone());
                stove.items[i] = null;
                stove.cookingTime[i] = 0;
                stove.maxTime[i] = defaultCookTime;
                stove.ownerIds[i] = null;
                stove.ownerNames[i] = null;
            }
        }
    }

    private void spawnCookingParticles(List<Player> viewers, Location location, int slot, BlockFace facing) {
        double[] offset = getRotatedSlotOffset(slot, facing);
        double px = location.getX() + 0.5 + offset[0];
        double py = location.getY() + offset[1] + smokeYOffset;
        double pz = location.getZ() + 0.5 + offset[2];
        ManagerSupport.spawnParticleFor(viewers, smokeParticle, px, py, pz,
                smokeCount, smokeOffsetX, smokeOffsetY, smokeOffsetZ, smokeSpeed);
    }

    private void spawnAmbientFireParticles(List<Player> viewers, Location location, BlockFace facing, ThreadLocalRandom random) {
        double horizontalSpread = random.nextDouble() * 0.6D - 0.3D;
        boolean axisX = facing == BlockFace.EAST || facing == BlockFace.WEST;
        boolean axisZ = facing == BlockFace.NORTH || facing == BlockFace.SOUTH;
        double xOffset = axisX ? facing.getModX() * 0.52D : horizontalSpread;
        double zOffset = axisZ ? facing.getModZ() * 0.52D : horizontalSpread;
        double yOffset = random.nextDouble() * 6.0D / 16.0D;
        double px = location.getX() + 0.5D + xOffset;
        double py = location.getY() + yOffset;
        double pz = location.getZ() + 0.5D + zOffset;
        ManagerSupport.spawnParticleFor(viewers, Particle.SMOKE, px, py, pz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        ManagerSupport.spawnParticleFor(viewers, Particle.FLAME, px, py, pz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private void createVisual(Location location, StoveData stove, int slot, BlockFace facing) {
        removeVisual(location, stove, slot);

        ItemStack item = stove.items[slot];
        if (item == null || item.getType().isAir()) {
            debug("spawn display: skipped empty item for slot=" + slot + ", location=" + formatLocation(location));
            return;
        }

        double[] offset = getRotatedSlotOffset(slot, facing);
        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager == null || !visualManager.isAvailable()) {
            debug("spawn display: visual manager unavailable for slot=" + slot + ", item=" + formatItem(item)
                    + ", location=" + formatLocation(location));
            return;
        }

        Location displayLocation = location.clone().add(0.5 + offset[0], offset[1], 0.5 + offset[2]);
        CuttingBoardDisplayConfig displayConfig = plugin.getStoveDisplayConfig();
        CuttingBoardDisplayConfig.DisplayOverride displayOverride = displayConfig.getOverride(item);
        ItemStack visualItem = displayConfig.resolveDisplayItem(item, displayOverride);
        if (visualItem == null || visualItem.getType().isAir()) {
            debug("spawn display: skipped unresolved display item for slot=" + slot + ", item=" + formatItem(item)
                    + ", location=" + formatLocation(location));
            return;
        }
        if (displayOverride.offset() != null) {
            double[] configuredOffset = DisplayTransformUtils.stoveSlotOffset(
                    (double) displayOverride.offset().x(),
                    (double) displayOverride.offset().y(),
                    (double) displayOverride.offset().z(),
                    facing
            );
            displayLocation.add(configuredOffset[0], configuredOffset[1], configuredOffset[2]);
        }

        boolean isBlockItem = switch (displayOverride.style()) {
            case BLOCK -> true;
            case ITEM -> false;
            default -> ItemUtils.shouldUseBlockStyleDisplay(visualItem);
        };
        float xRotation = isBlockItem ? 0.0F : -90.0F;
        float yRotation = DisplayTransformUtils.stoveYaw(facing);
        float zRotation = 0.0F;
        if (displayOverride.rotationDegrees() != null) {
            xRotation = displayOverride.rotationDegrees().x();
            yRotation = displayOverride.rotationDegrees().y();
            zRotation = displayOverride.rotationDegrees().z();
        }

        Quaternionf leftRotation = new Quaternionf();
        leftRotation.rotationYXZ(
                (float) Math.toRadians(yRotation),
                (float) Math.toRadians(xRotation),
                (float) Math.toRadians(zRotation)
        );

        Vector3f translation = displayOverride.translation() == null
                ? new Vector3f(0.0F, 0.0F, 0.0F)
                : new Vector3f(displayOverride.translation());
        Vector3f scale = displayOverride.scale() == null
                ? new Vector3f(plugin.getStoveDisplayScale(), plugin.getStoveDisplayScale(), plugin.getStoveDisplayScale())
                : new Vector3f(displayOverride.scale());
        Transformation transformation = new Transformation(
                translation,
                leftRotation,
                scale,
                new Quaternionf()
        );

        stove.displayEntities[slot] = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                displayLocation,
                visualItem,
                org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED,
                transformation
        ));
        debug("spawn display: slot=" + slot + ", entityId=" + stove.displayEntities[slot] + ", item=" + formatItem(visualItem)
                + ", location=" + formatLocation(location));
    }

    private void ensureVisualExists(Location location, StoveData stove, int slot, BlockFace facing) {
        int entityId = stove.displayEntities[slot];
        if (entityId < 0) {
            createVisual(location, stove, slot, facing);
        }
    }

    private double[] getRotatedSlotOffset(int slot, BlockFace facing) {
        double[] offset = slotOffsets[slot];
        return DisplayTransformUtils.stoveSlotOffset(offset[0], offset[1], offset[2], facing);
    }

    private void refreshVisualsAfterConfigReload() {
        if (stoves.isEmpty()) {
            return;
        }
        for (StoveData stove : stoves.values()) {
            if (stove == null || stove.location == null) {
                continue;
            }
            Location stoveLoc = stove.location;
            plugin.scheduler().runAt(stoveLoc, () -> {
                BlockFace facing = CustomBlockUtils.getFacing(stoveLoc.getBlock()).getOppositeFace();
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    if (stove.items[slot] != null && !stove.items[slot].getType().isAir()) {
                        createVisual(stoveLoc, stove, slot, facing);
                    }
                }
            });
        }
    }

    private void removeVisual(Location location, StoveData stove, int slot) {
        int entityId = stove.displayEntities[slot];
        if (entityId < 0) return;
        stove.displayEntities[slot] = -1;

        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager != null) {
            visualManager.destroyDisplay(entityId);
        }
    }

    private void cleanupAllVisuals(StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            removeVisual(stove.location, stove, i);
        }
    }

    private boolean retrieveItem(Player player, Location location, StoveData stove) {
        int slot = findBestRetrievalSlot(stove);
        if (slot < 0) {
            return false;
        }

        ItemStack item = stove.items[slot];
        if (item == null || item.getType().isAir()) {
            return false;
        }

        ItemStack toReturn = item.clone();
        stove.items[slot] = null;
        stove.cookingTime[slot] = 0;
        stove.maxTime[slot] = defaultCookTime;
        stove.ownerIds[slot] = null;
        stove.ownerNames[slot] = null;
        removeVisual(location, stove, slot);

        if (player.getInventory().getItemInMainHand().getType().isAir()) {
            player.getInventory().setItemInMainHand(toReturn);
        } else {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(toReturn);
            for (ItemStack leftover : leftovers.values()) {
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), leftover);
            }
        }

        // Mark unconditionally: with other slots still occupied the disk copy would otherwise keep the
        // retrieved slot until the next unrelated write, and a crash would duplicate the taken item.
        markStoveDirty(location);
        location.getWorld().playSound(location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.0f);
        return true;
    }

    private int findBestRetrievalSlot(StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] != null && !stove.items[i].getType().isAir() && stove.cookingTime[i] >= stove.maxTime[i]) {
                return i;
            }
        }
        for (int i = SLOT_COUNT - 1; i >= 0; i--) {
            if (stove.items[i] != null && !stove.items[i].getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    private String getCrackleSound(ImmutableBlockState state) {
        // The caller already holds the validated block state; resolving the behavior from it skips the
        // full CE block-state re-fetch that getBlockBehavior(location) would pay.
        StoveCookingBlockBehavior behavior = CustomBlockUtils.getBehavior(state, StoveCookingBlockBehavior.class);
        if (behavior != null) {
            return behavior.getCrackleSound();
        }
        return Constants.SOUND_STOVE_CRACKLE;
    }

    private CookingRecipe<?> findCampfireRecipe(ItemStack item) {
        return campfireRecipes.find(item);
    }

    private void debug(String message) {
        if (plugin.isDebugEnabled("stove")) {
            plugin.getLogger().info(I18n.formatConsole("debug.stove", "message", message));
        }
    }

    private void debug(Supplier<String> messageSupplier) {
        if (plugin.isDebugEnabled("stove")) {
            plugin.getLogger().info(I18n.formatConsole("debug.stove", "message", messageSupplier.get()));
        }
    }

    private String formatItem(ItemStack item) {
        return ManagerSupport.formatItem(item);
    }

    private String formatLocation(Location location) {
        return ManagerSupport.formatLocation(location);
    }

    private void saveStove(Location location, StoveData stove) {
        Location normalized = ManagerSupport.normalize(location);
        if (stove == null || !hasAnyItem(stove)) {
            debug("save state: removing persisted stove state at " + formatLocation(normalized));
            markStoveDirty(normalized);
            return;
        }

        markStoveDirty(normalized);
        debug("save state: slots=" + countSavedSlots(stove) + ", location=" + formatLocation(normalized));
    }

    public Map<String, Object> exportStoveData(org.bukkit.World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return Map.of();
        }
        StoveData stove = stoves.get(ManagerSupport.toLocation(world, posKey));
        if (stove == null || !hasAnyItem(stove)) {
            return Map.of();
        }
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack item = stove.items[i];
            if (item != null && !item.getType().isAir()) {
                data.put("slot_" + i + "_item", item.clone());
                data.put("slot_" + i + "_progress", stove.cookingTime[i]);
                data.put("slot_" + i + "_duration", stove.maxTime[i]);
                if (stove.ownerIds[i] != null) {
                    data.put("slot_" + i + "_owner_id", stove.ownerIds[i].toString());
                }
                if (stove.ownerNames[i] != null) {
                    data.put("slot_" + i + "_owner_name", stove.ownerNames[i]);
                }
            }
        }
        return data;
    }

    private int countSavedSlots(StoveData stove) {
        int count = 0;
        if (stove == null) {
            return 0;
        }
        for (ItemStack item : stove.items) {
            if (item != null && !item.getType().isAir()) {
                count++;
            }
        }
        return count;
    }

    private void markStoveDirty(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null || normalized.getWorld() == null) {
            return;
        }
        CustomBlockUtils.markBlockEntityDirty(normalized.getWorld(), new BlockPosKey(normalized));
    }

    private void removeStoredData(Location location) {
        markStoveDirty(location);
    }

}
