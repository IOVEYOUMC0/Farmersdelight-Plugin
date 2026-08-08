package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceBurnEvent;

import java.util.Map;

public final class FurnaceFuelListener implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        WorldDataConfig config = WorldDataConfig.get();
        if (config.isFuelEnabled()) {
            return;
        }
        Integer ticks = config.fuelTime(ItemUtils.resolveItemId(event.getFuel()));
        if (ticks == null || ticks <= 0) {
            return;
        }
        // Runs after CraftEngine's own HIGH handler so the configured value is the one that survives, and it
        // also corrects items whose base material carries a vanilla burn time of its own.
        event.setBurnTime(ticks);
    }

    @EventHandler
    public void onWarmup(FarmersDelightWarmupEvent event) {
        applyFuelTimesToCraftEngine();
    }

    static void applyFuelTimesToCraftEngine() {
        WorldDataConfig config = WorldDataConfig.get();
        if (config.isFuelEnabled() || config.fuelTimes().isEmpty()) {
            return;
        }
        if (!ItemUtils.isAnyCustomItemLoaded()) {
            return;
        }
        try {
            for (Map.Entry<String, Integer> entry : config.fuelTimes().entrySet()) {
                BukkitItemDefinition definition = CraftEngineItems.byId(entry.getKey());
                if (definition == null) {
                    continue;
                }
                definition.settings().fuelTime(entry.getValue());
            }
        } catch (Throwable ignored) {
            // Fuel slot insertion falls back to the vanilla predicate; FurnaceBurnEvent still applies the time.
        }
    }
}
