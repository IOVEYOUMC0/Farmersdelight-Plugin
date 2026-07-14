package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.TrayManager;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
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
}

