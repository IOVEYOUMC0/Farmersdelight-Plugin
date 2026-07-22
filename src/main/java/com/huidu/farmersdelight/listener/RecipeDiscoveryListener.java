package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.FarmersDelightProduceEvent;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Drives the recipe-discovery "obtain" trigger: when a player picks up an item, or a station hands one
 * straight to them, recipes keyed to that item (their result or an exact ingredient) unlock. Both handlers
 * early-out on the manager's feature switch before doing any work, and the trigger switch plus the index
 * lookup live in RecipeDiscoveryManager, so this listener stays cheap when discovery is off.
 *
 * Two triggers are needed because they cover disjoint routes. A pickup covers anything that lands on the
 * ground first — notably cutting-board output, which is dropped as an entity and never produces a
 * FarmersDelightProduceEvent. The produce event covers meals taken straight out of a station's GUI into the
 * inventory (cooking pot, addon kegs), which never fires a pickup. When a station's output does not fit and
 * falls to the ground both can run for the same item; that is harmless, because a repeated unlock is a
 * no-op and only genuinely new unlocks notify.
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProduce(FarmersDelightProduceEvent event) {
        // A null player id means automated extraction (hopper, addon logic): nobody to unlock for.
        UUID playerId = event.getPlayerId();
        if (playerId == null) {
            return;
        }
        RecipeDiscoveryManager manager = plugin.getRecipeDiscoveryManager();
        if (manager == null || !manager.isEnabled()) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        ItemStack result = event.getResult();
        if (result == null) {
            return;
        }
        // A station may fire this event while holding its own per-block lock (BrewinAndChewin's keg pours
        // inside its per-keg monitor). Doing the unlock inline would run this handler, the discovery event it
        // fires and every third-party listener of that event under a foreign plugin's lock. Only the cheap
        // gates above run on the firing thread; the rest is handed to the player's own scheduler, which lands
        // on the player's region thread with no foreign lock held and is Folia-safe. The result stack is
        // already a private clone handed out by the event, so it is safe to read later.
        player.getScheduler().run(plugin, task -> {
            String itemId = ItemUtils.resolveItemId(result);
            if (itemId != null) {
                manager.onObtain(player, itemId);
            }
        }, null);
    }
}
