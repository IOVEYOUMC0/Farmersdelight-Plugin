package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 单个 Bukkit 监听器，将物品栏事件路由到拥有当前打开的顶部物品栏的 EditorGui。
 * 仅延迟注册一次（参照 RecipeViewGui 的分发器模式）。
 */
public final class RecipeEditorListener implements Listener {

    private static volatile boolean registered = false;

    private RecipeEditorListener() {
    }

    public static void ensureRegistered(FarmersDelightPlugin plugin) {
        if (registered) {
            return;
        }
        synchronized (RecipeEditorListener.class) {
            if (registered) {
                return;
            }
            Bukkit.getPluginManager().registerEvents(new RecipeEditorListener(), plugin);
            registered = true;
        }
    }

    /** 重置状态，以便软重新启用时重新注册一个新的监听器。 */
    public static void reset() {
        registered = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleDrag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleClose(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof EditorGui) {
            event.getPlayer().setItemOnCursor(null);
        }
    }
}
