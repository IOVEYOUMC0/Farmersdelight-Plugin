package com.huidu.farmersdelight.api.event;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

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

    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    public String getRecipeId() {
        return recipeId;
    }

    public ItemStack getResult() {
        return result == null ? null : result.clone();
    }

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
