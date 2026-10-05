package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the chunk scan queue: insertion order, one entry per chunk, keyed removal and a bounded drain.
 *
 *
 * Those four properties are what keep the carrier scan from re-walking a chunk that is already waiting
 * and from walking every waiting chunk on each unload. The payload is a plain string, which is the whole
 * reason the queue does not name Chunk and can be exercised without a server.
 */
class PendingChunkScanQueueTest {

    private static final UUID OVERWORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void drainReturnsEntriesInInsertionOrder() {
        PendingChunkScanQueue<String> queue = new PendingChunkScanQueue<>();
        queue.add(OVERWORLD, 1, 2, "first");
        queue.add(OVERWORLD, 3, 4, "second");
        queue.add(NETHER, 5, 6, "third");

        assertEquals(List.of("first", "second", "third"), queue.drain(10));
        assertTrue(queue.isEmpty());
    }

    @Test
    void queueingTheSameChunkTwiceKeepsOneEntryInItsOriginalPosition() {
        PendingChunkScanQueue<String> queue = new PendingChunkScanQueue<>();
        queue.add(OVERWORLD, 1, 2, "first");
        queue.add(OVERWORLD, 3, 4, "second");
        queue.add(OVERWORLD, 1, 2, "first-again");

        assertEquals(2, queue.size());
        // The repeat replaces the entry already queued for that chunk; it must not move it behind "second".
        assertEquals(List.of("first-again", "second"), queue.drain(10));
    }

    @Test
    void theSameCoordinatesInDifferentWorldsAreDifferentChunks() {
        PendingChunkScanQueue<String> queue = new PendingChunkScanQueue<>();
        queue.add(OVERWORLD, 7, 8, "overworld");
        queue.add(NETHER, 7, 8, "nether");

        assertEquals(2, queue.size());
        assertTrue(queue.remove(OVERWORLD, 7, 8));
        assertEquals(List.of("nether"), queue.drain(10));
    }

    @Test
    void removeByKeyLeavesTheOtherEntriesAndTheirOrderAlone() {
        PendingChunkScanQueue<String> queue = new PendingChunkScanQueue<>();
        queue.add(OVERWORLD, 0, 0, "a");
        queue.add(OVERWORLD, 0, 1, "b");
        queue.add(OVERWORLD, 0, 2, "c");

        assertTrue(queue.remove(OVERWORLD, 0, 1));
        assertFalse(queue.remove(OVERWORLD, 0, 1), "the second removal has nothing left to drop");
        assertFalse(queue.remove(OVERWORLD, 9, 9));
        assertEquals(List.of("a", "c"), queue.drain(10));
    }

    @Test
    void drainIsLimitedToTheRequestedSlice() {
        PendingChunkScanQueue<String> queue = new PendingChunkScanQueue<>();
        for (int i = 0; i < 6; i++) {
            queue.add(OVERWORLD, i, 0, "c" + i);
        }

        assertEquals(List.of("c0", "c1"), queue.drain(2));
        assertEquals(4, queue.size());
        assertEquals(List.of("c2", "c3", "c4", "c5"), queue.drain(99));
        assertTrue(queue.drain(4).isEmpty(), "an empty queue drains to nothing");
        assertTrue(queue.drain(0).isEmpty());
    }

    @Test
    void removeWorldDropsOnlyThatWorldsChunks() {
        PendingChunkScanQueue<String> queue = new PendingChunkScanQueue<>();
        queue.add(OVERWORLD, 1, 1, "ow1");
        queue.add(NETHER, 2, 2, "ne1");
        queue.add(OVERWORLD, 3, 3, "ow2");
        queue.add(NETHER, 4, 4, "ne2");

        assertEquals(2, queue.removeWorld(OVERWORLD));
        // The surviving world keeps the order its chunks were queued in.
        assertEquals(List.of("ne1", "ne2"), queue.drain(10));
        assertEquals(0, queue.removeWorld(OVERWORLD), "a second world removal finds nothing");
    }

    @Test
    void clearEmptiesTheQueue() {
        PendingChunkScanQueue<String> queue = new PendingChunkScanQueue<>();
        queue.add(OVERWORLD, 1, 1, "a");
        queue.add(NETHER, 2, 2, "b");

        queue.clear();

        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertTrue(queue.drain(5).isEmpty());
    }

    @Test
    void nullWorldAndNullPayloadAreIgnored() {
        PendingChunkScanQueue<String> queue = new PendingChunkScanQueue<>();
        queue.add(null, 1, 1, "a");
        queue.add(OVERWORLD, 1, 1, null);

        assertTrue(queue.isEmpty());
        assertFalse(queue.remove(null, 1, 1));
        assertEquals(0, queue.removeWorld(null));
    }

    @Test
    void scanBandKeepsTheConfiguredHeightWhenTheWorldIsTallEnough() {
        // The overworld build range is taller than the band, so the band is exactly the configured height.
        assertEquals(96, CarrierRestorer.scanYLength(96, -64, 320));
        assertEquals(24576, CarrierRestorer.positionsPerChunk(96, -64, 320));
        assertEquals(16 * 16 * 96, CarrierRestorer.positionsPerChunk(96, -64, 320));
    }

    @Test
    void scanBandIsClampedToAWorldShorterThanTheBand() {
        // A 64-block-tall world cannot offer more than its own range.
        assertEquals(64, CarrierRestorer.scanYLength(96, 0, 64));
        assertEquals(16 * 16 * 64, CarrierRestorer.positionsPerChunk(96, 0, 64));
        assertEquals(0, CarrierRestorer.scanYLength(96, 0, 0));
    }

    @Test
    void positionsPerChunkFollowsTheConfiguredColumnHeight() {
        assertEquals(16 * 16 * 16, CarrierRestorer.positionsPerChunk(16, -64, 320));
        assertEquals(16 * 16 * 96, CarrierRestorer.positionsPerChunk(96, 0, 256));
    }
}
