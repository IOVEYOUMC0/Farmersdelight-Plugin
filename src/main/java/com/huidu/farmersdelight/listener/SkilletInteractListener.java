package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.InteractionDebouncer;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class SkilletInteractListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteractProbe(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        String ceBlockId = CustomBlockUtils.getId(block);
        if (!shouldLogAttempt(block, ceBlockId)) {
            return;
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !plugin.isDebugEnabled("interact-probe")) {
            return;
        }

        Bukkit.getLogger().info("=== FD DEBUG PROBE ===");
        Bukkit.getLogger().info("Stage: LOWEST");
        Bukkit.getLogger().info("Use item: " + event.useItemInHand());
        Bukkit.getLogger().info("Use block: " + event.useInteractedBlock());
        Bukkit.getLogger().info("Hand: " + event.getHand());
        Bukkit.getLogger().info("Action: " + event.getAction());
        Bukkit.getLogger().info("Clicked block: " + block.getType());
        Bukkit.getLogger().info("CE block id: " + ceBlockId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        String ceBlockId = CustomBlockUtils.getId(block);
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();

        if (shouldLogAttempt(block, ceBlockId) && plugin.isDebugEnabled("skillet")) {
            logDebug(player, block, ceBlockId, "farmersdelight:skillet", mainHand, plugin.getSkilletManager().findRecipeId(mainHand));
        }

        if (!isSkilletBlock(ceBlockId)) {
            return;
        }

        if (player.isSneaking() && isSkilletItem(mainHand)) {
            return;
        }

        if (!player.hasPermission("farmersdelight.use.skillet")) {
            player.sendActionBar(I18n.getComponent("general.no_permission", player));
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
            return;
        }

        if (mainHand != null && !mainHand.getType().isAir()
                && plugin.getSkilletManager().handleInteract(player, block, mainHand, EquipmentSlot.HAND)) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
            player.updateInventory();
            return;
        }

        if ((mainHand == null || mainHand.getType().isAir())
                && InteractionDebouncer.tryAcquire(player.getUniqueId(), block.getLocation())
                && plugin.getSkilletManager().handleInteract(player, block, mainHand, EquipmentSlot.HAND)) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
            player.updateInventory();
        }
    }

    private boolean isSkilletItem(ItemStack itemStack) {
        return itemStack != null && "farmersdelight:skillet".equals(ItemUtils.getCustomItemId(itemStack));
    }

    private void logDebug(Player player, Block clickedBlock, String ceBlockId, String behaviorId, ItemStack item, String recipeId) {
        String resolvedItemId = resolveItemId(item);
        Bukkit.getLogger().info("=== FD DEBUG ===");
        Bukkit.getLogger().info("Player: " + player.getName());
        Bukkit.getLogger().info("Clicked block: " + clickedBlock.getType());
        Bukkit.getLogger().info("CE block id: " + ceBlockId);
        Bukkit.getLogger().info("Behavior: " + behaviorId);
        Material itemType = Material.AIR;
        if (item != null) {
            itemType = item.getType();
        }
        Bukkit.getLogger().info("Item: " + itemType);
        Bukkit.getLogger().info("Item id: " + resolvedItemId);
        Bukkit.getLogger().info("Recipe found: " + recipeId);
    }

    private String resolveItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "minecraft:air";
        }

        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId != null) {
            return customItemId;
        }
        return "minecraft:" + item.getType().name().toLowerCase();
    }

    private boolean isSkilletBlock(String ceBlockId) {
        return ceBlockId != null && ceBlockId.contains("skillet");
    }

    private boolean shouldLogAttempt(Block block, String ceBlockId) {
        if (isSkilletBlock(ceBlockId)) {
            return true;
        }
        if (block == null) {
            return false;
        }
        return block.getType() == Material.TRIPWIRE;
    }
}
