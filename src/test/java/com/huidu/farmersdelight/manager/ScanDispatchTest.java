package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.util.scheduler.RegionDispatcher;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the carrier scan dispatch decision: which queued chunks one maintenance pass hands out, in which
 * order, and that a chunk which is no longer loaded is not scanned.
 *
 *
 * The dispatcher is a recording fake and the worlds are null, so the decision is asserted without a server;
 * the scan body records whether it ran.
 */
class ScanDispatchTest {

    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void aPassDispatchesTheQueuedChunksInInsertionOrderUpToTheBudget() {
        PendingChunkScanQueue<CarrierRestorer.ChunkTarget> queue = new PendingChunkScanQueue<>();
        enqueue(queue, 1, 1);
        enqueue(queue, 2, 2);
        enqueue(queue, 3, 3);
        RecordingDispatcher dispatcher = new RecordingDispatcher(false);

        CarrierRestorer.dispatchQueuedScans(queue, 2, dispatcher, (world, chunkX, chunkZ) -> { });

        assertEquals(List.of("1,1", "2,2"), dispatcher.dispatched);
        assertEquals(1, queue.size(), "the rest of the queue waits for the next pass");
    }

    @Test
    void theSameChunkQueuedTwiceIsDispatchedOnce() {
        PendingChunkScanQueue<CarrierRestorer.ChunkTarget> queue = new PendingChunkScanQueue<>();
        enqueue(queue, 8, 9);
        enqueue(queue, 8, 9);
        RecordingDispatcher dispatcher = new RecordingDispatcher(false);

        CarrierRestorer.dispatchQueuedScans(queue, 4, dispatcher, (world, chunkX, chunkZ) -> { });

        assertEquals(List.of("8,9"), dispatcher.dispatched);
        assertTrue(queue.isEmpty());
    }

    @Test
    void aChunkThatIsNoLongerLoadedIsNotScanned() {
        PendingChunkScanQueue<CarrierRestorer.ChunkTarget> queue = new PendingChunkScanQueue<>();
        enqueue(queue, 5, 6);
        // This dispatcher runs the task inline, which is what a region owning the chunk does.
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);
        List<String> scanned = new ArrayList<>();

        CarrierRestorer.dispatchQueuedScans(queue, 4, dispatcher,
                (world, chunkX, chunkZ) -> scanned.add(chunkX + "," + chunkZ));

        assertEquals(List.of("5,6"), dispatcher.dispatched);
        assertTrue(scanned.isEmpty(), "a queued chunk that unloaded before the task ran must not be read");
    }

    @Test
    void anEmptyQueueDispatchesNothing() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);

        CarrierRestorer.dispatchQueuedScans(new PendingChunkScanQueue<>(), 4, dispatcher,
                (world, chunkX, chunkZ) -> { });

        assertTrue(dispatcher.dispatched.isEmpty());
    }

    private static void enqueue(PendingChunkScanQueue<CarrierRestorer.ChunkTarget> queue, int chunkX, int chunkZ) {
        queue.add(WORLD_ID, chunkX, chunkZ, new CarrierRestorer.ChunkTarget(null, chunkX, chunkZ));
    }

    private static final class RecordingDispatcher implements RegionDispatcher {

        private final List<String> dispatched = new ArrayList<>();
        private final boolean runTasks;

        RecordingDispatcher(boolean runTasks) {
            this.runTasks = runTasks;
        }

        @Override
        public void runAt(World world, int chunkX, int chunkZ, Runnable task) {
            dispatched.add(chunkX + "," + chunkZ);
            if (runTasks) {
                task.run();
            }
        }

        @Override
        public void runForEntity(Entity entity, Runnable task, Runnable retired) {
            throw new AssertionError("this test only dispatches chunks");
        }
    }
}
