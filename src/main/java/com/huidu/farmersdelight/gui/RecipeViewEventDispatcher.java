package com.huidu.farmersdelight.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Routes inventory events to the RecipeViewGui that owns the open top inventory, and closes a player's
 * GUI on quit. A single shared instance is registered once via RecipeViewGui.ensureListenerRegistered.
 * Extracted from RecipeViewGui so the dispatch wiring is a focused top-level class.
 */
public final class RecipeViewEventDispatcher implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RecipeViewGui gui) {
            gui.onClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RecipeViewGui gui) {
            gui.onDrag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RecipeViewGui gui) {
            gui.onClose(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        RecipeViewGui gui = RecipeViewGui.removeActiveGui(event.getPlayer().getUniqueId());
        if (gui != null && !gui.closed) {
            gui.close();
        }
    }
}
