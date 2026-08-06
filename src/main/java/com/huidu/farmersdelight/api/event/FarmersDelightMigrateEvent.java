package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicInteger;

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
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
