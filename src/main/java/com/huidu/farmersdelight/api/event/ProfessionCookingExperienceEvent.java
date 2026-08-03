package com.huidu.farmersdelight.api.event;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/**
 * Fired when a FarmersDelight station credits a player with cooking experience for a produced item.
 * A notification hook for profession / skill / economy plugins; not cancellable — the experience has
 * already been decided by the time this fires.
 */
@ApiStatus.NonExtendable
public class ProfessionCookingExperienceEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final String source;
    private final ItemStack result;
    private final float baseExperience;
    private final Location location;

    /**
     * Constructor without a station location, kept so callers compiled against earlier builds keep
     * compiling and linking. Delegates with a null location, which is what getLocation reported
     * before the field existed.
     */
    public ProfessionCookingExperienceEvent(UUID playerId, String playerName, String source, ItemStack result, float baseExperience) {
        this(playerId, playerName, source, result, baseExperience, null);
    }

    /**
     * playerId the credited player's unique id
     * playerName the credited player's name (may be null)
     * source the station: "cooking_pot", "skillet", "stove", "cutting_board", or an
     *                       addon's own id
     * result what was produced; cloned in and out
     * baseExperience the experience the station is crediting, before any config multiplier
     * location the station's block location; cloned in and out, null when the caller has
     *                       no block context
     */
    public ProfessionCookingExperienceEvent(UUID playerId, String playerName, String source, ItemStack result,
                                            float baseExperience, Location location) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.source = source;
        this.result = result == null ? null : result.clone();
        this.baseExperience = baseExperience;
        this.location = location == null ? null : location.clone();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getSource() {
        return source;
    }

    public ItemStack getResult() {
        return result == null ? null : result.clone();
    }

    public float getBaseExperience() {
        return baseExperience;
    }

    /**
     * The station's block location, or null when the caller supplied none — an addon still using the
     * five-argument constructor, or a station crediting experience with no block context. Mirrors how
     * FarmersDelightProduceEvent reports the producing station.
     */
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
