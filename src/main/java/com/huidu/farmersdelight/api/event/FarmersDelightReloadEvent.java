package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after FarmersDelight finishes a full reload (configs, recipes, language). Addons can listen to
 * re-read their own configuration in step with the main plugin.
 */
public class FarmersDelightReloadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String reason;

    public FarmersDelightReloadEvent(String reason) {
        this.reason = reason;
    }

    /** A short identifier for what triggered the reload (may be null). */
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
