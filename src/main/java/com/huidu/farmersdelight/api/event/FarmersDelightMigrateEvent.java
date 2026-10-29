package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reports that the legacy-id migration rewrote at least one stored stack. Fired once per storage pass, on the
 * thread that owns that storage, whenever something changed: once per player join ("player"), container open
 * ("inventory"), station block entity ("block"), dropped item ("item"), and once per start-up chunk batch
 * ("startup"). Addons can use the key to drop their own caches keyed by a legacy id. Not cancellable, and the
 * counts accumulate through addRemoved(int).
 */
@ApiStatus.NonExtendable
public class FarmersDelightMigrateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String migrationKey;
    private final AtomicInteger removed = new AtomicInteger();

    public FarmersDelightMigrateEvent(String migrationKey) {
        this.migrationKey = migrationKey;
    }

    public String migrationKey() {
        return migrationKey;
    }

    public void addRemoved(int count) {
        if (count > 0) {
            removed.addAndGet(count);
        }
    }

    public int getRemoved() {
        return removed.get();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
