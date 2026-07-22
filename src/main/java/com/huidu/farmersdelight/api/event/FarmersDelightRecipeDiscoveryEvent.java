package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a player's recipe-discovery state really changes: a locked recipe becomes unlocked, or an
 * unlocked one is re-locked. Redundant calls (unlocking what is already unlocked, locking what is already
 * locked) fire nothing, so a listener sees exactly one event per transition. Unlocking or locking a whole
 * type at once fires one event per recipe that actually moved.
 *
 * A notification hook for addons (stats, quests, sounds, cross-plugin integrations); not cancellable — the
 * state is already committed when listeners run. A veto belongs upstream, before the unlock is requested.
 *
 * Threading: dispatched on whichever thread performed the change, with none of the discovery manager's
 * locks held. That is the region thread owning the player for the obtain trigger, the command thread for
 * the admin command, and the calling thread for the API. It is an ordinary synchronous Bukkit event, so
 * API callers must request unlocks from a server thread; Bukkit rejects synchronous dispatch from an
 * asynchronous one. Listeners should treat it as region-local: act on the named player, not on unrelated
 * world state.
 *
 * The player named by the event may be offline — the command path can change stored state for a player who
 * is not on the server.
 */
public class FarmersDelightRecipeDiscoveryEvent extends Event {

    /** Which way the discovery state moved. */
    public enum Action {
        UNLOCK,
        LOCK
    }

    /** What drove the change. */
    public enum Source {
        /** The obtain trigger: the player picked up, or was handed by a station, a result or exact ingredient. */
        OBTAIN,
        /** A plugin called the FarmersDelightRecipeDiscovery API. */
        API,
        /** An operator ran the discovery admin command. */
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

    /** The player whose discovery state changed. */
    public UUID getPlayerId() {
        return playerId;
    }

    /** The recipe type id, e.g. farmersdelight:cooking_pot, or an addon's RecipeType id. */
    public String getTypeId() {
        return typeId;
    }

    /** The recipe id within its type. */
    public String getRecipeId() {
        return recipeId;
    }

    /** Whether the recipe was unlocked or re-locked. */
    public Action getAction() {
        return action;
    }

    /** Where the change came from. */
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
