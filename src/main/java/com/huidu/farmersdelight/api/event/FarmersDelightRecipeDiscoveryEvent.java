package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class FarmersDelightRecipeDiscoveryEvent extends Event {

    public enum Action {
        UNLOCK,
        LOCK
    }

    public enum Source {
        OBTAIN,
        API,
        COMMAND
    }

    private static final HandlerList HANDLERS = new HandlerList();

    // Every field is an immutable type (UUID, String, enum), so the accessors hand out the field itself;
    // there is no mutable state a listener could reach through and change.
    private final UUID playerId;
    private final String typeId;
    private final String recipeId;
    private final Action action;
    private final Source source;

    public FarmersDelightRecipeDiscoveryEvent(UUID playerId, String typeId, String recipeId,
                                              Action action, Source source) {
        this.playerId = playerId;
        this.typeId = typeId;
        this.recipeId = recipeId;
        this.action = action;
        this.source = source;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getTypeId() {
        return typeId;
    }

    public String getRecipeId() {
        return recipeId;
    }

    public Action getAction() {
        return action;
    }

    public Source getSource() {
        return source;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
