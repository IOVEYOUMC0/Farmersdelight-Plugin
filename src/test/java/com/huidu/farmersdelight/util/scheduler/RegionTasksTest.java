package com.huidu.farmersdelight.util.scheduler;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared chunk dispatch: one task per chunk, handed to the region that owns it, with the chunk
 * re-checked from inside the task.
 *
 *
 * The world is a dynamic proxy and the dispatcher a recording fake, so both branches of the re-check are
 * asserted without a server.
 */
class RegionTasksTest {

    @Test
    void aLoadedChunkRunsTheTask() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);
        List<String> scanned = new ArrayList<>();

        RegionTasks.runAtLoadedChunk(dispatcher, world(true), 4, -7, () -> scanned.add("ran"));

        assertEquals(List.of("4,-7"), dispatcher.dispatched);
        assertEquals(List.of("ran"), scanned);
    }

    @Test
    void anUnloadedChunkIsDispatchedButTheTaskDoesNotRun() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);
        List<String> scanned = new ArrayList<>();

        RegionTasks.runAtLoadedChunk(dispatcher, world(false), 4, -7, () -> scanned.add("ran"));

        // The target is still handed out: the check belongs inside the task, when the chunk is known.
        assertEquals(List.of("4,-7"), dispatcher.dispatched);
        assertTrue(scanned.isEmpty());
    }

    @Test
    void aNullWorldCannotBeRead() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);
        List<String> scanned = new ArrayList<>();

        RegionTasks.runAtLoadedChunk(dispatcher, null, 1, 2, () -> scanned.add("ran"));

        assertFalse(RegionTasks.isChunkLoaded(null, 1, 2));
        assertTrue(scanned.isEmpty());
    }

    @Test
    void aMissingDispatcherOrTaskIsIgnored() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);

        RegionTasks.runAtLoadedChunk(null, world(true), 0, 0, () -> { });
        RegionTasks.runAtLoadedChunk(dispatcher, world(true), 0, 0, null);

        assertTrue(dispatcher.dispatched.isEmpty());
    }

    @Test
    void theTaskIsOnlyQueuedWhenTheDispatcherDoesNotRunItInline() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(false);
        List<String> scanned = new ArrayList<>();

        RegionTasks.runAtLoadedChunk(dispatcher, world(true), 3, 3, () -> scanned.add("ran"));

        assertEquals(List.of("3,3"), dispatcher.dispatched);
        assertTrue(scanned.isEmpty(), "the dispatcher owns when the task runs");
    }

    /** A world whose chunks are loaded or not, without a server; any other call is a test bug. */
    private static World world(boolean chunkLoaded) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isChunkLoaded")) {
                        return chunkLoaded;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == short.class) return (short) 0;
                    if (type == byte.class) return (byte) 0;
                    if (type == float.class) return 0F;
                    if (type == double.class) return 0D;
                    if (type == char.class) return (char) 0;
                    return null;
                });
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
