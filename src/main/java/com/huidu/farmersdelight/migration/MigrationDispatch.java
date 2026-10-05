package com.huidu.farmersdelight.migration;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * The Folia-safe dispatch the migration hooks go through, kept behind an interface so an offline test can
 * assert the decision instead of a running region scheduler.
 *
 *
 * Every hook owns a target — a player or item entity, or the block an inventory belongs to. The rule is the
 * same everywhere: work may run inline only when the current thread already owns the target; otherwise it
 * has to be handed to the owning region and never read from here. inlineIfOwned is that rule in one
 * place, so all five hooks make the same decision and a test can count the dispatches.
 */
public interface MigrationDispatch {

    /** True when the current thread already owns owner, so the work may run inline. */
    boolean ownsCurrentThread(Object owner);

    /** Hands the work to the region/thread that owns owner: exactly one dispatch. */
    void runAtOwner(Object owner, Runnable task);

    /** Runs the work on the global thread, for startup work that owns no region yet. */
    void runGlobal(Runnable task);

    /**
     * Runs now when the current thread owns the target, otherwise exactly one dispatch. A null dispatcher
     * (migration not wired, e.g. in a unit test of some other component) simply skips the work.
     */
    static void inlineIfOwned(MigrationDispatch dispatch, Object owner, Runnable task) {
        if (dispatch == null || task == null) {
            return;
        }
        if (owner == null) {
            // No owner to attribute the work to: never touch it from an unknown thread.
            return;
        }
        if (dispatch.ownsCurrentThread(owner)) {
            task.run();
            return;
        }
        dispatch.runAtOwner(owner, task);
    }

    /** Convenience for the startup sweep, which runs on the global thread without a specific owner. */
    static void global(MigrationDispatch dispatch, Runnable task) {
        if (dispatch == null || task == null) {
            return;
        }
        dispatch.runGlobal(task);
    }

    /** The owner objects this interface accepts, named so the call sites read the same everywhere. */
    static Object ownerOf(Location location) {
        return location;
    }

    static Object ownerOf(Entity entity) {
        return entity;
    }
}
