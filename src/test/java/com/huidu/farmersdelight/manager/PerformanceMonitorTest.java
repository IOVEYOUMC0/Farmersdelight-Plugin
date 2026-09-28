package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.util.BlockPosKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.huidu.farmersdelight.manager.PerformanceMonitor.Feature.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceMonitorTest {

    @Test
    void profilesAreExclusiveFilteredAndIsolatedFromLateTasks() {
        PerformanceMonitor monitor = new PerformanceMonitor(null);
        assertNull(monitor.recording());
        assertNull(monitor.timing(HANDHELD));
        assertTrue(monitor.snapshot().features().isEmpty());
        long first = monitor.start(HANDHELD);
        var oldSession = monitor.recording();
        var oldTiming = monitor.timing(HANDHELD);
        assertNull(monitor.timing(STOVE));
        assertEquals(0, monitor.start(STOVE));
        oldTiming.record(20);
        oldSession.recordPass(30, 5, 2);
        var result = monitor.finish(first);
        assertFalse(result.active());
        assertEquals(1, result.features().size());
        assertEquals(20, result.features().get(HANDHELD).totalNanos());
        assertEquals(2, result.lastProcessedBlocks());
        assertEquals(result.elapsedNanos(), monitor.snapshot().elapsedNanos());
        assertNull(monitor.timing(HANDHELD));
        assertNull(monitor.finish(first));

        long second = monitor.start(STOVE);
        oldTiming.record(999);
        oldSession.recordPass(999, 99, 99);
        oldSession.recordBlockCost(UUID.randomUUID(), new BlockPosKey(0, 0, 0), 999);
        assertNull(monitor.finish(first));
        assertTrue(monitor.isRecording());
        monitor.timing(STOVE).record(7);
        var next = monitor.finish(second);
        assertEquals(0, next.pass().calls());
        assertEquals(7, next.features().get(STOVE).totalNanos());
        assertTrue(next.blockNanos().isEmpty());
        assertEquals(20, oldTiming.snapshot().totalNanos());
    }

    @Test
    void totalsCoverAllCallsButPercentilesUseRecentBoundedHistory() {
        PerformanceMonitor monitor = new PerformanceMonitor(null);
        long id = monitor.start(HANDHELD_DISPLAY);
        var timing = monitor.timing(HANDHELD_DISPLAY);
        for (int i = 0; i < 4096; i++) timing.record(900);
        for (int i = 0; i < 4096; i++) timing.record(10);
        var snapshot = monitor.finish(id).features().get(HANDHELD_DISPLAY);
        assertEquals(8192, snapshot.calls());
        assertEquals(4096L * 910, snapshot.totalNanos());
        assertEquals(455, snapshot.averageNanos());
        assertEquals(4096, snapshot.historyNanos().length);
        assertEquals(10, snapshot.percentile(95));
        assertEquals(900, snapshot.maxNanos());
        assertEquals(10, snapshot.lastNanos());
        snapshot.historyNanos()[0] = 1_000_000;
        assertEquals(10, timing.snapshot().percentile(100));
    }

    @Test
    void hotspotsSeparateWorldsAndStopGrowingAtTheCap() {
        PerformanceMonitor monitor = new PerformanceMonitor(null);
        long id = monitor.start(COOKING_POT);
        var session = monitor.recording();
        UUID worldA = UUID.randomUUID();
        UUID worldB = UUID.randomUUID();
        BlockPosKey origin = new BlockPosKey(0, 0, 0);
        session.recordBlockCost(worldA, origin, 5);
        session.recordBlockCost(worldB, origin, 9);
        for (int x = 1; x <= 4095; x++) {
            session.recordBlockCost(worldA, new BlockPosKey(x, 0, 0), 1);
        }
        session.recordBlockCost(worldA, origin, 7);
        var snapshot = monitor.finish(id);
        assertEquals(4096, snapshot.blockNanos().size());
        assertEquals(1, snapshot.omittedHotspotCalls());
        assertEquals(12, snapshot.blockNanos().get(new PerformanceMonitor.Hotspot(worldA, origin)));
        assertEquals(9, snapshot.blockNanos().get(new PerformanceMonitor.Hotspot(worldB, origin)));
    }

    @Test
    void concurrentRegionSamplesAndReadersKeepExactTotals() throws Exception {
        PerformanceMonitor monitor = new PerformanceMonitor(null);
        long id = monitor.start(null);
        var timing = monitor.timing(SKILLET);
        try (var executor = Executors.newFixedThreadPool(5)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                tasks.add(() -> {
                    for (int i = 0; i < 5000; i++) timing.record(25);
                    return null;
                });
            }
            tasks.add(() -> {
                for (int i = 0; i < 100; i++) {
                    var snapshot = monitor.snapshot().features().get(SKILLET);
                    assertEquals(snapshot.calls() * 25, snapshot.totalNanos());
                }
                return null;
            });
            for (var task : executor.invokeAll(tasks)) task.get();
        }
        var result = monitor.finish(id).features().get(SKILLET);
        assertEquals(20_000, result.calls());
        assertEquals(500_000, result.totalNanos());
        assertEquals(25, result.percentile(95));
    }
}
