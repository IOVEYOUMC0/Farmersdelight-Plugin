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

/**
 * Gives the mod's fuel items their furnace burn times.
 *
 * Two things have to line up for a custom item to work as fuel. Lighting the furnace is the easy half:
 * AbstractFurnaceBlockEntity.serverTick fires FurnaceBurnEvent whenever the fuel slot and the input slot are
 * both occupied, without ever consulting the fuel registry, and assigns litTime straight from the event, so
 * setBurnTime here lights the furnace for exactly the configured number of ticks no matter what the item's
 * base material is. Getting the stack into the fuel slot is the other half, and there vanilla does consult the
 * registry: AbstractFurnaceBlockEntity.canPlaceItem and the menu's fuel slot both reject an item whose
 * material is not a registered fuel. CraftEngine already works around that in its own inventory click handler,
 * but only for items whose fuel-time item setting is non-zero, so the burn times are also pushed into the
 * CraftEngine item definitions here. Hopper and dropper insertion stays on the vanilla predicate and is not
 * reachable for these items.
 */
public final class FurnaceFuelListener implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        WorldDataConfig config = WorldDataConfig.get();
        if (!config.isFuelEnabled()) {
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

    /**
     * The warmup fires once CraftEngine has finished loading items, both at enable and after every CraftEngine
     * reload, which is exactly when the item definitions exist and their settings have been rebuilt from YAML.
     */
    @EventHandler
    public void onWarmup(FarmersDelightWarmupEvent event) {
        applyFuelTimesToCraftEngine();
    }

    /**
     * Writes the configured burn times onto the CraftEngine item definitions, which is what lets a player move
     * the stack into a furnace's fuel slot at all. Best effort: the item settings are CraftEngine internals
     * rather than its stable bukkit api, so a signature drift degrades to "the item still burns once it is in
     * the slot" instead of breaking enable or reload.
     */
    static void applyFuelTimesToCraftEngine() {
        WorldDataConfig config = WorldDataConfig.get();
        if (!config.isFuelEnabled() || config.fuelTimes().isEmpty()) {
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
