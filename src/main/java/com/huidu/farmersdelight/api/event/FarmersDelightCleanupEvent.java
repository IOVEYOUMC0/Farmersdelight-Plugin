package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicInteger;

@ApiStatus.NonExtendable
public class FarmersDelightCleanupEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AtomicInteger removed = new AtomicInteger();

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
