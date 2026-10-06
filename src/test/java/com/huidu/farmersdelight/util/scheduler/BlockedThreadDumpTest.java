package com.huidu.farmersdelight.util.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockedThreadDumpTest {

    private static final String OWN_FRAME =
            "com.huidu.farmersdelight.manager.KegManager.flush(KegManager.java:42)";

    private static List<BlockedThreadDump.Sample> samples() {
        List<BlockedThreadDump.Sample> samples = new ArrayList<>();
        samples.add(new BlockedThreadDump.Sample("farmersdelight async pool-1", "WAITING", List.of(
                OWN_FRAME,
                "java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:1)",
                "java.util.concurrent.locks.LockSupport.park(LockSupport.java:211)",
                "java.lang.Thread.run(Thread.java:1583)")));
        samples.add(new BlockedThreadDump.Sample("Server thread", "RUNNABLE", List.of(
                "net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:1)")));
        return samples;
    }

    @Test
    void renderIsPureAndLeavesTheSnapshotAlone() {
        List<BlockedThreadDump.Sample> samples = samples();

        String first = BlockedThreadDump.render("pool 'x' did not drain", samples, 8);
        String second = BlockedThreadDump.render("pool 'x' did not drain", samples, 8);

        assertEquals(first, second, "the same snapshot and cap have to render the same text");
        assertEquals(2, samples.size(), "rendering must not consume or reorder the snapshot");
        assertEquals(4, samples.get(0).frames().size(), "rendering must not trim the samples themselves");
    }

    @Test
    void renderNamesTheThreadStateAndTopFrameAndCapsFrames() {
        String text = BlockedThreadDump.render("async pool did not drain within 10 seconds", samples(), 2);

        assertTrue(text.contains("async pool did not drain within 10 seconds"), text);
        assertTrue(text.contains("farmersdelight async pool-1"), text);
        assertTrue(text.contains("[WAITING]"), text);
        assertTrue(text.contains("KegManager.flush"), text);
        assertTrue(text.contains("... 2 more frame(s)"), text);
        assertFalse(text.contains("LockSupport.park"), "frames past the cap must stay out of the report");
    }

    @Test
    void renderWithoutThreadsStillNamesTheReason() {
        String text = BlockedThreadDump.render("nothing left", List.of(), 8);

        assertTrue(text.contains("nothing left"), text);
        assertTrue(text.contains("0 thread(s)"), text);
    }

    @Test
    void reportHappensExactlyOncePerDump() {
        AtomicInteger writes = new AtomicInteger();
        BlockedThreadDump dump = BlockedThreadDump.toSink(text -> writes.incrementAndGet(), BlockedThreadDumpTest::samples);

        assertTrue(dump.report("first timeout"), "the first report has to write");
        assertFalse(dump.report("second timeout"), "a later step must not dump again");
        assertEquals(1, writes.get());
    }

    @Test
    void captureIsBoundedAndFindsOurOwnStuckThread() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "farmersdelight-test-worker");
        worker.start();
        try {
            assertTrue(running.await(5, TimeUnit.SECONDS));
            List<BlockedThreadDump.Sample> captured = BlockedThreadDump.capture();

            assertTrue(captured.size() <= BlockedThreadDump.MAX_THREADS,
                    "a dump has to stay bounded, was " + captured.size());
            boolean found = captured.stream().anyMatch(sample -> "farmersdelight-test-worker".equals(sample.name()));
            assertTrue(found, "a waiting thread inside our own package belongs in the snapshot");
            for (BlockedThreadDump.Sample sample : captured) {
                assertNotNull(sample.name());
                assertNotNull(sample.state());
                assertTrue(sample.frames().size() <= BlockedThreadDump.MAX_FRAMES, sample.name());
            }
        } finally {
            release.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(5));
        }
    }
}
