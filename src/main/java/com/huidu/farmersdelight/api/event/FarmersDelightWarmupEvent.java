package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
public class FarmersDelightWarmupEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String reason;

    public FarmersDelightWarmupEvent(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
