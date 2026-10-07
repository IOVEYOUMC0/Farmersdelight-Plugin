package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.PermissionChecker;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

public class CuttingBoardInteractListener implements Listener {

    // Handed in by the registrar instead of looked up: this handler only runs while the plugin is enabled.
    private final FarmersDelightPlugin plugin;

    public CuttingBoardInteractListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSneakInsertTool(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK) return;
        if (event.hand() != InteractionHand.MAIN_HAND) return;

        Player player = event.player();
        if (!player.isSneaking()) return;
        if (!CustomBlockUtils.hasBehavior(event.blockState(), CuttingBoardBlockBehavior.class)) return;

        Block block = event.bukkitBlock();
        BlockPosKey posKey = new BlockPosKey(block.getX(), block.getY(), block.getZ());
        if (!PermissionChecker.check(player, "farmersdelight.use.cutting_board")) {
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
            CuttingBoardBlockBehavior.loadBlockEntity(plugin, block.getWorld(), posKey);
            blockEntity = CuttingBoardBlockBehavior.getBlockEntity(block.getWorld(), posKey);
        }
        if (blockEntity != null && blockEntity.hasItem()) return;

        if (blockEntity == null) {
            blockEntity = new CuttingBoardBlockEntity(plugin, posKey, block.getWorld());
            CuttingBoardBlockBehavior.putBlockEntity(block.getWorld(), posKey, blockEntity);
        }
        CuttingBoardBlockEntity board = blockEntity;

        ItemStack itemToPlace = mainHand.clone();
        itemToPlace.setAmount(1);
        BlockFace facing = CustomBlockUtils.getFacing(block);
        // Store, then consume — atomically: the store commits the board's item before its persistence side
        // effects run, and on Folia one of those can throw a region thread-check. Without the rollback below
        // the tool would sit on the board while the hand keeps it, so the player could take it back for free.
        // A failed store empties the board again and consumes nothing: no dupe and no loss.
        if (!storeThenConsume(
                () -> board.setItem(itemToPlace, block.getWorld(), posKey, facing, true),
                () -> board.setStoredItem(null, block.getWorld(), posKey, facing),
                () -> {
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
                })) {
            if (plugin.isDebugEnabled("interact")) {
                plugin.getLogger().info(I18n.formatConsole("debug.cutting_board",
                        "message", "sneak insert rolled back after store failure"));
            }
            return;
        }

        CuttingBoardBlockBehavior.saveBlockEntityData(block.getWorld(), posKey);
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_HIT, 1.0f, 1.2f);
        player.swingMainHand();
        event.setCancelled(true);
    }

    /**
     * Runs the board store, then the hand consumption, with the store guarded: a store that throws is rolled
     * back and nothing is consumed, because a committed item plus an unconsumed hand is a free copy of the
     * tool. The rollback is best-effort for the same reason it exists — the item must not be consumed either
     * way. Returns true only when the store committed and the consumption ran.
     */
    @ApiStatus.Internal
    static boolean storeThenConsume(Runnable store, Runnable rollback, Runnable consume) {
        try {
            store.run();
        } catch (RuntimeException | LinkageError failure) {
            try {
                rollback.run();
            } catch (RuntimeException | LinkageError ignored) {
                // Nothing else to restore: the hand item stays untouched regardless of the rollback result.
            }
            return false;
        }
        consume.run();
        return true;
    }
}
