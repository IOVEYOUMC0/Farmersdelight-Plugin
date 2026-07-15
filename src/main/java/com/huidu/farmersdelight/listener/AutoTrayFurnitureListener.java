package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.TrayManager;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class AutoTrayFurnitureListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public AutoTrayFurnitureListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        TrayManager trayManager = plugin.getTrayManager();
        if (trayManager == null) {
            return;
        }
        if (!trayManager.isAutoPlacedTray(event.furniture())) {
            return;
        }

        event.setDropItems(false);
        event.setCancelled(true);
    }

    // A player placing a tray by hand fires this (the auto tray is placed programmatically and does not), so
    // stamp it as a manual tray: the cooking pot/skillet then never reclaim, absorb, or remove it, and it can
    // be broken/retrieved normally.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        TrayManager trayManager = plugin.getTrayManager();
        if (trayManager != null) {
            trayManager.markManualTrayFurniture(event.furniture());
        }
    }
}

