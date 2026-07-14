package com.huidu.farmersdelight.gui.editor;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Shared contract for all recipe editor GUIs (and their picker sub-GUIs). The GUI instance is its own
 * InventoryHolder, so RecipeEditorListener can route raw inventory events to it by checking
 * the top inventory's holder.
 *
 * Editor GUIs never move real items: every InventoryClickEvent is cancelled, and slot
 * contents are only ever set programmatically to copies, so the player's inventory is never consumed or lost (a "copy on click" model).
 */
public interface EditorGui extends InventoryHolder {

    void handleClick(InventoryClickEvent event);

    default void handleDrag(InventoryDragEvent event) {
        event.setCancelled(true);
    }

    default void handleClose(InventoryCloseEvent event) {
    }
}
