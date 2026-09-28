package com.huidu.farmersdelight.api.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Hands a stack back to a player and drops whatever does not fit. Every station that lets a player take an
 * item out (or that cancels a craft already paid for) needs the same "add, then drop the overflow" step, and
 * the drop point differs per station: at the player, at the block centre, or at a station-specific offset.
 * The caller owns the drop point, and Folia requires the drop to run on the thread that owns that location,
 * so these methods must be invoked from that region's own scheduler.
 */
public final class ItemDelivery {

    private ItemDelivery() {
    }

    /** Adds the stack to the player's inventory, dropping anything that does not fit at the player. */
    public static void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    /** Adds the stack to the player's inventory, dropping anything that does not fit at the given point. */
    public static void giveOrDrop(Player player, Location dropAt, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            dropAt.getWorld().dropItemNaturally(dropAt, leftover);
        }
    }
}
