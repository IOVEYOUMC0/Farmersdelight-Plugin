package com.huidu.farmersdelight.api.event;

import com.huidu.farmersdelight.api.block.FarmersDelightStation;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player placed one of the four built-in stations. Fired from the CraftEngine block-place path after the block
 * exists on the region thread owning it; the placement has already happened, so this reports it rather than
 * asking to veto it.
 */
@ApiStatus.NonExtendable
public class FarmersDelightStationPlacedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final FarmersDelightStation station;
    private final Location location;
    private final ItemStack item;

    public FarmersDelightStationPlacedEvent(Player player, FarmersDelightStation station,
                                            Location location, ItemStack item) {
        this.playerId = player == null ? null : player.getUniqueId();
        this.playerName = player == null ? null : player.getName();
        this.station = station;
        this.location = location == null ? null : location.clone();
        this.item = item == null ? null : item.clone();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public FarmersDelightStation getStation() {
        return station;
    }

    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    /** A copy of the placed item; editing it changes nothing. */
    public ItemStack getItem() {
        return item == null ? null : item.clone();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
