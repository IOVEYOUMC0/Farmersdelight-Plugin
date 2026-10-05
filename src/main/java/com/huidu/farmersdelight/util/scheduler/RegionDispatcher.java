package com.huidu.farmersdelight.util.scheduler;

import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * The region-aware part of the scheduler: the calls that hand work to the thread owning a chunk or an
 * entity.
 *
 *
 * Dispatchers are written against this interface rather than against the concrete adapter, so a test can
 * drive a recording implementation and assert which chunks and entities a maintenance pass hands out.
 * Production resolves to SchedulerAdapter, which keeps the behaviour these methods had before the
 * interface existed.
 */
public interface RegionDispatcher {

    /**
     * Runs the task on the thread that owns the chunk, inline when the current thread already owns it.
     *
     * @param world  world of the target chunk; null means the chunk cannot be attributed to a region
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param task   work to run on the owning thread
     */
    void runAt(World world, int chunkX, int chunkZ, Runnable task);

    /**
     * Runs the task on the thread that owns the entity, without a retired callback. The default keeps a
     * dispatcher that only cares about the retired variant implementable, and matches the adapter's
     * two-argument overload, which production overrides.
     */
    default void runForEntity(Entity entity, Runnable task) {
        runForEntity(entity, task, null);
    }

    /**
     * Runs the task on the thread that owns the entity. retired runs instead when the entity is
     * already gone, so a caller's bookkeeping is released even though the task never ran.
     */
    void runForEntity(Entity entity, Runnable task, Runnable retired);
}
