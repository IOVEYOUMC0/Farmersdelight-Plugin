package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

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
    private final ReentrantLock pendingLock = new ReentrantLock();
    private final Set<ActiveBlock> pendingAdditions = new HashSet<>();
    private final Set<ActiveBlock> pendingRemovals = new HashSet<>();
    private final Map<ActiveBlock, Long> lastProcessedTicks = new ConcurrentHashMap<>();
    private final Map<ActiveBlock, Long> progressDisplayLastUpdateTicks = new ConcurrentHashMap<>();
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
    private static final int DEFAULT_ACTIVE_BLOCK_WARNING_THRESHOLD = 1000;
    private static final int CLEANUP_INTERVAL = 6000;
    private static final int DEFAULT_COOKING_POT_TICK_BUDGET = 512;
    private static final int MAX_ELAPSED_TICKS = 100;
    public TickManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        ConfigurationSection effectSection = plugin.getFirstConfigSection("cooking-pot.effects", "cooking-pot-effects");
        ConfigurationSection bubbleSection = effectSection != null ? effectSection.getConfigurationSection("bubble") : null;
        ConfigurationSection steamSection = effectSection != null ? effectSection.getConfigurationSection("steam") : null;
        ConfigurationSection secondarySection = steamSection != null ? steamSection.getConfigurationSection("secondary") : null;

        bubbleEffect = loadEffectSpec(bubbleSection, true, Particle.BUBBLE_POP, 0.20f, 1,
                0.02D, 0.0D, 0.0D, 0.0D, 0.01D);
        steamEffect = loadEffectSpec(steamSection, true, Particle.CLOUD, 0.05f, 1,
                0.08D, 0.0D, 0.03D, 0.0D, 0.02D);
        secondarySteamEffect = loadEffectSpec(secondarySection, false, Particle.SMOKE, 1.0f, 1,
                0.05D, 0.0D, 0.025D, 0.0D, 0.02D);
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
        I18n.logInfo("tick.started", "interval", TICK_INTERVAL);
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
        
        pendingLock.lock();
        try {
            activeBlocks.clear();
            activeBlockSnapshot = List.of();
            activeCookingPotCount = 0;
            pendingAdditions.clear();
            pendingRemovals.clear();
            lastProcessedTicks.clear();
            progressDisplayLastUpdateTicks.clear();
            scheduledActiveBlocks.clear();
        } finally {
            pendingLock.unlock();
        }
        
        I18n.logInfo("tick.stopped");
    }
    
    private void performCleanup() {
        checkPerformanceWarnings();
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

    private boolean cleanupCookingPotBlockEntity(World world, BlockPosKey posKey) {
        return cleanupInvalidBlockEntity(
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
    
    public void registerActiveBlock(World world, BlockPosKey posKey, BlockType type) {
        if (world == null || posKey == null || type == null) return;
        ActiveBlock block = new ActiveBlock(world.getUID(), world, posKey, type);
        pendingLock.lock();
        try {
            pendingAdditions.add(block);
            pendingRemovals.remove(block);
        } finally {
            pendingLock.unlock();
        }
    }
    
    public void unregisterActiveBlock(World world, BlockPosKey posKey, BlockType type) {
        if (world == null || posKey == null || type == null) return;
        ActiveBlock block = new ActiveBlock(world.getUID(), world, posKey, type);
        pendingLock.lock();
        try {
            pendingRemovals.add(block);
            pendingAdditions.remove(block);
        } finally {
            pendingLock.unlock();
        }
    }
    
    public void markActive(World world, BlockPosKey posKey, BlockType type) {
        registerActiveBlock(world, posKey, type);
    }

    private void tick() {
        if (!running) return;
        long startedNanos = System.nanoTime();
        int size = 0;
        int processed = 0;
        try {
            long currentTick = advanceCurrentTick();

            pendingLock.lock();
            boolean changed = false;
            try {
                if (!pendingAdditions.isEmpty()) {
                    activeBlocks.addAll(pendingAdditions);
                    changed = true;
                    for (ActiveBlock activeBlock : pendingAdditions) {
                        lastProcessedTicks.putIfAbsent(activeBlock, currentTick);
                    }
                    pendingAdditions.clear();
                }

                if (!pendingRemovals.isEmpty()) {
                    activeBlocks.removeAll(pendingRemovals);
                    changed = true;
                    for (ActiveBlock activeBlock : pendingRemovals) {
                        lastProcessedTicks.remove(activeBlock);
                        progressDisplayLastUpdateTicks.remove(activeBlock);
                        scheduledActiveBlocks.remove(activeBlock);
                    }
                    pendingRemovals.clear();
                }
                if (changed) {
                    activeBlockSnapshot = List.copyOf(activeBlocks);
                    activeCookingPotCount = countActiveBlocks(activeBlockSnapshot, BlockType.COOKING_POT);
                }
            } finally {
                pendingLock.unlock();
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
            recordPerformanceSample(System.nanoTime() - startedNanos, size, processed);
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

    private int countActiveBlocks(List<ActiveBlock> blocks, BlockType type) {
        int count = 0;
        for (ActiveBlock block : blocks) {
            if (block.type() == type) {
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
        int pendingAdditionsSize;
        int pendingRemovalsSize;
        pendingLock.lock();
        try {
            pendingAdditionsSize = pendingAdditions.size();
            pendingRemovalsSize = pendingRemovals.size();
        } finally {
            pendingLock.unlock();
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
            switch (activeBlock.type()) {
                case COOKING_POT -> tickCookingPot(activeBlock, world, posKey, consumeElapsedTicks(activeBlock));
                default -> throw new IllegalArgumentException("Unexpected value: " + activeBlock.type());
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
            unregisterCookingPotBlock(activeBlock, world, posKey);
            CookingPotBlockBehavior.removeBlockEntity(world, posKey);
            return;
        }
        
        if (!CookingPotBlockBehavior.hasCookingPotBehavior(world, posKey)) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            return;
        }
        
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(world, posKey);
        if (entity == null) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return;
        }

        // Keep buffer -> output progression in the pot tick, matching the old plugin behavior
        // instead of relying only on GUI refreshes or manual output pickup.
        entity.tryMovePendingToOutput();

        if (!entity.hasStoredContents()) {
            unregisterCookingPotBlock(activeBlock, world, posKey);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return;
        }

        Block blockBelow = world.getBlockAt(posKey.x(), posKey.y() - 1, posKey.z());
        boolean hasHeat = plugin.getHeatSourceConfig().isHeatSource(blockBelow);
        
        if (!hasHeat) {
            Block blockTwoBelow = world.getBlockAt(posKey.x(), posKey.y() - 2, posKey.z());
            if (plugin.getHeatSourceConfig().isConductor(blockBelow)) {
                hasHeat = plugin.getHeatSourceConfig().isHeatSource(blockTwoBelow);
            }
        }

        entity.setHasHeatSource(hasHeat);
        emitCookingPotEffects(world, posKey, entity, hasHeat);

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
                    // ingredients can immediately start the next batch, matching
                    // the old plugin behavior.
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
                CookingPotBlockBehavior.updateProgressDisplay(world, posKey, entity.getProgressPercent());
            }
        } else {
            progressDisplayLastUpdateTicks.remove(activeBlock);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
        }
    }

    private void unregisterCookingPotBlock(ActiveBlock activeBlock, World world, BlockPosKey posKey) {
        progressDisplayLastUpdateTicks.remove(activeBlock);
        unregisterActiveBlock(world, posKey, BlockType.COOKING_POT);
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

    private void emitCookingPotEffects(World world, BlockPosKey posKey, CookingPotBlockEntity entity, boolean hasHeat) {
        if (!hasHeat) {
            return;
        }

        boolean hasActivity = entity.hasInput()
                || entity.hasPendingOutput()
                || entity.getMealDisplayItem() != null
                || entity.getCookingProgress() > 0
                || entity.getCurrentRecipe() != null;
        if (!hasActivity) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        CookingPotBlockBehavior behavior = CookingPotBlockBehavior.getBlockBehavior(posKey.toLocation(world));
        Location center = ManagerSupport.toLocation(world, posKey);
        if (center == null) {
            return;
        }
        center.add(0.5, 0.9, 0.5);

        EffectSpec bubble = bubbleEffect;
        if (bubble.enabled() && random.nextFloat() < bubble.chance()) {
            double x = center.getX() + (random.nextDouble() * 0.6D - 0.3D);
            double y = center.getY() + bubble.yOffset();
            double z = center.getZ() + (random.nextDouble() * 0.6D - 0.3D);
            world.spawnParticle(
                    bubble.particle(),
                    x, y, z,
                    bubble.count(),
                    bubble.offsetX(),
                    bubble.offsetY(),
                    bubble.offsetZ(),
                    bubble.speed()
            );
        }

        EffectSpec steam = steamEffect;
        if (steam.enabled() && random.nextFloat() < steam.chance()) {
            double x = center.getX() + (random.nextDouble() * 0.4D - 0.2D);
            double y = center.getY() + steam.yOffset();
            double z = center.getZ() + (random.nextDouble() * 0.4D - 0.2D);
            for (int i = 0; i < steam.count(); i++) {
                world.spawnParticle(
                        steam.particle(),
                        x, y, z,
                        0,
                        steam.offsetX(),
                        steam.offsetY() + (random.nextDouble() * 0.01D),
                        steam.offsetZ(),
                        steam.speed()
                );
            }

            EffectSpec secondary = secondarySteamEffect;
            if (secondary.enabled()) {
                for (int i = 0; i < secondary.count(); i++) {
                    world.spawnParticle(
                            secondary.particle(),
                            x,
                            y + secondary.yOffset(),
                            z,
                            0,
                            secondary.offsetX(),
                            secondary.offsetY() + (random.nextDouble() * 0.01D),
                            secondary.offsetZ(),
                            secondary.speed()
                    );
                }
            }
        }

        float soundChance = 0.10f;
        if (behavior != null && behavior.getSoundChance() != null) {
            soundChance = behavior.getSoundChance().floatValue();
        }
        if (random.nextFloat() < soundChance) {
            boolean soupReady = entity.hasPendingOutput() || entity.getMealDisplayItem() != null;
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
            playConfiguredSound(world, center, boilSound, volume, pitch);
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
            int defaultCount,
            double defaultYOffset,
            double defaultOffsetX,
            double defaultOffsetY,
            double defaultOffsetZ,
            double defaultSpeed
    ) {
        return new EffectSpec(
                section == null ? defaultEnabled : section.getBoolean("enabled", defaultEnabled),
                resolveParticle(section == null ? null : section.getString("type"), defaultParticle),
                section == null ? defaultChance : (float) section.getDouble("chance", defaultChance),
                Math.max(1, section == null ? defaultCount : section.getInt("count", defaultCount)),
                section == null ? defaultYOffset : section.getDouble("y-offset", defaultYOffset),
                section == null ? defaultOffsetX : section.getDouble("offset-x", defaultOffsetX),
                section == null ? defaultOffsetY : section.getDouble("offset-y", defaultOffsetY),
                section == null ? defaultOffsetZ : section.getDouble("offset-z", defaultOffsetZ),
                Math.max(0.001D, section == null ? defaultSpeed : section.getDouble("speed", defaultSpeed))
        );
    }

    private Particle resolveParticle(String configured, Particle defaultParticle) {
        if (configured == null || configured.isBlank()) {
            return defaultParticle;
        }

        String normalized = configured.trim();
        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator < normalized.length() - 1) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }

        try {
            return Particle.valueOf(normalized.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return defaultParticle;
        }
    }

    private ResolvedSound resolveSound(String configured, Sound defaultSound) {
        if (configured == null || configured.isBlank()) {
            return ResolvedSound.fromBukkit(defaultSound);
        }

        String trimmed = configured.trim();
        String registryKey;
        if (trimmed.contains(":")) {
            registryKey = trimmed.toLowerCase();
        } else {
            registryKey = "minecraft:" + trimmed.toLowerCase().replace('_', '.');
        }
        Sound registrySound = Registry.SOUNDS.get(NamespacedKey.fromString(registryKey));
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

    private void playConfiguredSound(World world, Location location, ResolvedSound sound, float volume, float pitch) {
        if (sound.bukkitSound() != null) {
            world.playSound(location, sound.bukkitSound(), volume, pitch);
            return;
        }
        if (sound.soundKey() != null && !sound.soundKey().isBlank()) {
            world.playSound(location, sound.soundKey(), SoundCategory.BLOCKS, volume, pitch);
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
        SKILLET,
        STOVE,
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

