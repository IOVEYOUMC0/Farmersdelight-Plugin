package com.huidu.farmersdelight.util.scheduler;

import org.bukkit.World;

/**
 * The scheduling shapes more than one subsystem needs.
 *
 *
 * A task that reads a chunk has to run on the region that owns it and has to re-check the chunk from inside
 * the task: the dispatch is queued, so the chunk can unload before the task runs. Both halves are identical
 * at every call site, so they live here rather than being repeated wherever a chunk is dispatched.
 */
public final class RegionTasks {

    private RegionTasks() {
    }

    /**
     * Hands one task to the region that owns the chunk, re-checking the chunk is still loaded inside that
     * task. A chunk that unloaded in the meantime is skipped instead of being read from stale state.
     */
    public static void runAtLoadedChunk(RegionDispatcher dispatcher, World world, int chunkX, int chunkZ,
                                        Runnable task) {
        if (dispatcher == null || task == null) {
            return;
        }
        dispatcher.runAt(world, chunkX, chunkZ, () -> {
            if (isChunkLoaded(world, chunkX, chunkZ)) {
                task.run();
            }
        });
    }

    /** True when the chunk can still be read; a null world has no loaded chunk to speak of. */
    static boolean isChunkLoaded(World world, int chunkX, int chunkZ) {
        return world != null && world.isChunkLoaded(chunkX, chunkZ);
    }
}
