package com.huidu.farmersdelight.util;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cross-thread-safe viewer state for container inventories.
 *
 * CraftEngine's custom storage keeps viewers in a plain ObjectArrayList; reading that list from a
 * Folia region thread while the player thread opens/closes a GUI is a data race. The plugin therefore mirrors the
 * open state from the Bukkit inventory events into concurrent counters keyed by the stable InventoryHolder,
 * and the basket tick only reads this tracker. A quit releases every holder the player had open, so a missing
 * close event cannot leave a container permanently paused.
 *
 * The per-player map stores a count, not a set: a player can open the same holder twice (for example a
 * loot/reopen sequence), and each open must be balanced by exactly one close. A late close after quit is ignored,
 * so it cannot steal a slot from another viewer of the same holder.
 */
public final class InventoryViewerTracker implements Listener {

    private static final ConcurrentHashMap<InventoryHolder, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Map<InventoryHolder, Integer>> BY_PLAYER = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        trackOpen(event.getPlayer(), event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        trackClose(event.getPlayer(), event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer().getUniqueId());
    }

    /** True when the inventory or its holder is open; safe to call from a region thread. */
    public static boolean hasViewer(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder != null) {
            return hasViewer(holder);
        }
        return !inventory.getViewers().isEmpty();
    }

    static boolean hasViewer(InventoryHolder holder) {
        AtomicInteger count = COUNTS.get(holder);
        return count != null && count.get() > 0;
    }

    static void trackOpen(HumanEntity player, Inventory inventory) {
        if (player == null || inventory == null) {
            return;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder == null) {
            return;
        }
        COUNTS.computeIfAbsent(holder, ignored -> new AtomicInteger()).incrementAndGet();
        BY_PLAYER.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .merge(holder, 1, Integer::sum);
    }

    static void trackClose(HumanEntity player, Inventory inventory) {
        if (player == null || inventory == null) {
            return;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder == null) {
            return;
        }
        Map<InventoryHolder, Integer> held = BY_PLAYER.get(player.getUniqueId());
        if (held == null) {
            // Already released by quit (or never tracked): a late close must not decrement another viewer.
            return;
        }
        Integer remaining = held.get(holder);
        if (remaining == null) {
            return;
        }
        if (remaining <= 1) {
            held.remove(holder);
        } else {
            held.put(holder, remaining - 1);
        }
        if (held.isEmpty()) {
            BY_PLAYER.remove(player.getUniqueId(), held);
        }
        releaseHolder(holder);
    }

    static void release(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Map<InventoryHolder, Integer> held = BY_PLAYER.remove(playerId);
        if (held == null) {
            return;
        }
        for (Map.Entry<InventoryHolder, Integer> entry : held.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                releaseHolder(entry.getKey());
            }
        }
    }

    private static void releaseHolder(InventoryHolder holder) {
        COUNTS.computeIfPresent(holder, (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    public static void clear() {
        COUNTS.clear();
        BY_PLAYER.clear();
    }
}
