package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.ManagerSupport;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Collects bounded, opt-in timings; world access remains in the calling region/entity task.
public final class PerformanceMonitor {

    private static final int PERFORMANCE_HISTORY_CAPACITY = 4096;
    private static final int HOTSPOT_CAPACITY = 4096;

    public enum Feature {
        COOKING_POT("cooking_pot"), HANDHELD("handheld"),
        HANDHELD_DISPLAY("handheld_display"), SKILLET("skillet"), STOVE("stove");

        private final String id;

        Feature(String id) { this.id = id; }

        public String id() { return id; }
    }

    private final FarmersDelightPlugin plugin;
    private boolean warningsEnabled = true;
    private int densityWarningThreshold = 64;
    private int totalWarningThreshold = 1000;
    private long warningCooldownMillis = 600_000L;
    private final Map<String, Long> warningTimes = new ConcurrentHashMap<>();

    private volatile Session session;
    private long nextSessionId;

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
        Session current = session;
        return current != null && current.active;
    }

    synchronized long start(Feature feature) {
        if (isRecording()) return 0L;
        session = new Session(++nextSessionId, feature);
        return session.id;
    }

    synchronized Snapshot finish(long id) {
        Session current = session;
        if (current == null || current.id != id || !current.active) return null;
        current.stop();
        return current.snapshot();
    }

    synchronized void stop() {
        if (session != null) session.stop();
    }

    Snapshot snapshot() {
        Session current = session;
        return current == null ? Snapshot.EMPTY : current.snapshot();
    }

    Session recording() {
        Session current = session;
        return current != null && current.active ? current : null;
    }

    Timing timing(Feature feature) {
        Session current = recording();
        return current == null ? null : current.timings.get(feature);
    }

    public record Hotspot(UUID worldId, BlockPosKey position) { }

    public record TimingSnapshot(long calls, long totalNanos, long lastNanos, long maxNanos,
                                 long[] historyNanos) {
        private static final TimingSnapshot EMPTY = new TimingSnapshot(0, 0, 0, 0, new long[0]);

        public double averageNanos() { return calls == 0 ? 0 : (double) totalNanos / calls; }

        public long percentile(int percent) {
            if (historyNanos.length == 0) return 0;
            long[] sorted = historyNanos.clone();
            Arrays.sort(sorted);
            int index = (int) Math.ceil(sorted.length * Math.max(1, Math.min(100, percent)) / 100.0) - 1;
            return sorted[index];
        }
    }

    public record Snapshot(boolean active, long elapsedNanos, TimingSnapshot pass,
                           int lastActiveBlocks, int lastProcessedBlocks,
                           Map<Feature, TimingSnapshot> features, Map<Hotspot, Long> blockNanos,
                           long omittedHotspotCalls) {
        private static final Snapshot EMPTY = new Snapshot(false, 0, TimingSnapshot.EMPTY,
                0, 0, Map.of(), Map.of(), 0);
    }

    static final class Session {
        final long id;
        final long startedNanos = System.nanoTime();
        volatile boolean active = true;
        private long elapsedNanos;
        final Timing pass = new Timing(this);
        final Map<Feature, Timing> timings = new EnumMap<>(Feature.class);
        private final Map<Hotspot, Long> blockNanos = new HashMap<>();
        private int lastActiveBlocks;
        private int lastProcessedBlocks;
        private long omittedHotspotCalls;

        Session(long id, Feature feature) {
            this.id = id;
            for (Feature value : Feature.values()) {
                if (feature == null || feature == value) timings.put(value, new Timing(this));
            }
        }

        synchronized void stop() {
            if (!active) return;
            active = false;
            elapsedNanos = System.nanoTime() - startedNanos;
        }

        synchronized void recordPass(long nanos, int activeCount, int processedCount) {
            if (!active) return;
            pass.record(nanos);
            lastActiveBlocks = activeCount;
            lastProcessedBlocks = processedCount;
        }

        synchronized void recordBlockCost(UUID worldId, BlockPosKey posKey, long nanos) {
            if (!active) return;
            Hotspot key = new Hotspot(worldId, posKey);
            if (blockNanos.size() >= HOTSPOT_CAPACITY && !blockNanos.containsKey(key)) {
                omittedHotspotCalls++;
                return;
            }
            blockNanos.merge(key, Math.max(0, nanos), Long::sum);
        }

        synchronized Snapshot snapshot() {
            Map<Feature, TimingSnapshot> features = new EnumMap<>(Feature.class);
            timings.forEach((feature, timing) -> features.put(feature, timing.snapshot()));
            long elapsed = active ? System.nanoTime() - startedNanos : elapsedNanos;
            return new Snapshot(active, elapsed, pass.snapshot(), lastActiveBlocks,
                    lastProcessedBlocks, Map.copyOf(features), Map.copyOf(blockNanos), omittedHotspotCalls);
        }
    }

    static final class Timing {
        private final Session owner;
        private final long[] history = new long[PERFORMANCE_HISTORY_CAPACITY];
        private long calls;
        private long totalNanos;
        private long lastNanos;
        private long maxNanos;

        Timing(Session owner) { this.owner = owner; }

        // ponytail: one short lock per feature during sampling; shard only if profiling shows contention.
        synchronized void record(long nanos) {
            if (!owner.active) return;
            long bounded = Math.max(0, nanos);
            history[(int) (calls % history.length)] = bounded;
            calls++;
            totalNanos += bounded;
            lastNanos = bounded;
            maxNanos = Math.max(maxNanos, bounded);
        }

        synchronized TimingSnapshot snapshot() {
            return new TimingSnapshot(calls, totalNanos, lastNanos, maxNanos,
                    Arrays.copyOf(history, (int) Math.min(calls, history.length)));
        }
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
