package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public class CuttingBoardInteractListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSneakInsertTool(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK) return;
        if (event.hand() != InteractionHand.MAIN_HAND) return;

        Player player = event.player();
        if (!player.isSneaking()) return;
        if (!CustomBlockUtils.hasBehavior(event.blockState(), CuttingBoardBlockBehavior.class)) return;

        Block block = event.bukkitBlock();
        BlockPosKey posKey = new BlockPosKey(block.getLocation());
        if (!player.hasPermission("farmersdelight.use.cutting_board")) {
            player.sendActionBar(I18n.getComponent("general.no_permission", player));
            return;
        }
        if (!ProtectionCompat.canUse(player, block, ProtectionCompat.Feature.CUTTING_BOARD)
                || !ProtectionCompat.canBuild(player, block, ProtectionCompat.Feature.CUTTING_BOARD)) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        CuttingBoardBlockBehavior behavior = CuttingBoardBlockBehavior.getBlockBehavior(block.getLocation());
        if (behavior == null || !behavior.isTool(mainHand)) return;

        CuttingBoardBlockEntity blockEntity = CuttingBoardBlockBehavior.getBlockEntity(block.getWorld(), posKey);
        if (blockEntity == null) {
            // Apply parked saved data first (deferred startup load, or a chunk served from CraftEngine's
            // chunk cache): creating a blank entity here would let the late apply replace it and destroy
            // the item this handler is about to place. loadBlockEntity flushes pending controller data.
            CuttingBoardBlockBehavior.loadBlockEntity(block.getWorld(), posKey);
            blockEntity = CuttingBoardBlockBehavior.getBlockEntity(block.getWorld(), posKey);
        }
        if (blockEntity != null && blockEntity.hasItem()) return;

        if (blockEntity == null) {
            blockEntity = new CuttingBoardBlockEntity(posKey, block.getWorld());
            CuttingBoardBlockBehavior.putBlockEntity(block.getWorld(), posKey, blockEntity);
        }

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
        event.setCancelled(true);
        player.updateInventory();
    }
}
