package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class TickManager {

    private final FarmersDelightPlugin plugin;
    private PluginTask tickTask;
    private PluginTask cleanupTask;
    private volatile boolean running = false;
    private EffectSpec bubbleEffect = new EffectSpec(true, Particle.BUBBLE_POP, 0.20f, 1,
            0.02D, 0.0D, 0.0D, 0.0D, 0.01D);
    private EffectSpec steamEffect = new EffectSpec(true, Particle.CLOUD, 0.05f, 1,
            0.08D, 0.0D, 0.03D, 0.0D, 0.02D);
    private EffectSpec secondarySteamEffect = new EffectSpec(false, Particle.SMOKE, 1.0f, 1,
            0.05D, 0.0D, 0.025D, 0.0D, 0.02D);
    
    private final Set<ActiveBlock> activeBlocks = ConcurrentHashMap.newKeySet();
    // Lock-free mark queue: producers are event-driven (GUI clicks, block interactions, chunk loads)
    // and the single consumer is tick(). FIFO drain reproduces the old add/remove set cancellation —
    // the last operation for a block within a drain window wins.
    private final ConcurrentLinkedQueue<PendingChange> pendingChanges = new ConcurrentLinkedQueue<>();

    private record PendingChange(ActiveBlock block, boolean add) {
    }
    private final Map<ActiveBlock, Long> lastProcessedTicks = new ConcurrentHashMap<>();
    private final Map<ActiveBlock, Long> progressDisplayLastUpdateTicks = new ConcurrentHashMap<>();
    // Heat source checking is expensive (930ms total per profiler — two isHeatSource + isConductor calls
    // per pot per tick). Block-below heat sources rarely change; skip the per-tick re-check.
    private final Map<ActiveBlock, Long> heatSourceLastCheckTicks = new ConcurrentHashMap<>();
    private static final int HEAT_SOURCE_CHECK_INTERVAL_TICKS = 10;
    private final Set<ActiveBlock> scheduledActiveBlocks = ConcurrentHashMap.newKeySet();
    private volatile List<ActiveBlock> activeBlockSnapshot = List.of();
    private volatile int activeCookingPotCount;
    private boolean activeBlockLimitWarningShown;
    private int activeBlockCursor;
    private int cookingPotTickBudget = 512;
    private int cookingPotProgressDisplayUpdateIntervalTicks = 8;
    private int cookingPotProgressDisplayDisableAboveActivePots = 512;
    private int activeBlockWarningThreshold = 1000;
    private boolean performanceWarningsEnabled = true;
    private int cookingPotDensityWarningThreshold = 64;
    private int cookingPotTotalWarningThreshold = 1000;
    private long performanceWarningCooldownMillis = 600_000L;
    private final Map<String, Long> performanceWarningTimes = new ConcurrentHashMap<>();
    private final AtomicLong foliaTickClock = new AtomicLong();
    private final AtomicLong performanceSamples = new AtomicLong();
    private final AtomicLong performanceTotalNanos = new AtomicLong();
    private final AtomicLong performanceLastNanos = new AtomicLong();
    private final AtomicLong performanceMaxNanos = new AtomicLong();
    private final AtomicLong performanceLastActiveBlocks = new AtomicLong();
    private final AtomicLong performanceLastProcessedBlocks = new AtomicLong();
    private volatile boolean performanceStatsEnabled;

    private static final int TICK_INTERVAL = 4;
    // Squared player-proximity radius for gating cooking-pot particle/sound broadcasts. Default 32 blocks =
    // vanilla particle/sound range upper bound; read from cooking-pot.effects.viewer-distance on reload.
    private static final double DEFAULT_EFFECT_VIEWER_DISTANCE = 32.0D;
    // Written in reloadConfig (reload thread), read on Folia region tick threads — volatile for a
    // happens-before edge, matching the other reload-mutated tick-read fields.
    private volatile double effectViewerDistanceSquared = DEFAULT_EFFECT_VIEWER_DISTANCE * DEFAULT_EFFECT_VIEWER_DISTANCE;
    // Cooking-pot particle/sound emission is throttled to 1-in-N TickManager passes (each pass = TICK_INTERVAL
    // ticks, so 1 = every pass ~= 5/s). Raise to 2-3 to cut particle + getPlayersSeeingChunk cost on dense pot
    // farms; cooking PROGRESS is unaffected (it runs earlier in tickCookingPot). Read from
    // cooking-pot.effects.interval on reload.
    private volatile int cookingPotEffectInterval = 1;
    // Reusable per-thread recipient list for targeted particle/sound sends (per-thread for Folia's
    // concurrent per-region cooking-pot ticks; refilled per pot and consumed synchronously).
    private static final ThreadLocal<List<Player>> NEARBY_VIEWER_SCRATCH = ThreadLocal.withInitial(ArrayList::new);
    // R-PERF-007 (b): per-chunk hard cap on cooking-pot particle+sound packets emitted per dispatch,
    // mirroring StoveManager/SkilletManager. Cooking pot is the densest heat block, so a packed pocket
    // must not steamroll the packet queue in one Bukkit tick. Reset once per bukkit tick across the pass.
    // The context also caches the chunk's tracked-player list so pots sharing a chunk pay one
    // getPlayersSeeingChunk lookup that tick (mirrors StoveManager.chunkFx). World-keyed so identical
    // chunk coordinates in different worlds never collide.
    private volatile int cookingPotChunkEffectBudgetLimit = 50;
    private final Map<UUID, Map<Long, CookingPotFxContext>> chunkFx = new ConcurrentHashMap<>();
    private volatile long effectBudgetResetTick = -1L;

    private static final class CookingPotFxContext {
        final AtomicInteger budget = new AtomicInteger();
        volatile List<Player> seeing;
    }
    private static final int DEFAULT_ACTIVE_BLOCK_WARNING_THRESHOLD = 1000;
    private static final int CLEANUP_INTERVAL = 6000;
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
        reloadConfig();
    }

    public void reloadConfig() {
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
        cookingPotTickBudget = Math.max(1, plugin.getConfigInt(DEFAULT_COOKING_POT_TICK_BUDGET,
                "cooking-pot.tick-budget",
                "performance.cooking-pot-tick-budget"));
        double viewerDistance = Math.max(0.0D, plugin.getConfigDouble(DEFAULT_EFFECT_VIEWER_DISTANCE,
                "cooking-pot.effects.viewer-distance"));
        effectViewerDistanceSquared = viewerDistance * viewerDistance;
        cookingPotEffectInterval = Math.max(1, plugin.getConfigInt(1, "cooking-pot.effects.interval"));
        cookingPotChunkEffectBudgetLimit = Math.max(1, plugin.getConfigInt(50,
                "performance.chunk-effect-packet-budget"));
        cookingPotProgressDisplayUpdateIntervalTicks = Math.max(1,
                plugin.getCookingPotProgressDisplayUpdateIntervalTicks());
        cookingPotProgressDisplayDisableAboveActivePots = Math.max(0,
                plugin.getCookingPotProgressDisplayDisableAboveActivePots());
        activeBlockWarningThreshold = Math.max(1, plugin.getConfigInt(DEFAULT_ACTIVE_BLOCK_WARNING_THRESHOLD,
                "performance.active-block-warning-threshold",
                "performance.max-active-blocks-warning"));
        performanceWarningsEnabled = plugin.getConfigBoolean(true,
                "performance.warnings-enabled");
        cookingPotDensityWarningThreshold = Math.max(1, plugin.getConfigInt(64,
                "performance.cooking-pot-density-warning-threshold"));
        cookingPotTotalWarningThreshold = Math.max(1, plugin.getConfigInt(1000,
                "performance.cooking-pot-total-warning-threshold"));
        int cooldownSeconds = Math.max(1, plugin.getConfigInt(600,
                "performance.warning-cooldown-seconds"));
        performanceWarningCooldownMillis = cooldownSeconds * 1000L;
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

        I18n.logDetail("startup", "tick.stopped");
    }
    
    private void performCleanup() {
        checkPerformanceWarnings();
        pruneOldPerformanceWarnings();
        if (plugin.scheduler().isFolia()) {
            scheduleCookingPotCleanup();
            return;
        }

        int cleanedCount = 0;
        
        cleanedCount += cleanupInvalidBlockEntities(
            CookingPotBlockBehavior::getAllBlockEntities,
            CookingPotBlockBehavior::removeBlockEntity
        );
        
        if (cleanedCount > 0) {
            I18n.logInfo("tick.cleanup_completed", "count", cleanedCount);
        }
    }

    private void checkPerformanceWarnings() {
        if (!performanceWarningsEnabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            Map<BlockPosKey, CookingPotBlockEntity> entities = CookingPotBlockBehavior.getAllBlockEntities(world);
            int total = entities.size();
            if (total >= cookingPotTotalWarningThreshold) {
                warnWithCooldown("cooking-pot-total:" + world.getUID(),
                        I18n.formatNamedArgs("console.performance.cooking_pot_total",
                                "world", world.getName(),
                                "count", total,
                                "threshold", cookingPotTotalWarningThreshold));
            }
            if (total < cookingPotDensityWarningThreshold) {
                continue;
            }
            Map<Long, Integer> chunkCounts = new HashMap<>();
            for (BlockPosKey posKey : entities.keySet()) {
                int chunkX = posKey.x() >> 4;
                int chunkZ = posKey.z() >> 4;
                chunkCounts.merge(packChunkKey(chunkX, chunkZ), 1, Integer::sum);
            }
            for (Map.Entry<Long, Integer> entry : chunkCounts.entrySet()) {
                int count = entry.getValue();
                if (count < cookingPotDensityWarningThreshold) {
                    continue;
                }
                int chunkX = unpackChunkX(entry.getKey());
                int chunkZ = unpackChunkZ(entry.getKey());
                warnWithCooldown("cooking-pot-density:" + world.getUID() + ":" + chunkX + ":" + chunkZ,
                        I18n.formatNamedArgs("console.performance.cooking_pot_density",
                                "world", world.getName(),
                                "chunk_x", chunkX,
                                "chunk_z", chunkZ,
                                "count", count,
                                "threshold", cookingPotDensityWarningThreshold));
            }
        }
    }

    private void pruneOldPerformanceWarnings() {
        // The warning-times map is keyed by world/chunk and would grow unbounded otherwise; remove
        // entries past the cooldown (a later warning for the same key re-adds it).
        long cutoff = System.currentTimeMillis() - performanceWarningCooldownMillis;
        performanceWarningTimes.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private void warnWithCooldown(String key, String message) {
        long now = System.currentTimeMillis();
        Long previous = performanceWarningTimes.get(key);
        if (previous != null && now - previous < performanceWarningCooldownMillis) {
            return;
        }
        performanceWarningTimes.put(key, now);
        plugin.getLogger().warning(message);
    }

    private long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private int unpackChunkX(long key) {
        return (int) (key >> 32);
    }

    private int unpackChunkZ(long key) {
        return (int) key;
    }

    private void scheduleCookingPotCleanup() {
        for (Location location : CookingPotBlockBehavior.getBlockEntityLocations()) {
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
    }
    
    private interface BlockEntityGetter<T> {
        Map<BlockPosKey, T> getAll(World world);
    }
    
    private interface BlockEntityRemover {
        void remove(World world, BlockPosKey posKey);
    }
    
    private <T> int cleanupInvalidBlockEntities(BlockEntityGetter<T> getter, BlockEntityRemover remover) {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            Map<BlockPosKey, T> entities = getter.getAll(world);
            for (BlockPosKey posKey : entities.keySet()) {
                if (cleanupInvalidBlockEntity(world, posKey, remover)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void cleanupCookingPotBlockEntity(World world, BlockPosKey posKey) {
        cleanupInvalidBlockEntity(
                world,
                posKey,
                CookingPotBlockBehavior::removeBlockEntity
        );
    }

    private boolean cleanupInvalidBlockEntity(World world, BlockPosKey posKey, BlockEntityRemover remover) {
        if (world == null || posKey == null) {
            return false;
        }

        if (!CookingPotBlockBehavior.hasCookingPotBehavior(world, posKey)) {
            remover.remove(world, posKey);
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

    private void tick() {
        if (!running) return;
        // Idle fast path: nothing active, nothing queued, stats off — skip the pass entirely.
        // CLQ.isEmpty is a single head-node probe.
        if (activeBlockSnapshot.isEmpty() && pendingChanges.isEmpty() && !performanceStatsEnabled) {
            return;
        }
        boolean stats = performanceStatsEnabled;
        long startedNanos = stats ? System.nanoTime() : 0L;
        int size = 0;
        int processed = 0;
        try {
            long currentTick = advanceCurrentTick();

            boolean changed = false;
            PendingChange change;
            while ((change = pendingChanges.poll()) != null) {
                if (change.add()) {
                    changed |= activeBlocks.add(change.block());
                    lastProcessedTicks.putIfAbsent(change.block(), currentTick);
                } else {
                    changed |= activeBlocks.remove(change.block());
                    lastProcessedTicks.remove(change.block());
                    progressDisplayLastUpdateTicks.remove(change.block());
                    heatSourceLastCheckTicks.remove(change.block());
                    scheduledActiveBlocks.remove(change.block());
                }
            }
            if (changed) {
                activeBlockSnapshot = List.copyOf(activeBlocks);
                activeCookingPotCount = countActiveBlocks(activeBlockSnapshot);
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
                processActiveBlock(activeBlock);
                processed++;
                if (processed >= budget) {
                    break;
                }
            }

            if (processed < budget) {
                for (int index = 0; index < start; index++) {
                    ActiveBlock activeBlock = snapshot.get(index);
                    processActiveBlock(activeBlock);
                    processed++;
                    if (processed >= budget) {
                        break;
                    }
                }
            }

            activeBlockCursor = size == 0 ? 0 : (start + Math.max(1, processed)) % size;
        } finally {
            if (stats) {
                recordPerformanceSample(System.nanoTime() - startedNanos, size, processed);
            }
        }
    }

    private void recordPerformanceSample(long durationNanos, int activeCount, int processedCount) {
        if (!performanceStatsEnabled) {
            return;
        }
        performanceSamples.incrementAndGet();
        performanceTotalNanos.addAndGet(Math.max(0L, durationNanos));
        performanceLastNanos.set(Math.max(0L, durationNanos));
        performanceLastActiveBlocks.set(Math.max(0, activeCount));
        performanceLastProcessedBlocks.set(Math.max(0, processedCount));
        updateMax(performanceMaxNanos, durationNanos);
    }

    private void updateMax(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value <= current) {
                return;
            }
        } while (!target.compareAndSet(current, value));
    }

    private int countActiveBlocks(List<ActiveBlock> blocks) {
        int count = 0;
        for (ActiveBlock block : blocks) {
            if (block.type() == BlockType.COOKING_POT) {
                count++;
            }
        }
        return count;
    }

    public void resetPerformanceStats() {
        performanceSamples.set(0L);
        performanceTotalNanos.set(0L);
        performanceLastNanos.set(0L);
        performanceMaxNanos.set(0L);
        performanceLastActiveBlocks.set(activeBlockSnapshot.size());
        performanceLastProcessedBlocks.set(0L);
        performanceStatsEnabled = true;
    }

    public void setPerformanceStatsEnabled(boolean enabled) {
        performanceStatsEnabled = enabled;
    }

    public PerformanceSnapshot getPerformanceSnapshot() {
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
                performanceSamples.get(),
                performanceTotalNanos.get(),
                performanceLastNanos.get(),
                performanceMaxNanos.get(),
                performanceLastActiveBlocks.get(),
                performanceLastProcessedBlocks.get(),
                activeBlocks.size(),
                activeBlockSnapshot.size(),
                pendingAdditionsSize,
                pendingRemovalsSize,
                cookingPotTickBudget,
                TICK_INTERVAL,
                performanceStatsEnabled
        );
    }

    private void processActiveBlock(ActiveBlock activeBlock) {
        World world = activeBlock.world();
        if (world == null) return;

        if (plugin.scheduler().isFolia()) {
            if (!scheduledActiveBlocks.add(activeBlock)) {
                return;
            }
            int chunkX = activeBlock.posKey().x() >> 4;
            int chunkZ = activeBlock.posKey().z() >> 4;
            try {
                plugin.scheduler().runAt(world, chunkX, chunkZ, () -> {
                    try {
                        processActiveBlockInRegion(activeBlock, world);
                    } finally {
                        scheduledActiveBlocks.remove(activeBlock);
                    }
            });
        } catch (RuntimeException e) {
            scheduledActiveBlocks.remove(activeBlock);
        }
        return;
        }

        processActiveBlockInRegion(activeBlock, world);
    }

    private void processActiveBlockInRegion(ActiveBlock activeBlock, World world) {
        if (!running || !activeBlocks.contains(activeBlock)) {
            return;
        }

        BlockPosKey posKey = activeBlock.posKey();
        if (!world.isChunkLoaded(posKey.x() >> 4, posKey.z() >> 4)) {
            return;
        }

        try {
            if (Objects.requireNonNull(activeBlock.type()) == BlockType.COOKING_POT) {
                tickCookingPot(activeBlock, world, posKey, consumeElapsedTicks(activeBlock));
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

    private int consumeElapsedTicks(ActiveBlock activeBlock) {
        long currentTick = getCurrentTick();
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

    private void tickCookingPot(ActiveBlock activeBlock, World world, BlockPosKey posKey, int elapsedTicks) {
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

        // Keep advancing buffer -> output during the pot tick to match the old plugin's behavior,
        // rather than relying only on GUI refresh or manual output pickup.
        entity.tryMovePendingToOutput();

        if (!entity.hasStoredContents()) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return;
        }

        Block blockBelow = world.getBlockAt(posKey.x(), posKey.y() - 1, posKey.z());
        boolean hasHeat;

        Long lastHeatCheck = heatSourceLastCheckTicks.get(activeBlock);
        long currentTickForHeat = getCurrentTick();
        if (lastHeatCheck != null && currentTickForHeat - lastHeatCheck < HEAT_SOURCE_CHECK_INTERVAL_TICKS) {
            hasHeat = entity.hasHeatSource();
        } else {
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
        emitCookingPotEffects(world, posKey, entity, hasHeat, behavior);

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
            entity.setCookingDuration(recipe.getCookTime());

            int newProgress = Math.min(entity.getCookingDuration(), entity.getCookingProgress() + elapsedTicks);
            entity.setCookingProgress(newProgress);
            if (newProgress >= entity.getCookingDuration()) {
                Location blockLoc = ManagerSupport.toLocation(world, posKey);
                if (blockLoc == null) {
                    unregisterCookingPotBlock(activeBlock, world, posKey);
                    return;
                }
                if (entity.finishCooking(world, blockLoc)) {
                    // Keep the pot active after a successful cook so remaining
                    // ingredients can immediately start the next batch, to match
                    // the old plugin's behavior.
                    recipe = entity.getCurrentRecipe();
                }
            }
        } else if (entity.getCookingProgress() > 0) {
            entity.setCookingProgress(Math.max(0, entity.getCookingProgress() - (elapsedTicks * 2)));
        }

        if (entity.getCookingProgress() > 0 && recipe != null) {
            if (shouldSuppressCookingPotProgressDisplay()) {
                progressDisplayLastUpdateTicks.remove(activeBlock);
                CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            } else if (shouldUpdateCookingPotProgressDisplay(activeBlock)) {
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

    private boolean shouldUpdateCookingPotProgressDisplay(ActiveBlock activeBlock) {
        if (cookingPotProgressDisplayUpdateIntervalTicks <= TICK_INTERVAL) {
            return true;
        }

        long currentTick = getCurrentTick();
        Long lastUpdateTick = progressDisplayLastUpdateTicks.get(activeBlock);
        if (lastUpdateTick != null
                && currentTick - lastUpdateTick < cookingPotProgressDisplayUpdateIntervalTicks) {
            return false;
        }
        progressDisplayLastUpdateTicks.put(activeBlock, currentTick);
        return true;
    }

    private void emitCookingPotEffects(World world, BlockPosKey posKey, CookingPotBlockEntity entity, boolean hasHeat,
                                       CookingPotBlockBehavior behavior) {
        if (!hasHeat) {
            return;
        }

        boolean hasActivity = entity.hasInput()
                || entity.hasPendingOutput()
                || entity.hasMealDisplayItem()  // was getMealDisplayItem() != null — that path clones.
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

        // Cooking pot is the densest heat block (warn threshold 1000 / 64-per-chunk). Skip the
        // per-tick particle + sound broadcast entirely when no player is close enough to see/hear —
        // world.spawnParticle/playSound otherwise scan the full online-player list server-side even
        // for an unattended farm. Cooking progress runs earlier in tickCookingPot, so gating only the
        // effects here is correctness-safe (R-PERF-003). The collected list is both the gate and the
        // recipient set for the targeted sends below, so we avoid world.spawnParticle/playSound
        // re-walking the whole world player list per call (R-PERF-006).
        // R-PERF-007 (b): per-chunk per-dispatch packet budget + shared tracked-player lookup. No stagger
        // — a period-N dispatch never rotates a getCurrentTick()-based one (#022) — so only the hard cap
        // is used. Both the budget and the chunk's seeing-players list are cached per chunk and reset
        // once per bukkit tick, so a pocket of pots in one chunk pays getPlayersSeeingChunk exactly once.
        int chunkX = posKey.x() >> 4;
        int chunkZ = posKey.z() >> 4;
        long effectChunkKey = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
        long currentBukkitTick = getCurrentTick();
        // Throttle particle/sound emission to 1-in-N passes (cooking-pot.effects.interval). Skips the
        // getPlayersSeeingChunk lookup + particle/sound broadcast on the off-passes; cooking PROGRESS
        // already ran in tickCookingPot, so this is cosmetic-only (R-PERF-003). currentBukkitTick advances
        // by TICK_INTERVAL each pass on both Paper and Folia, so /TICK_INTERVAL yields the pass counter.
        if (cookingPotEffectInterval > 1 && (currentBukkitTick / TICK_INTERVAL) % cookingPotEffectInterval != 0) {
            return;
        }
        if (currentBukkitTick != effectBudgetResetTick) {
            chunkFx.clear();
            effectBudgetResetTick = currentBukkitTick;
        }
        CookingPotFxContext fx = chunkFx.computeIfAbsent(world.getUID(), w -> new ConcurrentHashMap<>())
                .computeIfAbsent(effectChunkKey, k -> new CookingPotFxContext());
        List<Player> seeing = fx.seeing;
        if (seeing == null) {
            seeing = world.isChunkLoaded(chunkX, chunkZ)
                    ? List.copyOf(world.getChunkAt(chunkX, chunkZ).getPlayersSeeingChunk())
                    : List.of();
            fx.seeing = seeing;
        }
        // Per-pot distance filter of the shared chunk list — both the "any player near?" gate and the
        // recipient set for the targeted sends below (R-PERF-006, mirrors StoveManager).
        List<Player> nearbyViewers = NEARBY_VIEWER_SCRATCH.get();
        nearbyViewers.clear();
        // Compute squared distance by hand to avoid allocating a Location per candidate. Same world is
        // already guaranteed below, so this is equivalent to distanceSquared.
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
            return;
        }
        AtomicInteger chunkBudget = fx.budget;
        if (chunkBudget.get() >= cookingPotChunkEffectBudgetLimit) {
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
            // One packet with count=N — vanilla randomizes per-particle within the (offsetX, offsetY,
            // offsetZ) box client-side, so we don't need the old per-iteration spawnParticle loop.
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

        float soundChance = 0.10f;
        if (behavior != null && behavior.getSoundChance() != null) {
            soundChance = behavior.getSoundChance().floatValue();
        }
        if (random.nextFloat() < soundChance && chunkBudget.get() < cookingPotChunkEffectBudgetLimit) {
            chunkBudget.incrementAndGet();
            boolean soupReady = entity.hasPendingOutput() || entity.hasMealDisplayItem();
            String configuredSound;
            if (soupReady) {
                String soupBoilSound = null;
                if (behavior != null) {
                    soupBoilSound = behavior.getSoupBoilSound();
                }
                configuredSound = firstNonBlank(soupBoilSound, Constants.SOUND_COOKING_POT_BOIL_SOUP);
            } else {
                String boilSound = null;
                if (behavior != null) {
                    boilSound = behavior.getBoilSound();
                }
                configuredSound = firstNonBlank(boilSound, Constants.SOUND_COOKING_POT_BOIL);
            }
            ResolvedSound boilSound = resolveSound(
                    configuredSound,
                    soupReady ? Sound.BLOCK_BREWING_STAND_BREW : Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP
            );
            float volume = 0.5f;
            if (behavior != null && behavior.getSoundVolume() != null) {
                volume = behavior.getSoundVolume().floatValue();
            }
            float pitchMin = 0.9f;
            if (behavior != null && behavior.getSoundPitchMin() != null) {
                pitchMin = behavior.getSoundPitchMin().floatValue();
            }
            float pitchMax = 1.1f;
            if (behavior != null && behavior.getSoundPitchMax() != null) {
                pitchMax = behavior.getSoundPitchMax().floatValue();
            }
            float pitch = pitchMin;
            if (pitchMin < pitchMax) {
                pitch = pitchMin + random.nextFloat() * (pitchMax - pitchMin);
            }
            playConfiguredSound(nearbyViewers, center, boilSound, volume, pitch);
        }
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
                section == null ? defaultEnabled : section.getBoolean("enabled", defaultEnabled),
                ManagerSupport.resolveParticle(section == null ? null : section.getString("type"), defaultParticle),
                section == null ? defaultChance : (float) section.getDouble("chance", defaultChance),
                Math.max(1, section == null ? 1 : section.getInt("count", 1)),
                section == null ? defaultYOffset : section.getDouble("y-offset", defaultYOffset),
                section == null ? 0.0 : section.getDouble("offset-x", 0.0),
                section == null ? defaultOffsetY : section.getDouble("offset-y", defaultOffsetY),
                section == null ? 0.0 : section.getDouble("offset-z", 0.0),
                Math.max(0.001D, section == null ? defaultSpeed : section.getDouble("speed", defaultSpeed))
        );
    }


    // Memo of sound resolution keyed on (configured, defaultSound). The vanilla sound registry is frozen
    // at bootstrap, so a given key always resolves the same way; this replaces a per-cooking-pot-per-tick
    // NamespacedKey.fromString + Registry.SOUNDS.get (a measurable hot cost in a many-pot scenario) with
    // one map lookup. Cleared on reload for pattern uniformity; the cap guards unbounded config strings.
    private static final Map<String, ResolvedSound> SOUND_RESOLUTION_CACHE = new ConcurrentHashMap<>();
    private static final int SOUND_RESOLUTION_CACHE_MAX = 512;

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
            registryKey = trimmed.toLowerCase(java.util.Locale.ROOT);
        } else {
            registryKey = "minecraft:" + trimmed.toLowerCase(java.util.Locale.ROOT).replace('_', '.');
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
            boolean statsEnabled
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

