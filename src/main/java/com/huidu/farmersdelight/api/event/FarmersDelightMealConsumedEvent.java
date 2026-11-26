package com.huidu.farmersdelight.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A player consumed a FarmersDelight meal, snack, drink or sweet. Fired from the `PlayerItemConsumeEvent`
 * monitor on the player's thread, after the consume has been accepted, so it reports the finished meal rather
 * than asking to veto it. Use it for quests, statistics and cross-plugin food tracking; use
 * `PlayerItemConsumeEvent` itself when the consumption has to be cancellable.
 */
@ApiStatus.NonExtendable
public class FarmersDelightMealConsumedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @Nullable Entity actor;
    private final UUID playerId;
    private final String playerName;
    private final String itemId;
    private final ItemStack item;
    private final Location location;

    public FarmersDelightMealConsumedEvent(Player player, String itemId, ItemStack item) {
        this((Entity) player, itemId, item, true);
    }

    /** Actor-neutral factory used when a non-player entity finishes a meal. */
    public static FarmersDelightMealConsumedEvent forActor(@Nullable Entity actor, String itemId,
                                                            ItemStack item) {
        return new FarmersDelightMealConsumedEvent(actor, itemId, item, true);
    }

    private FarmersDelightMealConsumedEvent(@Nullable Entity actor, String itemId, ItemStack item,
                                            boolean ignored) {
        this.actor = actor;
        Player player = actor instanceof Player p ? p : null;
        this.playerId = player == null ? null : player.getUniqueId();
        this.playerName = player == null ? null : player.getName();
        this.itemId = itemId;
        this.item = item == null ? null : item.clone();
        this.location = actor == null ? null : actor.getLocation().clone();
    }

    /** The consuming entity; may be a non-player actor. */
    public @Nullable Entity getActor() {
        return actor;
    }

    /** The actor UUID, or null for a non-entity source. */
    public @Nullable UUID getActorId() {
        return actor == null ? null : actor.getUniqueId();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    /** The consumed item's CraftEngine id, or `minecraft:<material>` for a vanilla item. */
    public String getItemId() {
        return itemId;
    }

    /** A copy of the consumed stack; editing it changes nothing. */
    public ItemStack getItem() {
        return item == null ? null : item.clone();
    }

    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
