package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.api.util.PerformanceProbe;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.GuiListenerRegistrar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

public final class RecipeBookListener implements Listener {

    public static void ensureRegistered() {
        GuiListenerRegistrar.ensureRegistered(RecipeBookListener.class, RecipeBookListener::new,
                FarmersDelightPlugin.getInstance());
    }

    public static void reset() {
        GuiListenerRegistrar.reset(RecipeBookListener.class);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = PerformanceProbe.holder(event.getInventory());
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (holder instanceof RecipeBookGui book) {
            event.setCancelled(true);
            if (event.getClickedInventory() == event.getInventory()) {
                book.handleClick(player, event.getRawSlot(), event.isShiftClick());
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = PerformanceProbe.holder(event.getInventory());
        // Both GUIs operate only on copies. Cancel every drag so fabricated cursor items cannot enter
        // real inventory slots.
        if (holder instanceof RecipeBookGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = PerformanceProbe.holder(event.getInventory());
        if (holder instanceof RecipeBookGui book) {
            // Release the progress-bar tick callback (ignored on a navigation close; see RecipeBookGui.onClose).
            book.onClose(event.getInventory());
        }
    }
}
