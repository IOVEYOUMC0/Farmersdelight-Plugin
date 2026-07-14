package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired once CraftEngine items are ready (on startup and after each CE reload), after FarmersDelight has
 * warmed its own item / GUI / recipe caches. Addons listen to pre-build their own CraftEngine item stacks
 * and prime their caches off the hot path, so the first in-game interaction does not pay lazy-init cost.
 *
 * <p>Handlers must do pure computation only (build item stacks, prime caches). They run on the global/main
 * thread and must not touch worlds, entities, regions, or real block state.
 */
public class FarmersDelightWarmupEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String reason;

    public FarmersDelightWarmupEvent(String reason) {
        this.reason = reason;
    }

    /** A short identifier for what triggered the warmup — "enable" or "reload" (may be null). */
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
