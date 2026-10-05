package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.SkilletManager;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/** Lifecycle cleanup for portable skillet state; interaction itself belongs to the CE ItemBehavior. */
public final class SkilletLifecycleListener implements Listener {

    // Handed in by the registrar instead of looked up: this handler only runs while the plugin is enabled.
    private final FarmersDelightPlugin plugin;

    public SkilletLifecycleListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        SkilletManager manager = manager();
        if (manager != null && manager.isHandheldCooking(event.getPlayer())) {
            // The event item is a snapshot, never an inventory source to refund.
            manager.stopHandheldUse(event.getPlayer(), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHotbarChange(PlayerItemHeldEvent event) {
        SkilletManager manager = manager();
        if (manager != null) {
            ItemStack previous = event.getPlayer().getInventory().getItem(event.getPreviousSlot());
            manager.stopHandheldUse(event.getPlayer(), previous);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        SkilletManager manager = manager();
        if (manager != null) manager.stopHandheldUse(event.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        SkilletManager manager = manager();
        if (manager != null) manager.stopHandheldUse(event.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(PlayerItemDamageEvent event) {
        SkilletManager manager = manager();
        if (manager != null) manager.stopHandheldUse(event.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent event) {
        // Instant consumables can finish between cooking ticks. Leave their native debit intact.
        stop(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) stop(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeftClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            stop(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        SkilletManager manager = manager();
        if (manager != null) manager.stopHandheldUse(event.getEntity(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) stop(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) stop(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        SkilletManager manager = manager();
        if (manager != null && manager.isHandheldCooking(player)) {
            // Creative packets carry a client stack; do not persist the display-only damage/model.
            event.setCancelled(true);
            manager.stopHandheldUse(player, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) stop(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        SkilletManager manager = manager();
        if (manager == null) return;
        for (ItemStack item : event.getPlayer().getInventory().getContents()) {
            if (manager.hasHandheldIngredient(item)) manager.stopHandheldUse(event.getPlayer(), item);
        }
    }

    private void stop(Player player) {
        SkilletManager manager = manager();
        if (manager != null && manager.isHandheldCooking(player)) manager.stopHandheldUse(player, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        SkilletManager manager = manager();
        if (manager != null) {
            manager.stopHandheldUse(event.getPlayer(), null);
        }
    }

    private SkilletManager manager() {
        return plugin == null ? null : plugin.getSkilletManager();
    }
}
