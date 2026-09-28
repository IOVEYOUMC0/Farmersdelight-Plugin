package com.huidu.farmersdelight.api.util;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.HashSet;
import java.util.Set;

/**
 * Central guard against container-nesting NBT bombs. A container whose inventory is packed into its
 * own NBT when mined (shulker box, bundle, cooking pot, tackle box, ...) must never be placed inside
 * another such container: each nesting level re-serializes the contents recursively and the NBT grows
 * without bound. Only containers that keep their contents on the mined item belong here; containers
 * that spill their items on break (cutting board, skillet, stove, basket, ...) carry no nested NBT
 * and are intentionally excluded. Addons whose mined containers preserve their contents register their
 * item ids once at startup, so every FD-family container GUI can refuse them uniformly.
 */
public final class NestingGuard {

    // FD-family containers that pack their contents into the mined item's NBT.
    private static volatile Set<String> REGISTERED = Set.of("farmersdelight:cooking_pot");

    private NestingGuard() {
    }

    /** Registers a FarmersDelight-family container item whose mined form packs its inventory into NBT. */
    public static void register(String containerId) {
        if (containerId == null || containerId.isEmpty()) {
            return;
        }
        synchronized (NestingGuard.class) {
            Set<String> next = new HashSet<>(REGISTERED);
            next.add(containerId);
            REGISTERED = Set.copyOf(next);
        }
    }

    public static boolean isContainerNestingHazard(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        var meta = item.getItemMeta();
        // Only containers that keep their contents on the mined item count: shulker boxes, bundles and
        // the registered FD-family containers. Plain containers (chest, furnace, ...) spill their items
        // on break and carry no nested NBT, so they are intentionally excluded.
        if (meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof ShulkerBox) {
            return true;
        }
        if (meta instanceof BundleMeta) {
            return true;
        }
        Key customId = CraftEngineItems.getCustomItemId(item);
        return customId != null && REGISTERED.contains(customId.toString());
    }
}
