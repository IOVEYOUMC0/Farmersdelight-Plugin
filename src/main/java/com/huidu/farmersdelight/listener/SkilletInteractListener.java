package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.InteractionDebouncer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.WorldGuardCompat;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
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

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !plugin.isDebugEnabled("interact-probe")) {
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

        Bukkit.getLogger().info(I18n.formatConsole("debug.probe_header"));
        logDebugField("debug.label_stage", "LOWEST");
        logDebugField("debug.label_use_item", event.useItemInHand());
        logDebugField("debug.label_use_block", event.useInteractedBlock());
        logDebugField("debug.label_hand", event.getHand());
        logDebugField("debug.label_action", event.getAction());
        logDebugField("debug.label_clicked_block", block.getType());
        logDebugField("debug.label_ce_block_id", ceBlockId);
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
        ImmutableBlockState state = CustomBlockUtils.getState(block);
        String ceBlockId = CustomBlockUtils.getId(state);
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();

        if (plugin.isDebugEnabled("skillet") && shouldLogAttempt(block, ceBlockId)) {
            logDebug(player, block, ceBlockId, "farmersdelight:skillet", mainHand, plugin.getSkilletManager().findRecipeId(mainHand));
        }

        if (!isSkilletBlock(state, ceBlockId)) {
            return;
        }
        if (!WorldGuardCompat.canUse(player, block) || !WorldGuardCompat.canBuild(player, block)) {
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
                && InteractionDebouncer.tryAcquire(player.getUniqueId(), block.getLocation())
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
        return itemStack != null && Constants.ITEM_SKILLET.equals(ItemUtils.getCustomItemId(itemStack));
    }

    private void logDebug(Player player, Block clickedBlock, String ceBlockId, String behaviorId, ItemStack item, String recipeId) {
        String resolvedItemId = resolveItemId(item);
        Bukkit.getLogger().info(I18n.formatConsole("debug.header"));
        logDebugField("debug.label_player", player.getName());
        logDebugField("debug.label_clicked_block", clickedBlock.getType());
        logDebugField("debug.label_ce_block_id", ceBlockId);
        logDebugField("debug.label_behavior", behaviorId);
        Material itemType = Material.AIR;
        if (item != null) {
            itemType = item.getType();
        }
        logDebugField("debug.label_item", itemType);
        logDebugField("debug.label_item_id", resolvedItemId);
        logDebugField("debug.label_recipe_found", recipeId);
    }

    private void logDebugField(String labelKey, Object value) {
        Bukkit.getLogger().info(I18n.formatConsole("debug.field",
                "label", I18n.formatConsole(labelKey),
                "value", value));
    }

    private String resolveItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "minecraft:air";
        }

        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId != null) {
            return customItemId;
        }
        return "minecraft:" + item.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isSkilletBlock(String ceBlockId) {
        return Constants.CE_SHORT_SKILLET.equals(ceBlockId);
    }

    private boolean isSkilletBlock(Block block) {
        return CustomBlockUtils.hasBehavior(block, SkilletBlockBehavior.class)
                || CustomBlockUtils.hasId(block, Constants.BLOCK_SKILLET)
                || Constants.CE_SHORT_SKILLET.equals(CustomBlockUtils.getId(block));
    }

    private boolean isSkilletBlock(ImmutableBlockState state, String ceBlockId) {
        return CustomBlockUtils.hasBehavior(state, SkilletBlockBehavior.class)
                || CustomBlockUtils.hasId(state, Constants.BLOCK_SKILLET)
                || Constants.CE_SHORT_SKILLET.equals(ceBlockId);
    }

    private boolean shouldLogAttempt(Block block, String ceBlockId) {
        if (isSkilletBlock(ceBlockId) || isSkilletBlock(block)) {
            return true;
        }
        if (block == null) {
            return false;
        }
        return block.getType() == Material.TRIPWIRE;
    }
}

