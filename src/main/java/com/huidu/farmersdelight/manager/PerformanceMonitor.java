package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.ManagerSupport;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Aggregates cooking-pot tick timings into P50/P95/P99-ready samples and emits density/total warnings.
// The snapshot builder lives in TickManager because it stitches in metrics owned by the tick loop.
class PerformanceMonitor {

    private static final int PERFORMANCE_HISTORY_CAPACITY = 4096;

    private final FarmersDelightPlugin plugin;
    private boolean warningsEnabled = true;
    private int densityWarningThreshold = 64;
    private int totalWarningThreshold = 1000;
    private long warningCooldownMillis = 600_000L;
    private final Map<String, Long> warningTimes = new ConcurrentHashMap<>();

    private volatile boolean statsEnabled;
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong lastNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();
    private final AtomicLong lastActiveBlocks = new AtomicLong();
    private final AtomicLong lastProcessedBlocks = new AtomicLong();
    // Per-block hot-spot sampling: keyed by BlockPosKey so a pot is attributed once per pass across all
    // regions. Grows only while a profile runs, handed off to the caller on snapshot(), then reset so
    // per-profile attribution never bleeds into the next run.
    private final Map<BlockPosKey, Long> blockNanos = new ConcurrentHashMap<>();
    // Rolling per-pass duration history for P50/P95/P99 reporting, mirroring Spark's MSPT distribution.
    private long[] history = new long[PERFORMANCE_HISTORY_CAPACITY];
    private int historySize;

    PerformanceMonitor(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    void reloadConfig() {
        warningsEnabled = plugin.getConfigBoolean(true, "performance.warnings-enabled");
        densityWarningThreshold = Math.max(1, plugin.getConfigInt(64,
                "performance.cooking-pot-density-warning-threshold"));
        totalWarningThreshold = Math.max(1, plugin.getConfigInt(1000,
                "performance.cooking-pot-total-warning-threshold"));
        int cooldownSeconds = Math.max(1, plugin.getConfigInt(600,
                "performance.warning-cooldown-seconds"));
        warningCooldownMillis = cooldownSeconds * 1000L;
    }

    boolean isRecording() {
        return statsEnabled;
    }

    void setRecording(boolean enabled) {
        statsEnabled = enabled;
    }

    void reset(int snapshotActiveBlocks) {
        samples.set(0L);
        totalNanos.set(0L);
        lastNanos.set(0L);
        maxNanos.set(0L);
        lastActiveBlocks.set(Math.max(0, snapshotActiveBlocks));
        lastProcessedBlocks.set(0L);
        blockNanos.clear();
        historySize = 0;
        statsEnabled = true;
    }

    void recordPass(long durationNanos, int activeCount, int processedCount) {
        if (!statsEnabled) {
            return;
        }
        long bounded = Math.max(0L, durationNanos);
        samples.incrementAndGet();
        totalNanos.addAndGet(bounded);
        lastNanos.set(bounded);
        lastActiveBlocks.set(Math.max(0, activeCount));
        lastProcessedBlocks.set(Math.max(0, processedCount));
        updateMax(maxNanos, bounded);
        appendHistory(bounded);
    }

    void recordBlockCost(BlockPosKey posKey, long cost) {
        blockNanos.merge(posKey, cost, Long::sum);
    }

    // Grow-only per-pass duration history for percentile reporting; capped so a long profile cannot
    // allocate unbounded. Percentiles stay valid because the cap only drops the oldest samples.
    private void appendHistory(long durationNanos) {
        if (historySize >= history.length) {
            return;
        }
        history[historySize] = durationNanos;
        historySize++;
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

    long samples() {
        return samples.get();
    }

    long totalNanos() {
        return totalNanos.get();
    }

    long lastNanos() {
        return lastNanos.get();
    }

    long maxNanos() {
        return maxNanos.get();
    }

    long lastActiveBlocks() {
        return lastActiveBlocks.get();
    }

    long lastProcessedBlocks() {
        return lastProcessedBlocks.get();
    }

    boolean statsEnabled() {
        return statsEnabled;
    }

    long[] historyCopy() {
        return java.util.Arrays.copyOf(history, historySize);
    }

    Map<BlockPosKey, Long> blockNanosCopy() {
        return new HashMap<>(blockNanos);
    }

    void checkPooledWarnings() {
        if (!warningsEnabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            Map<BlockPosKey, ?> entities = CookingPotBlockBehavior.getAllBlockEntities(world);
            int total = entities.size();
            if (total >= totalWarningThreshold) {
                warnWithCooldown("cooking-pot-total:" + world.getUID(),
                        I18n.formatNamedArgs("console.performance.cooking_pot_total",
                                "world", world.getName(),
                                "count", total,
                                "threshold", totalWarningThreshold));
            }
            if (total < densityWarningThreshold) {
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
                if (count < densityWarningThreshold) {
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
                                "threshold", densityWarningThreshold));
            }
        }
    }

    void pruneOldWarnings() {
        // The warning-times map is keyed by world/chunk and would grow unbounded otherwise; remove
        // entries past the cooldown (a later warning for the same key re-adds it).
        long cutoff = System.currentTimeMillis() - warningCooldownMillis;
        warningTimes.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private long packChunkKey(int chunkX, int chunkZ) {
        return ManagerSupport.chunkKey(chunkX, chunkZ);
    }

    private int unpackChunkX(long key) {
        return (int) (key >> 32);
    }

    private int unpackChunkZ(long key) {
        return (int) key;
    }

    private void warnWithCooldown(String key, String message) {
        long now = System.currentTimeMillis();
        Long previous = warningTimes.get(key);
        if (previous != null && now - previous < warningCooldownMillis) {
            return;
        }
        warningTimes.put(key, now);
        plugin.getLogger().warning(message);
    }
}