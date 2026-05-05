package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.item.CustomItem;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.GameMode;
import org.bukkit.Sound;
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

import java.util.List;
import java.util.Set;

public class CuttingBoardInteractListener implements Listener {

    private final List<Key> toolTags;

    public CuttingBoardInteractListener() {
        this.toolTags = FarmersDelightPlugin.getInstance()
                .getConfig()
                .getStringList("blocks.cutting-board.tool-tags")
                .stream()
                .map(tag -> tag != null && tag.startsWith("#") ? tag.substring(1) : tag)
                .map(Key::of)
                .toList();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSneakInsertTool(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }

        Block block = event.getClickedBlock();
        if (!CustomBlockUtils.hasId(block, Constants.BLOCK_CUTTING_BOARD)) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!isTool(mainHand)) {
            return;
        }

        BlockPosKey posKey = new BlockPosKey(block.getLocation());
        CuttingBoardBlockEntity blockEntity = CuttingBoardBlockBehavior.getBlockEntity(block.getWorld(), posKey);
        if (blockEntity != null && blockEntity.hasItem()) {
            return;
        }

        if (blockEntity == null) {
            blockEntity = new CuttingBoardBlockEntity(posKey, block.getWorld());
            CuttingBoardBlockBehavior.putBlockEntity(block.getWorld(), posKey, blockEntity);
        }

        // Shift-insert is the dedicated "stick the tool into the board" path.
        ItemStack itemToPlace = mainHand.clone();
        itemToPlace.setAmount(1);
        blockEntity.setItem(itemToPlace, block.getWorld(), posKey, CustomBlockUtils.getFacing(block), true);
        CuttingBoardBlockBehavior.markManualInsertion(block.getWorld(), posKey, player.getUniqueId());

        if (player.getGameMode() != GameMode.CREATIVE) {
            int newAmount = mainHand.getAmount() - 1;
            if (newAmount <= 0) {
                player.getInventory().setItemInMainHand(null);
            } else {
                mainHand.setAmount(newAmount);
                player.getInventory().setItemInMainHand(mainHand);
            }
        }

        CuttingBoardBlockBehavior.saveBlockEntityData(block.getWorld(), posKey);
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_HIT, 1.0f, 1.2f);
        player.swingMainHand();
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
        player.updateInventory();
    }

    private boolean isTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        if (isKnifeTool(item) || item.getType().name().endsWith("_AXE")) {
            return true;
        }

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            CustomItem<?> customItem = FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                    .getCustomItem(Key.of(customId)).orElse(null);
            if (customItem != null) {
                Set<Key> itemTags = customItem.settings().tags();
                for (Key tag : toolTags) {
                    if (itemTags.contains(tag)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isKnifeTool(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId == null) {
            return false;
        }

        return FarmersDelightPlugin.getInstance()
                .getConfig()
                .getStringList("knife-config.items")
                .stream()
                .anyMatch(id -> id.equalsIgnoreCase(customId));
    }
}
