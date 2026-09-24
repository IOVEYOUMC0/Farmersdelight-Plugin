package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

public class TickManager {

    private final FarmersDelightPlugin plugin;
    private final CookingPotEffectManager effectManager;
    private final PerformanceMonitor performanceMonitor;
    private PluginTask tickTask;
    private PluginTask cleanupTask;
    private volatile boolean running = false;
    
    private final Set<ActiveBlock> activeBlocks = ConcurrentHashMap.newKeySet();
    // Lock-free mark queue: producers are event-driven (GUI clicks, block interactions, chunk loads)
    // and the single consumer is tick(). FIFO drain preserves last-operation-wins semantics per block.
    private final ConcurrentLinkedQueue<PendingChange> pendingChanges = new ConcurrentLinkedQueue<>();

    private record PendingChange(ActiveBlock block, boolean add) {
    }
    private final Map<ActiveBlock, Long> lastProcessedTicks = new ConcurrentHashMap<>();
    private final Map<ActiveBlock, Long> progressDisplayLastUpdateTicks = new ConcurrentHashMap<>();
    // Heat source checks (two CE state fetches: isHeatSource + isConductor) are cached per block and
    // only refreshed every HEAT_SOURCE_CHECK_INTERVAL_TICKS; a block-below heat source rarely changes.
    private final Map<ActiveBlock, Long> heatSourceLastCheckTicks = new ConcurrentHashMap<>();
    private static final int HEAT_SOURCE_CHECK_INTERVAL_TICKS = 10;
    private final Set<ActiveBlock> scheduledActiveBlocks = ConcurrentHashMap.newKeySet();
    private volatile List<ActiveBlock> activeBlockSnapshot = List.of();
    private volatile int activeCookingPotCount;
    private boolean activeBlockLimitWarningShown;
    private int activeBlockCursor;
    private int cleanupCursor;
    // Reload-written on the reload/command thread, read by the global tick thread — volatile for a
    // happens-before edge (Folia keeps reload on a different thread than the tick).
    private volatile int cookingPotTickBudget = 512;
    private volatile int cookingPotProgressDisplayUpdateIntervalTicks = 8;
    private volatile int cookingPotProgressDisplayDisableAboveActivePots = 512;
    private volatile int activeBlockWarningThreshold = 1000;
    private final AtomicLong foliaTickClock = new AtomicLong();

    private static final int TICK_INTERVAL = 4;
    private static final int DEFAULT_ACTIVE_BLOCK_WARNING_THRESHOLD = 1000;
    private static final int CLEANUP_INTERVAL = 6000;
    // Bounds how many region tasks one cleanup run submits; see scheduleCookingPotCleanup.
    private static final int CLEANUP_DISPATCH_BUDGET = 128;
    private static final int DEFAULT_COOKING_POT_TICK_BUDGET = 512;
    // Max catch-up ticks applied in a single processing pass. Large enough that cooking pots starved by the tick budget
    // (active blocks far exceeding the budget) don't lose real elapsed cooking time. This does not "cook a huge batch on reload":
    // tickCookingPot uses Math.min(duration, ...) to cap progress at the recipe duration, and finishes cooking at most once per
    // pass (a single if, not a loop), producing only one batch regardless of catch-up size; previousTick is also reset on
    // (re)activation and refreshed every time the pot is selected, so catch-up never includes unloaded time.
    // The cap still keeps a sane bound to avoid int overflow at elapsedTicks*2 in the cooldown branch.
    private static final int MAX_ELAPSED_TICKS = 72_000;
    public TickManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.effectManager = new CookingPotEffectManager(plugin);
        this.performanceMonitor = new PerformanceMonitor(plugin);
        reloadConfig();
    }

    public void reloadConfig() {
        effectManager.reloadConfig();
        cookingPotTickBudget = Math.max(1, plugin.getConfigInt(DEFAULT_COOKING_POT_TICK_BUDGET,
                "cooking-pot.tick-budget",
                "performance.cooking-pot-tick-budget"));
        cookingPotProgressDisplayUpdateIntervalTicks = Math.max(1,
                plugin.getCookingPotProgressDisplayUpdateIntervalTicks());
        cookingPotProgressDisplayDisableAboveActivePots = Math.max(0,
                plugin.getCookingPotProgressDisplayDisableAboveActivePots());
        activeBlockWarningThreshold = Math.max(1, plugin.getConfigInt(DEFAULT_ACTIVE_BLOCK_WARNING_THRESHOLD,
                "performance.active-block-warning-threshold",
                "performance.max-active-blocks-warning"));
        performanceMonitor.reloadConfig();
    }

    public void start() {
        if (running) return;
        running = true;
        
        tickTask = plugin.scheduler().runRepeating(this::tick, 1L, TICK_INTERVAL);
        cleanupTask = plugin.scheduler().runRepeating(this::performCleanup, CLEANUP_INTERVAL, CLEANUP_INTERVAL);
        I18n.logDetail("startup", "tick.started", "interval", TICK_INTERVAL);
    }

    public void stop() {
        running = false;
        performanceMonitor.stop();
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        
        activeBlocks.clear();
        activeBlockSnapshot = List.of();
        activeCookingPotCount = 0;
        pendingChanges.clear();
        lastProcessedTicks.clear();
        progressDisplayLastUpdateTicks.clear();
        heatSourceLastCheckTicks.clear();
        scheduledActiveBlocks.clear();
        effectManager.cleanup();

        I18n.logDetail("startup", "tick.stopped");
    }
    
    private void performCleanup() {
        performanceMonitor.checkPooledWarnings();
        performanceMonitor.pruneOldWarnings();
        if (plugin.scheduler().isFolia()) {
            scheduleCookingPotCleanup();
            return;
        }

        int cleanedCount = cleanupInvalidCookingPotBlockEntities();
        
        if (cleanedCount > 0) {
            I18n.logInfo("tick.cleanup_completed", "count", cleanedCount);
        }
    }

    // The tracked block-entity set can hold thousands of entries, and submitting all of them in one tick spikes
    // the region scheduler. Each run covers CLEANUP_DISPATCH_BUDGET entries from a rotating cursor, so every
    // entry is still reached across successive runs (a stale entry merely survives one more interval).
    private void scheduleCookingPotCleanup() {
        List<Location> locations = CookingPotBlockBehavior.getBlockEntityLocations();
        int size = locations.size();
        if (size == 0) {
            cleanupCursor = 0;
            return;
        }
        int budget = Math.min(CLEANUP_DISPATCH_BUDGET, size);
        int start = cleanupCursor >= size ? 0 : cleanupCursor;

        for (int processed = 0; processed < budget; processed++) {
            Location location = locations.get((start + processed) % size);
            if (location == null || location.getWorld() == null) {
                continue;
            }
            try {
                plugin.scheduler().runAt(location, () -> cleanupCookingPotBlockEntity(
                        location.getWorld(),
                        new BlockPosKey(location)
                ));
            } catch (RuntimeException ignored) {
            }
        }

        cleanupCursor = (start + Math.max(1, budget)) % size;
    }
    
    private int cleanupInvalidCookingPotBlockEntities() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            Map<BlockPosKey, CookingPotBlockEntity> entities = CookingPotBlockBehavior.getAllBlockEntities(world);
            for (BlockPosKey posKey : entities.keySet()) {
                if (cleanupInvalidCookingPotBlockEntity(world, posKey)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void cleanupCookingPotBlockEntity(World world, BlockPosKey posKey) {
        cleanupInvalidCookingPotBlockEntity(world, posKey);
    }

    private boolean cleanupInvalidCookingPotBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return false;
        }

        if (!CookingPotBlockBehavior.hasCookingPotBehavior(world, posKey)) {
            CookingPotBlockBehavior.removeBlockEntity(world, posKey);
            return true;
        }
        return false;
    }
    
    public void markActive(World world, BlockPosKey posKey, BlockType type) {
        if (world == null || posKey == null || type == null) return;
        pendingChanges.add(new PendingChange(new ActiveBlock(world.getUID(), world, posKey, type), true));
    }

    public void markInactive(World world, BlockPosKey posKey, BlockType type) {
        if (world == null || posKey == null || type == null) return;
        pendingChanges.add(new PendingChange(new ActiveBlock(world.getUID(), world, posKey, type), false));
    }

    /**
     * Queues removal of every tracked block in a world that is unloading. ActiveBlock holds a strong
     * World reference, and the per-behaviour cleanupWorld methods drop their own maps directly rather
     * than routing through markInactive, so without this the entries stay forever: the World is retained
     * and the tick loop's idle fast path can never fire again because the set is never empty.
     */
    public void cleanupWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }
        effectManager.cleanupWorld(worldId);
        for (ActiveBlock block : activeBlocks) {
            if (worldId.equals(block.worldId())) {
                pendingChanges.add(new PendingChange(block, false));
            }
        }
    }

    /** Drops the effect budget/viewer context of one unloading chunk; the map is otherwise never cleared. */
    public void cleanupEffectChunk(World world, int blockX, int blockZ) {
        if (world == null) {
            return;
        }
        effectManager.cleanupChunk(world.getUID(), ManagerSupport.chunkKey(blockX >> 4, blockZ >> 4));
    }

    private void tick() {
        if (!running) return;
        // Idle fast path: nothing active, nothing queued, stats off — skip the pass entirely.
        // CLQ.isEmpty is a single head-node probe.
        if (activeBlockSnapshot.isEmpty() && pendingChanges.isEmpty() && !performanceMonitor.isRecording()) {
            return;
        }
        PerformanceMonitor.Session profile = performanceMonitor.recording();
        long startedNanos = profile == null ? 0L : System.nanoTime();
        int size = 0;
        int processed = 0;
        try {
            long currentTick = advanceCurrentTick();
            boolean folia = plugin.scheduler().isFolia();

            boolean changed = false;
            PendingChange change;
            while ((change = pendingChanges.poll()) != null) {
                if (change.add()) {
                    if (activeBlocks.add(change.block())) {
                        changed = true;
                        if (change.block().type() == BlockType.COOKING_POT) {
                            activeCookingPotCount++;
                        }
                    }
                    lastProcessedTicks.putIfAbsent(change.block(), currentTick);
                } else {
                    if (activeBlocks.remove(change.block())) {
                        changed = true;
                        if (change.block().type() == BlockType.COOKING_POT) {
                            activeCookingPotCount--;
                        }
                    }
                    lastProcessedTicks.remove(change.block());
                    progressDisplayLastUpdateTicks.remove(change.block());
                    heatSourceLastCheckTicks.remove(change.block());
                    scheduledActiveBlocks.remove(change.block());
                }
            }
            if (changed) {
                activeBlockSnapshot = List.copyOf(activeBlocks);
                if (activeCookingPotCount < 0) {
                    // Never representative of a real state; shield the sneak-count invariant.
                    activeCookingPotCount = 0;
                }
            }

            List<ActiveBlock> snapshot = activeBlockSnapshot;
            size = snapshot.size();
            if (snapshot.isEmpty()) return;

            if (size > activeBlockWarningThreshold) {
                if (!activeBlockLimitWarningShown) {
                    activeBlockLimitWarningShown = true;
                    plugin.getLogger().warning(I18n.formatNamedArgs("console.performance.active_blocks_exceeded",
                            "threshold", activeBlockWarningThreshold,
                            "count", size));
                }
            } else {
                activeBlockLimitWarningShown = false;
            }

            int budget = Math.min(cookingPotTickBudget, size);
            int start = activeBlockCursor >= size ? 0 : activeBlockCursor;

            for (int index = start; index < size; index++) {
                ActiveBlock activeBlock = snapshot.get(index);
                processActiveBlock(activeBlock, currentTick, folia);
                processed++;
                if (processed >= budget) {
                    break;
                }
            }

            if (processed < budget) {
                for (int index = 0; index < start; index++) {
                    ActiveBlock activeBlock = snapshot.get(index);
                    processActiveBlock(activeBlock, currentTick, folia);
                    processed++;
                    if (processed >= budget) {
                        break;
                    }
                }
            }

            activeBlockCursor = size == 0 ? 0 : (start + Math.max(1, processed)) % size;
        } finally {
            if (profile != null) {
                profile.recordPass(System.nanoTime() - startedNanos, size, processed);
            }
        }
    }

    public long startPerformanceProfile(PerformanceMonitor.Feature feature) {
        return performanceMonitor.start(feature);
    }

    public PerformanceSnapshot finishPerformanceProfile(long id) {
        PerformanceMonitor.Snapshot snapshot = performanceMonitor.finish(id);
        return snapshot == null ? null : getPerformanceSnapshot(snapshot);
    }

    PerformanceMonitor.Timing featureTiming(PerformanceMonitor.Feature feature) {
        return performanceMonitor.timing(feature);
    }

    public PerformanceSnapshot getPerformanceSnapshot() {
        return getPerformanceSnapshot(performanceMonitor.snapshot());
    }

    private PerformanceSnapshot getPerformanceSnapshot(PerformanceMonitor.Snapshot profile) {
        // Weakly-consistent walk of the mark queue (diagnostics only); duplicates count as queued ops.
        int pendingAdditionsSize = 0;
        int pendingRemovalsSize = 0;
        for (PendingChange change : pendingChanges) {
            if (change.add()) {
                pendingAdditionsSize++;
            } else {
                pendingRemovalsSize++;
            }
        }
        return new PerformanceSnapshot(
                profile.pass().calls(),
                profile.pass().totalNanos(),
                profile.pass().lastNanos(),
                profile.pass().maxNanos(),
                profile.lastActiveBlocks(),
                profile.lastProcessedBlocks(),
                activeBlocks.size(),
                activeBlockSnapshot.size(),
                pendingAdditionsSize,
                pendingRemovalsSize,
                cookingPotTickBudget,
                TICK_INTERVAL,
                profile.active(),
                profile.pass().historyNanos(),
                profile.blockNanos(),
                profile.features(),
                profile.elapsedNanos(),
                profile.omittedHotspotCalls()
        );
    }

    private void processActiveBlock(ActiveBlock activeBlock, long currentTick, boolean folia) {
        World world = activeBlock.world();
        if (world == null) return;

        if (folia) {
            if (!scheduledActiveBlocks.add(activeBlock)) {
                return;
            }
            int chunkX = activeBlock.posKey().x() >> 4;
            int chunkZ = activeBlock.posKey().z() >> 4;
            try {
                plugin.scheduler().runAt(world, chunkX, chunkZ, () -> {
                    try {
                        processActiveBlockInRegion(activeBlock, world, getCurrentTick(), true);
                    } finally {
                        scheduledActiveBlocks.remove(activeBlock);
                    }
            });
        } catch (RuntimeException e) {
            scheduledActiveBlocks.remove(activeBlock);
        }
        return;
        }

        processActiveBlockInRegion(activeBlock, world, currentTick, false);
    }

    private void processActiveBlockInRegion(ActiveBlock activeBlock, World world,
                                            long currentTick, boolean verifyMembership) {
        if (!running || (verifyMembership && !activeBlocks.contains(activeBlock))) {
            return;
        }

        BlockPosKey posKey = activeBlock.posKey();
        if (!world.isChunkLoaded(posKey.x() >> 4, posKey.z() >> 4)) {
            return;
        }

        try {
            if (Objects.requireNonNull(activeBlock.type()) == BlockType.COOKING_POT) {
                PerformanceMonitor.Session profile = performanceMonitor.recording();
                PerformanceMonitor.Timing timing = profile == null ? null
                        : profile.timings.get(PerformanceMonitor.Feature.COOKING_POT);
                long started = timing == null ? 0L : System.nanoTime();
                try {
                    tickCookingPot(activeBlock, world, posKey, consumeElapsedTicks(activeBlock, currentTick), currentTick);
                } finally {
                    if (timing != null) {
                        long cost = System.nanoTime() - started;
                        timing.record(cost);
                        profile.recordBlockCost(activeBlock.worldId(), posKey, cost);
                    }
                }
            } else {
                throw new IllegalArgumentException("Unexpected value: " + activeBlock.type());
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatNamedArgs("console.tick.error_ticking",
                    "type", activeBlock.type(),
                    "pos", posKey,
                    "error", e.getMessage()));
        }
    }

    private int consumeElapsedTicks(ActiveBlock activeBlock, long currentTick) {
        Long previousTick = lastProcessedTicks.put(activeBlock, currentTick);
        if (previousTick == null) {
            return TICK_INTERVAL;
        }
        long elapsed = currentTick - previousTick;
        if (elapsed <= 0L) {
            return TICK_INTERVAL;
        }
        return (int) Math.min(MAX_ELAPSED_TICKS, elapsed);
    }

    private long getCurrentTick() {
        if (plugin.scheduler().isFolia()) {
            return foliaTickClock.get();
        }
        return Bukkit.getCurrentTick();
    }

    private long advanceCurrentTick() {
        if (plugin.scheduler().isFolia()) {
            return foliaTickClock.addAndGet(TICK_INTERVAL);
        }
        return Bukkit.getCurrentTick();
    }

    private void tickCookingPot(ActiveBlock activeBlock, World world, BlockPosKey posKey,
                                int elapsedTicks, long currentTick) {
        Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);

        if (state == null || state.isEmpty()) {
            // A /ce reload unbinds custom states for its parse window, making them unresolvable while the
            // injected server block is still in the world. Skip the tick and keep everything registered:
            // deleting here would destroy a live pot's contents mid-reload.
            if (CraftEngineBlocks.isCustomBlock(block)) {
                return;
            }
            unregisterCookingPotBlock(activeBlock, world, posKey);
            // Keep the CE-side stored NBT: only the break/removal callbacks delete data. A block replaced
            // behind CE's back (WorldEdit /setblock) just leaves inert leftover NBT behind.
            CookingPotBlockBehavior.removeBlockEntity(world, posKey, false);
            return;
        }

        // Resolve the behavior once from the already-fetched state; a null result also answers "no longer a
        // cooking pot", so this replaces a second getBlockAt + CE custom-state fetch per pot per tick.
        CookingPotBlockBehavior behavior = CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
        if (behavior == null) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            // The block is no longer a cooking pot (replaced by a different block that bypassed the CE break
            // callbacks); retire its floating progress display + recipe-name cache instead of leaving them to
            // linger until the periodic sweep.
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return;
        }
        
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(world, posKey);
        if (entity == null) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return;
        }

        // Advance buffer -> output during the pot tick instead of relying on GUI refresh or manual pickup.
        entity.tryMovePendingToOutput();

        // An empty pot can still be mid-cook: the player (or a hopper) can strip every ingredient while the
        // progress is running, and the mod regresses its cookTime in that state. Keep the pot registered until
        // the progress has decayed to 0, otherwise the bar freezes at its last value forever.
        if (!entity.hasStoredContents() && entity.getCookingProgress() <= 0) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return;
        }

        boolean hasHeat;

        Long lastHeatCheck = heatSourceLastCheckTicks.get(activeBlock);
        long currentTickForHeat = currentTick;
        if (lastHeatCheck != null && currentTickForHeat - lastHeatCheck < HEAT_SOURCE_CHECK_INTERVAL_TICKS) {
            hasHeat = entity.hasHeatSource();
        } else {
            // Resolved only on a cache miss: world.getBlockAt builds a Location per call, and at the default
            // 10-tick interval against a 4-tick pass the cached branch answers two passes out of three.
            Block blockBelow = world.getBlockAt(posKey.x(), posKey.y() - 1, posKey.z());
            // Pre-fetch the CE state of blockBelow once, then share it across isHeatSource
            // and isConductor to avoid two independent CraftEngineBlocks.getCustomBlockState()
            // calls on the same block.
            ImmutableBlockState belowState = CraftEngineBlocks.getCustomBlockState(blockBelow);
            hasHeat = plugin.getHeatSourceConfig().isHeatSource(blockBelow, belowState);
            if (!hasHeat && plugin.getHeatSourceConfig().isConductor(blockBelow, belowState)) {
                Block blockTwoBelow = world.getBlockAt(posKey.x(), posKey.y() - 2, posKey.z());
                hasHeat = plugin.getHeatSourceConfig().isHeatSource(blockTwoBelow);
            }
            heatSourceLastCheckTicks.put(activeBlock, currentTickForHeat);
        }

        entity.setHasHeatSource(hasHeat);
        effectManager.emit(world, posKey, entity, hasHeat, behavior, currentTickForHeat);

        boolean canCook = hasHeat && entity.canCook();
        CookingPotRecipe recipe = canCook ? entity.getCurrentRecipe() : null;
        if (plugin.isDebugEnabled("cooking_pot")) {
            plugin.getLogger().info(I18n.formatNamedArgs("console.debug.cooking_pot_tick",
                    "pos", posKey,
                    "has_heat", hasHeat,
                    "can_cook", canCook,
                    "recipe", recipe != null ? recipe.getId() : "null",
                    "progress", entity.getCookingProgress(),
                    "duration", entity.getCookingDuration(),
                    "inputs", entity.debugInputSummary()));
        }

        if (recipe != null) {
            // Restarts the bar when the matched recipe is a different dish than the one the progress was
            // accumulated for (see beginCookingRecipe: the id survives the passes with no matching recipe).
            entity.beginCookingRecipe(recipe.getId());
            entity.setCookingDuration(recipe.getCookTime());

            int newProgress = CookingPotBlockEntity.advanceOrDecayProgress(
                    entity.getCookingProgress(), entity.getCookingDuration(), elapsedTicks, true);
            entity.setCookingProgress(newProgress);
            if (newProgress >= entity.getCookingDuration()) {
                Location blockLoc = ManagerSupport.toLocation(world, posKey);
                if (blockLoc == null) {
                    unregisterCookingPotBlock(activeBlock, world, posKey);
                    return;
                }
                if (entity.finishCooking(world, blockLoc)) {
                    // Keep the pot active after a successful cook so remaining ingredients can immediately
                    // start the next batch.
                    recipe = entity.getCurrentRecipe();
                }
            }
        } else if (entity.getCookingProgress() > 0) {
            entity.setCookingProgress(CookingPotBlockEntity.advanceOrDecayProgress(
                    entity.getCookingProgress(), entity.getCookingDuration(), elapsedTicks, false));
        }

        if (entity.getCookingProgress() > 0 && recipe != null) {
            if (shouldSuppressCookingPotProgressDisplay()) {
                progressDisplayLastUpdateTicks.remove(activeBlock);
                CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            } else if (shouldUpdateCookingPotProgressDisplay(activeBlock, currentTick)) {
                // Populate the recipe-name cache the progress display reads, but only when the
                // show-recipe-name option is enabled so it stays empty (and allocation-free) otherwise.
                if (plugin.isShowRecipeNameInProgressDisplay()) {
                    CookingPotBlockBehavior.setCookingRecipeItem(world, posKey, recipe.getResult());
                }
                CookingPotBlockBehavior.updateProgressDisplay(world, posKey, entity.getProgressPercent());
            }
        } else {
            progressDisplayLastUpdateTicks.remove(activeBlock);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
        }
    }

    private void unregisterCookingPotBlock(ActiveBlock activeBlock, World world, BlockPosKey posKey) {
        progressDisplayLastUpdateTicks.remove(activeBlock);
        heatSourceLastCheckTicks.remove(activeBlock);
        markInactive(world, posKey, BlockType.COOKING_POT);
    }

    private boolean shouldSuppressCookingPotProgressDisplay() {
        return cookingPotProgressDisplayDisableAboveActivePots > 0
                && activeCookingPotCount > cookingPotProgressDisplayDisableAboveActivePots;
    }

    private boolean shouldUpdateCookingPotProgressDisplay(ActiveBlock activeBlock, long currentTick) {
        if (cookingPotProgressDisplayUpdateIntervalTicks <= TICK_INTERVAL) {
            return true;
        }

        Long lastUpdateTick = progressDisplayLastUpdateTicks.get(activeBlock);
        if (lastUpdateTick != null
                && currentTick - lastUpdateTick < cookingPotProgressDisplayUpdateIntervalTicks) {
            return false;
        }
        progressDisplayLastUpdateTicks.put(activeBlock, currentTick);
        return true;
    }

    public record PerformanceSnapshot(
            long samples,
            long totalNanos,
            long lastNanos,
            long maxNanos,
            long lastActiveBlocks,
            long lastProcessedBlocks,
            int currentActiveBlocks,
            int snapshotActiveBlocks,
            int pendingAdditions,
            int pendingRemovals,
            int tickBudget,
            int tickInterval,
            boolean statsEnabled,
            long[] historyNanos,
            Map<PerformanceMonitor.Hotspot, Long> blockNanos,
            Map<PerformanceMonitor.Feature, PerformanceMonitor.TimingSnapshot> features,
            long elapsedNanos,
            long omittedHotspotCalls
    ) {
        public double averageNanos() {
            return samples <= 0L ? 0.0D : (double) totalNanos / samples;
        }
    }

    public enum BlockType {
        COOKING_POT
    }
    
    private record ActiveBlock(UUID worldId, World world, BlockPosKey posKey, BlockType type) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ActiveBlock that = (ActiveBlock) o;
            return worldId.equals(that.worldId) && posKey.equals(that.posKey) && type == that.type;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * worldId.hashCode() + posKey.hashCode()) + type.hashCode();
        }
    }
}
