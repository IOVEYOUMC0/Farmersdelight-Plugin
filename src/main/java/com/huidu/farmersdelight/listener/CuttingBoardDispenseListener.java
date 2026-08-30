package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import io.papermc.paper.event.block.BlockPreDispenseEvent;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public final class CuttingBoardDispenseListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public CuttingBoardDispenseListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreDispense(BlockPreDispenseEvent event) {
        if (!plugin.isCuttingBoardDispenserBehaviorEnabled()) {
            return;
        }
        Block dispenser = event.getBlock();
        // Only dispensers use items as tools; droppers just eject, so leave them to vanilla.
        if (dispenser.getType() != Material.DISPENSER) {
            return;
        }
        ItemStack tool = event.getItemStack();
        if (tool == null || tool.getType().isAir()) {
            return;
        }
        // A FarmersDelight knife has no vanilla dispense behavior, so a dispenser would eject it as an
        // ordinary item the moment it fires. The knife is only ever meant to be used as a tool on a facing
        // cutting board, so gate on the item instead of the block: this also keeps a knife inside the
        // dispenser while CE reloads, when the facing board can briefly fail to resolve as a cutting board.
        String customId = ItemUtils.getCustomItemId(tool);
        if (customId == null || !plugin.isKnifeItemId(customId)) {
            return;
        }
        event.setCancelled(true);

        if (!(dispenser.getBlockData() instanceof Directional directional)) {
            return;
        }
        Block target = dispenser.getRelative(directional.getFacing());
        World world = target.getWorld();
        // Use int coordinates directly to skip the Location allocation per event.
        BlockPosKey boardPos = new BlockPosKey(target.getX(), target.getY(), target.getZ());
        // isCuttingBoardBlock returns true when the facing block is NOT a FarmersDelight cutting
        // board (its body inverts the has-behavior/has-id checks for the save/load guard paths), so a
        // truthy result means skip the cut; otherwise a non-board would reach the cutter with no tool.
        if (CuttingBoardBlockBehavior.isCuttingBoardBlock(world, boardPos)) {
            return;
        }

        // The cut mutates the board's block entity, so only proceed when the board is owned by the region this
        // event runs on. On Paper that is always true; on Folia it skips a rare cross-region border (the item
        // stays in the dispenser, un-ejected) rather than risk a region-ownership throw.
        if (!plugin.scheduler().isOwnedByCurrentRegion(target.getLocation())) {
            return;
        }

        CuttingBoardBlockBehavior behavior = CustomBlockUtils.getBehavior(target, CuttingBoardBlockBehavior.class);
        if (behavior == null) {
            return;
        }

        BlockFace boardFacing = CustomBlockUtils.getFacing(target);
        ItemStack toolCopy = tool.clone();
        boolean cut = behavior.tryDispenserCut(world, boardPos, boardFacing, toolCopy);
        if (cut && dispenser.getState(false) instanceof Dispenser dispenserState) {
            if (toolCopy.isEmpty() || toolCopy.getAmount() <= 0) {
                dispenserState.getInventory().clear(event.getSlot());
            } else {
                dispenserState.getInventory().setItem(event.getSlot(), toolCopy);
            }
        }
    }
}
