package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fired by a FarmersDelight /fd <migration> admin action (e.g. /fd rug-migrate) after
 * FarmersDelight has migrated its own legacy data, so addons can migrate their own legacy state in the same
 * admin pass instead of intercepting the command or reflecting into FarmersDelight internals.
 * #migrationKey() identifies which migration ran (e.g. "rug"), so a listener can react only
 * to migrations it cares about. Listeners report how many entries they migrated / cleaned via
 * #addRemoved(int); the command sums those counts into its final reply.
 *
 * Mirrors FarmersDelightCleanupEvent / FarmersDelightReloadEvent as the migrate-side
 * addon hook. Treat it as a one-shot admin signal (not periodic); listeners may do best-effort regional
 * scheduling on Folia — the count is allowed to be "scheduled for removal" rather than "removed before this
 * method returns".
 */
@ApiStatus.NonExtendable
public class FarmersDelightMigrateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String migrationKey;
    private final AtomicInteger removed = new AtomicInteger();

    public FarmersDelightMigrateEvent(String migrationKey) {
        this.migrationKey = migrationKey;
    }

    /** Which migration ran, e.g. "rug". Lets a listener react only to migrations it cares about. */
    public String migrationKey() {
        return migrationKey;
    }

    /** Report how many addon-owned entries this listener migrated / cleaned (or scheduled to clean). */
    public void addRemoved(int count) {
        if (count > 0) {
            removed.addAndGet(count);
        }
    }

    /** Sum reported by every listener; consumed by the migrate command for its final reply. */
    public int getRemoved() {
        return removed.get();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
