package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.GuiListenerRegistrar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

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
        InventoryHolder holder = event.getInventory().getHolder();
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
        if (holder instanceof RecipeEditorView editor) {
            event.setCancelled(true);
            int raw = event.getRawSlot();
            boolean top = event.getClickedInventory() == event.getInventory();
            if (top) {
                if (editor.isEditableSlot(raw)) {
                    ItemStack cursor = event.getCursor();
                    if (cursor == null || cursor.getType().isAir()) {
                        event.getInventory().setItem(raw, null);
                    } else {
                        ItemStack template = cursor.clone();
                        template.setAmount(1);
                        event.getInventory().setItem(raw, template);
                    }
                } else {
                    editor.handleButton(player, raw, event.isRightClick());
                }
            } else {
                // Clicking the player's own inventory: copy item to cursor, or discard a held template.
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir()) {
                    player.setItemOnCursor(null);
                } else {
                    ItemStack current = event.getCurrentItem();
                    if (current != null && !current.getType().isAir()) {
                        ItemStack copy = current.clone();
                        copy.setAmount(1);
                        player.setItemOnCursor(copy);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        // Both GUIs operate only on copies. Cancel every drag so fabricated cursor items cannot enter
        // real inventory slots.
        if (holder instanceof RecipeBookGui || holder instanceof RecipeEditorView) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof RecipeBookGui book) {
            // Release the progress-bar tick callback (ignored on a navigation close; see RecipeBookGui.onClose).
            book.onClose(event.getInventory());
        } else if (holder instanceof RecipeEditorView
                && event.getPlayer() instanceof Player player) {
            // Discard any template copy left on the cursor (it was never a real item).
            player.setItemOnCursor(null);
        }
    }
}
