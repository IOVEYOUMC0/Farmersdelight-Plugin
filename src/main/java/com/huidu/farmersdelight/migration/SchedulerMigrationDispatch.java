package com.huidu.farmersdelight.migration;

import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Objects;

/**
 * The production {@link MigrationDispatch}: every shape is handed to the plugin's own scheduler adapter, so
 * the migration hooks follow exactly the same Folia rules as the rest of the plugin and never call
 * {@code Bukkit.getScheduler} directly.
 */
public final class SchedulerMigrationDispatch implements MigrationDispatch {

    private final SchedulerAdapter scheduler;

    public SchedulerMigrationDispatch(SchedulerAdapter scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public boolean ownsCurrentThread(Object owner) {
        if (owner instanceof Location location) {
            return scheduler.isOwnedByCurrentRegion(location);
        }
        if (owner instanceof Entity entity) {
            // An entity is owned by the region holding its position; the adapter answers for that location.
            return scheduler.isOwnedByCurrentRegion(entity.getLocation());
        }
        return false;
    }

    @Override
    public void runAtOwner(Object owner, Runnable task) {
        if (owner instanceof Location location) {
            scheduler.runAt(location, task);
            return;
        }
        if (owner instanceof Entity entity) {
            scheduler.runForEntity(entity, task);
            return;
        }
        // Unknown owner: falling back to the global thread is still safer than reading it from here.
        scheduler.run(task);
    }

    @Override
    public void runGlobal(Runnable task) {
        scheduler.run(task);
    }
}
