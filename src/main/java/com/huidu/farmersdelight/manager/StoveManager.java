package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.api.util.ItemDelivery;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveBlockEntityController;
import com.huidu.farmersdelight.api.block.StoveSnapshot;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CampfireRecipeCache;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
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

    private static final int SLOT_COUNT = StoveData.SLOT_COUNT;
    private static final int DEFAULT_COOK_TIME = 600;
    private static final int HEARTBEAT_LOG_INTERVAL = 20;
    private static final int DEFAULT_TICK_BUDGET = 512;
    private static final int DEFAULT_COOLING_DECREMENT = 2;
    private static final double DEFAULT_SMOKE_CHANCE = Constants.STOVE_PARTICLE_CHANCE;
    private static final double DEFAULT_CRACKLE_CHANCE = Constants.STOVE_CRACKLE_CHANCE;

    private final FarmersDelightPlugin plugin;
    private final StoveVisualManager visualManager;
    private final Map<Location, StoveData> stoves = new ConcurrentHashMap<>();
    private final WorldChunkLocationIndex locationIndex = new WorldChunkLocationIndex();
    // Blocked-above freshness lives on StoveData (tick-stamp TTL + event invalidation); this constant
    // is the recheck period for non-event shape changes (pistons, falling blocks), ~30s at 20 TPS.
    private static final long BLOCKED_RECHECK_TICKS = 600L;
    private final Set<Location> scheduledStoveTicks = ConcurrentHashMap.newKeySet();
    private final AtomicLong tickLocationsVersion = new AtomicLong();
    private volatile List<Location> tickLocationsSnapshot = List.of();
    private volatile long tickLocationsSnapshotVersion = -1L;
    private final CampfireRecipeCache campfireRecipes = new CampfireRecipeCache("stove", this::debug);
    // Region events start tickTask; the global task may cancel it when idle.
    // Volatile publishes the handle, and the lock makes start/stop checks atomic to prevent duplicate tasks.
    private volatile PluginTask tickTask;
    private final Object tickTaskLock = new Object();
    private int heartbeatTicks;
    private int tickCursor;
    private int tickBudget;
    // The following are reload-written (reload/command thread) and read on Folia region tick threads —
    // volatile for a happens-before edge, matching effectViewerDistance below.
    private volatile int defaultCookTime = DEFAULT_COOK_TIME;
    private volatile int coolingDecrement = DEFAULT_COOLING_DECREMENT;
    private volatile boolean smokeEnabled = true;
    private volatile Particle smokeParticle = Particle.SMOKE;
    private volatile double smokeChance = DEFAULT_SMOKE_CHANCE;
    private volatile int smokeCount = 1;
    private volatile double smokeYOffset = 0.0D;
    private volatile double smokeOffsetX = 0.0D;
    private volatile double smokeOffsetY = 0.0D;
    private volatile double smokeOffsetZ = 0.0D;
    private volatile double smokeSpeed = 0.02D;
    private volatile boolean crackleEnabled = true;
    private volatile double crackleChance = DEFAULT_CRACKLE_CHANCE;
    private volatile float crackleVolume = 1.0F;
    private volatile float cracklePitch = 1.0F;
    private volatile boolean fireParticlesEnabled = true;
    private volatile double fireParticleChance = 0.15D;
    // Written in reloadConfig (reload thread), read on Folia region tick threads (effect/burn) — volatile
    // for a happens-before edge, matching the other reload-mutated tick-read fields.
    private volatile double effectViewerDistance = 32.0D;
    // Per-chunk per-tick effect context: the packet budget (hard cap so a dense pocket of stoves —
    // 60/chunk × 4 slot rolls — can't steamroll the packet queue in one Bukkit tick) plus the tick's
    // chunk-tracked player list, fetched once and shared by every stove in the chunk. World-keyed so
    // identical chunk coordinates in different worlds never collide. Cleared on tick rollover.
    private volatile int chunkEffectBudgetLimit = 50;
    private final Map<UUID, Map<Long, ChunkFxContext>> chunkFx = new ConcurrentHashMap<>();

    private static final class ChunkFxContext {
        final AtomicInteger budget = new AtomicInteger();
        volatile List<Player> seeing;
        // The tick this chunk's budget was last reset on. Per chunk rather than one field for the whole
        // manager: on Folia Bukkit.getCurrentTick() reports the CURRENT REGION's counter, so two regions
        // ticking stoves would disagree on a shared field almost every call, clear the whole map each
        // time, and leave the per-chunk packet cap never actually applying to anything.
        volatile long budgetTick = Long.MIN_VALUE;
    }

    // Reusable per-thread recipient list for targeted particle/sound sends. Per-thread so it stays safe
    // under Folia's concurrent per-region stove ticks; refilled (cleared) at the start of each stove's
    // effect emission and consumed synchronously within the same tick, so it never escapes.
    private static final ThreadLocal<List<Player>> NEARBY_VIEWER_SCRATCH = ThreadLocal.withInitial(ArrayList::new);

    public StoveManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.visualManager = new StoveVisualManager(plugin);
        reloadConfig();
        campfireRecipes.rebuild();
    }

    public void reloadConfig() {
        this.tickBudget = Math.max(1, plugin.getConfigInt(DEFAULT_TICK_BUDGET,
                "stove.tick-budget",
                "performance.stove-tick-budget"));
        this.defaultCookTime = Math.max(1, plugin.getConfigInt(DEFAULT_COOK_TIME,
                "stove.cooking.default-cook-time",
                "stove.default-cook-time"));
        this.coolingDecrement = Math.max(0, plugin.getConfigInt(DEFAULT_COOLING_DECREMENT,
                "stove.cooking.cooling-decrement",
                "stove.cooling-decrement"));
        this.chunkEffectBudgetLimit = Math.max(1, plugin.getConfigInt(50, "performance.chunk-effect-packet-budget"));
        loadEffectsConfig();
        visualManager.reloadSlotOffsets();
        visualManager.refreshAll(stoves.values());
    }

    private void loadEffectsConfig() {
        ConfigurationSection effectsSection = plugin.getFirstConfigSection("stove.effects");
        ConfigurationSection smokeSection = effectsSection != null ? effectsSection.getConfigurationSection("smoke") : null;
        smokeEnabled = smokeSection == null || ConfigSectionReader.optionalBoolean(smokeSection, "enabled", true);
        smokeParticle = ManagerSupport.resolveParticle(smokeSection == null ? null : ConfigSectionReader.optionalString(smokeSection, "type"), Particle.SMOKE);
        smokeChance = ManagerSupport.clampChance(smokeSection == null
                ? DEFAULT_SMOKE_CHANCE
                : ConfigSectionReader.optionalDouble(smokeSection, "chance", DEFAULT_SMOKE_CHANCE));
        smokeCount = Math.max(1, smokeSection == null ? 1 : ConfigSectionReader.optionalInt(smokeSection, "count", 1));
        smokeYOffset = smokeSection == null ? 0.0D : ConfigSectionReader.optionalDouble(smokeSection, "y-offset", 0.0D);
        smokeOffsetX = Math.max(0.0D, smokeSection == null ? 0.0D : ConfigSectionReader.optionalDouble(smokeSection, "offset-x", 0.0D));
        smokeOffsetY = Math.max(0.0D, smokeSection == null ? 0.0D : ConfigSectionReader.optionalDouble(smokeSection, "offset-y", 0.0D));
        smokeOffsetZ = Math.max(0.0D, smokeSection == null ? 0.0D : ConfigSectionReader.optionalDouble(smokeSection, "offset-z", 0.0D));
        smokeSpeed = Math.max(0.0D, smokeSection == null ? 0.02D : ConfigSectionReader.optionalDouble(smokeSection, "speed", 0.02D));

        ConfigurationSection crackleSection = effectsSection != null ? effectsSection.getConfigurationSection("crackle") : null;
        crackleEnabled = crackleSection == null || ConfigSectionReader.optionalBoolean(crackleSection, "enabled", true);
        crackleChance = ManagerSupport.clampChance(crackleSection == null
                ? DEFAULT_CRACKLE_CHANCE
                : ConfigSectionReader.optionalDouble(crackleSection, "chance", DEFAULT_CRACKLE_CHANCE));
        crackleVolume = (float) Math.max(0.0D, crackleSection == null ? 1.0D : ConfigSectionReader.optionalDouble(crackleSection, "volume", 1.0D));
        cracklePitch = (float) Math.max(0.0D, crackleSection == null ? 1.0D : ConfigSectionReader.optionalDouble(crackleSection, "pitch", 1.0D));

        ConfigurationSection fireSection = effectsSection != null ? effectsSection.getConfigurationSection("fire") : null;
        fireParticlesEnabled = fireSection == null || ConfigSectionReader.optionalBoolean(fireSection, "enabled", true);
        fireParticleChance = ManagerSupport.clampChance(fireSection == null
                ? 0.15D
                : ConfigSectionReader.optionalDouble(fireSection, "chance", 0.15D));
        effectViewerDistance = Math.max(0.0D, effectsSection == null
                ? 32.0D
                : ConfigSectionReader.optionalDouble(effectsSection, "viewer-distance", 32.0D));
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
        StoveData existing = stoves.get(normalized);
        if (existing != null) {
            return existing;
        }

        // Saved data may still be parked on the controller (deferred startup load, or a chunk served from
        // CraftEngine's chunk cache where loadCustomData never re-ran); apply it before creating a blank
        // entry that would shadow the stored contents and let the late apply overwrite this interaction.
        if (normalized.getWorld() != null) {
            CustomBlockUtils.notifyControllerChanged(normalized.getWorld(), new BlockPosKey(normalized),
                    StoveBlockEntityController.class, null,
                    StoveBlockEntityController::loadPendingDataIfReady);
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
        locationIndex.add(normalized);
        markTickLocationsDirty();
        return created;
    }

    public void collectLiveDisplayIds(Set<Integer> out) {
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
            debug(() -> "Stove interact blocked above for " + formatItem(itemInHand) + " at " + formatLocation(location));
            return false;
        }

        StoveData stove = getOrLoadStove(location);

        CookingRecipe<?> recipe = findCampfireRecipe(itemInHand);
        if (recipe == null) {
            debug(() -> "Stove interact no campfire recipe for " + formatItem(itemInHand) + " at " + formatLocation(location));
            return false;
        }

        // Atomic findEmpty + claim: without the lock, two concurrent right-clicks from different Folia
        // regions can each receive the same slot, both write, last write wins — first player's food is
        // consumed (heldItem.setAmount-1) but the slot now holds B's food, so A loses the item silently.
        int emptySlot;
        synchronized (stove) {
            emptySlot = findEmptySlot(stove);
            if (emptySlot < 0) {
                debug(() -> "Stove interact no empty slot for " + formatItem(itemInHand) + " at " + formatLocation(location));
                return false;
            }
            ItemStack toPlace = itemInHand.clone();
            toPlace.setAmount(1);
            stove.items[emptySlot] = toPlace;
            stove.cookingTime[emptySlot] = 0;
            stove.maxTime[emptySlot] = recipe.getCookingTime() > 0 ? recipe.getCookingTime() : defaultCookTime;
            stove.ownerIds[emptySlot] = player.getUniqueId();
            stove.ownerNames[emptySlot] = player.getName();
            debug(() -> "create state: slot=" + emptySlot + ", stored=" + formatItem(toPlace)
                    + ", duration=" + stove.maxTime[emptySlot] + ", location=" + formatLocation(location));
        }

        visualManager.createVisual(location, stove, emptySlot, CustomBlockUtils.getFacing(block).getOppositeFace());
        saveStove(location, stove);
        if (player.getGameMode() != GameMode.CREATIVE) {
            debug(() -> "consume: slot=" + emptySlot + ", before=" + itemInHand.getAmount() + ", after=" + (itemInHand.getAmount() - 1)
                    + ", item=" + formatItem(itemInHand) + ", location=" + formatLocation(location));
            itemInHand.setAmount(itemInHand.getAmount() - 1);
        } else {
            debug(() -> "consume: skipped for creative mode, slot=" + emptySlot + ", item=" + formatItem(itemInHand)
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
                StoveBlockEntityController.class, null,
                StoveBlockEntityController::loadPendingDataIfReady);
    }

    public void breakStove(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        Location normalized = ManagerSupport.normalize(blockLocation);
        flushControllerPendingData(normalized);
        StoveData stove = removeTrackedStove(normalized);
        if (stove != null) {
            visualManager.cleanupAllVisuals(stove);
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
        List<Location> locations = locationIndex.worldLocations(world.getUID());
        if (locations.isEmpty()) {
            return;
        }
        for (Location location : locations) {
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

        List<Location> indexed = locationIndex.worldLocations(world.getUID());
        if (indexed.isEmpty()) {
            return List.of();
        }

        List<Location> result = new ArrayList<>(indexed.size());
        for (Location loc : indexed) {
            result.add(loc.clone());
        }
        return result;
    }

    public void cleanupWorld(UUID worldId) {
        chunkFx.remove(worldId);
        List<Location> locations = locationIndex.removeWorld(worldId);
        if (locations.isEmpty()) {
            return;
        }
        boolean removedAny = false;
        for (Location location : locations) {
            StoveData stove = stoves.remove(location);
            if (stove != null) {
                removedAny = true;
                scheduledStoveTicks.remove(location);
                visualManager.cleanupAllVisuals(stove);
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
        Map<Long, ChunkFxContext> worldFx = chunkFx.get(world.getUID());
        if (worldFx != null) worldFx.remove(ManagerSupport.chunkKey(minX >> 4, minZ >> 4));
        List<Location> locations = locationIndex.chunkLocationsAtBlock(world.getUID(), minX, minZ);
        if (locations.isEmpty()) {
            return;
        }
        for (Location location : locations) {
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
                StoveBlockEntityController.class, null,
                controller -> stashed[0] = controller.passivate());
        return stashed[0];
    }

    public boolean loadStove(World world, BlockPos pos, Map<String, Object> data) {
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
        if (stoves.containsKey(ManagerSupport.normalize(location))) {
            // A live entry exists (created by an interaction before this deferred load applied); the live
            // state is newer than the saved snapshot, so consume the snapshot without overwriting it.
            return true;
        }

        StoveData stove = new StoveData(location, defaultCookTime);
        boolean hasAnyItem = false;
        BlockFace facing = CustomBlockUtils.getFacing(location.getBlock()).getOppositeFace();

        for (int i = 0; i < SLOT_COUNT; i++) {
            Object itemObject = data.get(slotItemKey(i));
            if (itemObject instanceof ItemStack item && !item.getType().isAir()) {
                stove.items[i] = item.clone();
                stove.cookingTime[i] = data.get(slotProgressKey(i)) instanceof Number progress ? progress.intValue() : 0;
                stove.maxTime[i] = data.get(slotDurationKey(i)) instanceof Number duration ? duration.intValue() : defaultCookTime;
                if (data.get(slotOwnerIdKey(i)) instanceof String ownerId) {
                    try {
                        stove.ownerIds[i] = UUID.fromString(ownerId);
                    } catch (IllegalArgumentException ignored) {
                        stove.ownerIds[i] = null;
                    }
                }
                if (data.get(slotOwnerNameKey(i)) instanceof String ownerName) {
                    stove.ownerNames[i] = ownerName;
                }
                visualManager.createVisual(location, stove, i, facing);
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

    public StoveSnapshot snapshot(Location location) {
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
            return new StoveSnapshot(
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
        for (StoveData stove : stoves.values()) {
            visualManager.cleanupAllVisuals(stove);
        }
        stoves.clear();
        locationIndex.clear();
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
            visualManager.cleanupAllVisuals(stove);
        }
        stopTaskIfIdle();
    }

    private void putStove(Location location, StoveData stove) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData previous = stoves.put(normalized, stove);
        if (previous != null && previous != stove) {
            // Overwriting a still-tracked stove (double chunk-load / reload re-scan): destroy the old stove's
            // item displays so they don't orphan (the incoming stove already created its own visuals).
            visualManager.cleanupAllVisuals(previous);
        }
        locationIndex.add(normalized);
        markTickLocationsDirty();
    }

    private StoveData removeTrackedStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData removed = stoves.remove(normalized);
        if (removed != null) {
            scheduledStoveTicks.remove(normalized);
            locationIndex.remove(normalized);
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
        PerformanceMonitor.Timing timing = plugin.getTickManager().featureTiming(PerformanceMonitor.Feature.STOVE);
        long started = timing == null ? 0L : System.nanoTime();
        try {
            advanceCooking(location, stove);
        } finally {
            if (timing != null) timing.record(System.nanoTime() - started);
        }
    }

    private void advanceCooking(Location location, StoveData stove) {
        if (stoves.get(location) != stove) {
            return;
        }

        World world = location.getWorld();
        if (world == null) return;
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        Block block = location.getBlock();
        if (block.getType().isAir()) {
            debug(() -> "tick remove: stove carrier block is air at " + formatLocation(location));
            visualManager.cleanupAllVisuals(stove);
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
            visualManager.cleanupAllVisuals(stove);
            removeStoredData(location);
            removeTrackedStove(location);
            return;
        }

        boolean isLit = isStoveLit(state);
        // Entity burn uses the always-on burnTick() poll because the cooking tick runs only for tracked
        // food-holding stoves. This keeps empty lit stoves hazardous.
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
        // Cache chunk observers for the current tick and filter by distance for each stove.
        // This avoids a world-wide player lookup per stove; cooking progress does not depend on viewers.
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
        long chunkKey = ManagerSupport.chunkKey(stoveChunkX, stoveChunkZ);
        ChunkFxContext fx = chunkFx.computeIfAbsent(world.getUID(), w -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey, k -> new ChunkFxContext());
        // A chunk is only ticked by the one region that owns it, so this comparison is against that
        // region's own clock and the reset happens exactly once per tick per chunk.
        if (fx.budgetTick != currentBukkitTick) {
            fx.budgetTick = currentBukkitTick;
            fx.budget.set(0);
            fx.seeing = null;
        }
        List<Player> seeingPlayers = fx.seeing;
        if (seeingPlayers == null) {
            seeingPlayers = List.copyOf(world.getChunkAt(stoveChunkX, stoveChunkZ).getPlayersSeeingChunk());
            fx.seeing = seeingPlayers;
        }
        // Filter chunk observers into a per-thread list and send directly to those recipients.
        // This avoids repeating a world-wide player scan for each particle or sound broadcast.
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

            visualManager.ensureVisualExists(location, stove, i, facing);
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
        // Drop the player references as soon as the emission ends: this scratch list is cached on the region
        // thread, which outlives the players it last saw.
        nearbyViewers.clear();
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

        BoundingBox grillingArea = new BoundingBox(
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
        debug(() -> "finish cooking: slot=" + slot + ", input=" + formatItem(input)
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
        visualManager.removeVisual(location, stove, slot);

        // Mark unconditionally: with other slots still occupied the disk copy would otherwise keep the
        // finished slot until the next unrelated write, and a crash would restore the already-dropped item.
        markStoveDirty(location);
    }

    private void ejectAllItems(Location location, StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] != null && !stove.items[i].getType().isAir()) {
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), stove.items[i].clone());
                clearSlot(stove, i);
            }
        }
    }

    /** Clears one slot's item, timer and owner attribution; shared by every path that removes a slot. */
    private void clearSlot(StoveData stove, int slot) {
        stove.items[slot] = null;
        stove.cookingTime[slot] = 0;
        stove.maxTime[slot] = defaultCookTime;
        stove.ownerIds[slot] = null;
        stove.ownerNames[slot] = null;
    }

    private void spawnCookingParticles(List<Player> viewers, Location location, int slot, BlockFace facing) {
        double[] offset = visualManager.getRotatedSlotOffset(slot, facing);
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
        clearSlot(stove, slot);
        visualManager.removeVisual(location, stove, slot);

        if (player.getInventory().getItemInMainHand().getType().isAir()) {
            player.getInventory().setItemInMainHand(toReturn);
        } else {
            ItemDelivery.giveOrDrop(player, location.clone().add(0.5, 1.0, 0.5), toReturn);
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
            debug(() -> "save state: removing persisted stove state at " + formatLocation(normalized));
            markStoveDirty(normalized);
            return;
        }

        markStoveDirty(normalized);
        debug(() -> "save state: slots=" + countSavedSlots(stove) + ", location=" + formatLocation(normalized));
    }

    public Map<String, Object> exportStoveData(World world, BlockPosKey posKey) {
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
                data.put(slotItemKey(i), item.clone());
                data.put(slotProgressKey(i), stove.cookingTime[i]);
                data.put(slotDurationKey(i), stove.maxTime[i]);
                if (stove.ownerIds[i] != null) {
                    data.put(slotOwnerIdKey(i), stove.ownerIds[i].toString());
                }
                if (stove.ownerNames[i] != null) {
                    data.put(slotOwnerNameKey(i), stove.ownerNames[i]);
                }
            }
        }
        return data;
    }

    // Persisted slot field names, shared by the load and export paths so the two sides cannot drift apart
    // and silently drop a field from saved stoves.
    private static String slotItemKey(int slot) {
        return "slot_" + slot + "_item";
    }

    private static String slotProgressKey(int slot) {
        return "slot_" + slot + "_progress";
    }

    private static String slotDurationKey(int slot) {
        return "slot_" + slot + "_duration";
    }

    private static String slotOwnerIdKey(int slot) {
        return "slot_" + slot + "_owner_id";
    }

    private static String slotOwnerNameKey(int slot) {
        return "slot_" + slot + "_owner_name";
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
