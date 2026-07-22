package com.huidu.farmersdelight.api.event;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/**
 * Fired when a FarmersDelight station hands a produced item to a player (e.g. a cooking pot meal taken).
 * A notification hook for addons (stats, quests, integrations); not cancellable — the item is already produced.
 */
@ApiStatus.NonExtendable
public class FarmersDelightProduceEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String source;
    private final ItemStack result;
    private final Location location;

    public FarmersDelightProduceEvent(UUID playerId, String source, ItemStack result, Location location) {
        this.playerId = playerId;
        this.source = source;
        this.result = result == null ? null : result.clone();
        this.location = location == null ? null : location.clone();
    }

    /** The player who took the item (may be null for automated extraction). */
    public UUID getPlayerId() {
        return playerId;
    }

    /** The producing station, e.g. "cooking_pot" or an addon's id like "keg". */
    public String getSource() {
        return source;
    }

    public ItemStack getResult() {
        return result == null ? null : result.clone();
    }

    /** The station's block location (may be null). */
    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
