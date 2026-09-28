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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class RecipeDiscoveryListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public RecipeDiscoveryListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    // Unlocks live in memory only while their owner is online. Reading them in on join keeps the first
    // book open and the first pickup off the disk; dropping them on quit is what stops the table growing
    // with every player who has ever joined.

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        RecipeDiscoveryManager manager = plugin.getRecipeDiscoveryManager();
        if (manager == null || !manager.isEnabled()) {
            return;
        }
        // Async because reading one player's unlocks means parsing the whole shared file, which grows with
        // every player the server has ever had. A book opened before this lands reads them in on the spot
        // instead; the warm-up is what keeps that from being the normal case.
        UUID playerId = event.getPlayer().getUniqueId();
        long version = manager.markJoin(playerId);
        plugin.scheduler().runAsync(() -> manager.ensureLoaded(playerId, version));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        RecipeDiscoveryManager manager = plugin.getRecipeDiscoveryManager();
        if (manager == null) {
            return;
        }
        // Async because the flush that has to happen before the drop writes the whole file, and a quit is
        // not the place for that. Order is kept inside evict, and a rejoin that beats the task just reads
        // the player back off the file the task has already written.
        UUID playerId = event.getPlayer().getUniqueId();
        long version = manager.markQuit(playerId);
        plugin.scheduler().runAsync(() -> manager.evict(playerId, version));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        RecipeDiscoveryManager manager = plugin.getRecipeDiscoveryManager();
        // Both gates before resolving an item id: with the obtain trigger off, onObtain would drop the id
        // on the floor, and this runs for every item every player picks up.
        if (manager == null || !manager.isEnabled() || !manager.isUnlockOnObtain()) {
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
        if (manager == null || !manager.isEnabled() || !manager.isUnlockOnObtain()) {
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
