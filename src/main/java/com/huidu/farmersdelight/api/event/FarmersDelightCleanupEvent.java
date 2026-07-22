package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fired by /fd cleanup after FarmersDelight has cleaned its own orphan display / tray entities, so
 * addons can clean their own state in the same admin pass. Listeners report how many entities they removed
 * via addRemoved(int); the command sums those counts into its final reply.
 *
 * <p>Mirrors FarmersDelightReloadEvent as the cleanup-side hook for addons. Listeners should treat
 * this as a one-shot admin signal (not periodic) and may do best-effort regional scheduling on Folia — the
 * count is allowed to be "scheduled for removal" rather than "removed before this method returns".
 */
@ApiStatus.NonExtendable
public class FarmersDelightCleanupEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AtomicInteger removed = new AtomicInteger();

    /** Report how many addon-owned entities this listener has cleaned (or scheduled to clean). */
    public void addRemoved(int count) {
        if (count > 0) {
            removed.addAndGet(count);
        }
    }

    /** Sum reported by every listener; consumed by the cleanup command for its final reply. */
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
