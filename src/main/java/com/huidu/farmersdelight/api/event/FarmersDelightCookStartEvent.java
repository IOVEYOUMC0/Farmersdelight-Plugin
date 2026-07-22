package com.huidu.farmersdelight.api.event;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Fired once when a cooking pot goes from holding no matched recipe to holding one — the moment the
 * pot starts working on a batch. Its counterpart at the other end of the batch is
 * FarmersDelightProduceEvent, fired when a player takes the finished meal.
 *
 * This is deliberately an edge, not a state. It fires on the idle-to-cooking transition only: while
 * the pot keeps cooking the same batch the recipe stays matched and nothing is fired, so a pot left
 * bubbling for a thousand ticks produces exactly one event. Swapping ingredients from one valid
 * recipe straight to another valid recipe is a recipe change rather than a start, and is not
 * reported; the pot must first fall idle (no match) for the next match to count as a start. Finishing
 * a batch clears the match, so a pot with enough ingredients for a second batch fires again for that
 * batch.
 *
 * Not cancellable — by the time the transition is observable the pot has already matched. Fired on
 * the region thread that owns the pot, outside the pot's block-entity lock, so a listener may read
 * and even mutate the pot without deadlocking. Reading it through
 * com.huidu.farmersdelight.api.block.FarmersDelightBlocks.cookingPot is the supported route.
 */
@ApiStatus.NonExtendable
public class FarmersDelightCookStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location location;
    private final String recipeId;
    private final ItemStack result;
    private final int cookTimeTicks;

    public FarmersDelightCookStartEvent(Location location, String recipeId, ItemStack result, int cookTimeTicks) {
        this.location = location == null ? null : location.clone();
        this.recipeId = recipeId;
        this.result = result == null ? null : result.clone();
        this.cookTimeTicks = cookTimeTicks;
    }

    /** The cooking pot's block location (block-aligned corner); null only if the pot has no world yet. */
    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    /** The id of the recipe the pot just started, e.g. farmersdelight:beef_stew. */
    public String getRecipeId() {
        return recipeId;
    }

    /** A copy of what the recipe will produce. */
    public ItemStack getResult() {
        return result == null ? null : result.clone();
    }

    /** Ticks the recipe needs from empty progress. The pot only advances while it is heated, so the
     *  wall-clock time to completion is at least this and usually more. */
    public int getCookTimeTicks() {
        return cookTimeTicks;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
