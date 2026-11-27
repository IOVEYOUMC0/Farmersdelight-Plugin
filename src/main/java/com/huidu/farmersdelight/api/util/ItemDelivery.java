package com.huidu.farmersdelight.api.util;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * Hands a stack back to a player and drops whatever does not fit. Every station that lets a player take an
 * item out (or that cancels a craft already paid for) needs the same "add, then drop the overflow" step, and
 * the drop point differs per station: at the player, at the block centre, or at a station-specific offset.
 * The caller owns the drop point, and Folia requires the drop to run on the thread that owns that location,
 * so these methods must be invoked from that region's own scheduler. A drop refused by another plugin is
 * reported once per player.
 */
public final class ItemDelivery {

    // A plugin that vetoes item spawns turns every full-inventory hand-back into the same lost item; report once
    // per player per cooldown instead of on every attempt.
    private static final WarningThrottle REJECTED_DROPS = new WarningThrottle();

    private ItemDelivery() {
    }

    /** Adds the stack to the player's inventory, dropping anything that does not fit at the player. */
    public static void giveOrDrop(Player player, ItemStack item) {
        give(player, item, leftover -> deliverOrWarn(player, player.getLocation(), leftover));
    }

    /** Adds the stack to the player's inventory, dropping anything that does not fit at the given point. */
    public static void giveOrDrop(Player player, Location dropAt, ItemStack item) {
        Location at = dropAt == null ? player.getLocation() : dropAt;
        give(player, item, leftover -> deliverOrWarn(player, at, leftover));
    }

    private static void give(Player player, ItemStack item, Consumer<ItemStack> overflow) {
        if (player == null || item == null) {
            return;
        }
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            if (leftover != null && !leftover.isEmpty()) {
                overflow.accept(leftover);
            }
        }
    }

    /**
     * Drops one overflow stack and reports whether it actually entered the world.
     *
     * Paper returns an entity wrapper even when another plugin cancels ItemSpawnEvent, so the return value of
     * dropItemNaturally alone does not prove delivery.
     */
    static boolean dropVerified(World world, Location at, ItemStack item) {
        if (world == null || at == null || item == null || item.isEmpty()) {
            return false;
        }
        Item[] created = new Item[1];
        Item dropped;
        try {
            dropped = world.dropItemNaturally(at, item, entity -> created[0] = entity);
        } catch (RuntimeException | LinkageError failure) {
            if (created[0] != null && created[0].isValid()) {
                return true;
            }
            throw failure;
        }
        return dropped != null && dropped.isValid();
    }

    private static void deliverOrWarn(Player player, Location at, ItemStack leftover) {
        boolean delivered;
        try {
            delivered = dropVerified(at == null ? null : at.getWorld(), at, leftover);
        } catch (RuntimeException | LinkageError failure) {
            delivered = false;
            Bukkit.getLogger().warning(FarmersDelightApi.consoleMessage("plugin.item_delivery_failed",
                    "player", player.getName(), "pos", String.valueOf(at), "error", String.valueOf(failure)));
        }
        if (!delivered && REJECTED_DROPS.allow("item-delivery", player.getUniqueId().toString())) {
            Bukkit.getLogger().warning(FarmersDelightApi.consoleMessage("plugin.item_delivery_refused",
                    "player", player.getName(), "pos", String.valueOf(at),
                    "item", leftover.getAmount() + "x " + leftover.getType()));
        }
    }
}
