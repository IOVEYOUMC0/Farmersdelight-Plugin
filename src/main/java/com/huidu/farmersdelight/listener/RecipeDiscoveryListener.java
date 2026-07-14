package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Drives the recipe-discovery "obtain" trigger: when a player picks up an item, recipes keyed to that item
 * (their result or an exact ingredient) unlock. All gating (feature enabled, trigger enabled) lives in the
 * RecipeDiscoveryManager, so this listener stays cheap when discovery is off.
 */
public class RecipeDiscoveryListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public RecipeDiscoveryListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        RecipeDiscoveryManager manager = plugin.getRecipeDiscoveryManager();
        if (manager == null || !manager.isEnabled()) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        String itemId = ItemUtils.resolveItemId(stack);
        if (itemId != null) {
            manager.onObtain(player, itemId);
        }
    }
}
