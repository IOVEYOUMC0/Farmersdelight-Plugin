package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
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

/**
 * Ports the mod's cutting-board dispenser behavior: a dispenser facing a cutting board uses the dispensed
 * item as a tool on the board's stored item instead of ejecting it. Only real dispensers act — droppers keep
 * ejecting — and the item is never ejected while facing a board, matching the mod.
 */
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
        if (!(dispenser.getBlockData() instanceof Directional directional)) {
            return;
        }
        Block target = dispenser.getRelative(directional.getFacing());
        World world = target.getWorld();
        // Use int coordinates directly to skip the Location allocation per event.
        BlockPosKey boardPos = new BlockPosKey(target.getX(), target.getY(), target.getZ());
        if (!CuttingBoardBlockBehavior.isCuttingBoardBlock(world, boardPos)) {
            return;
        }

        // A dispenser facing a cutting board never ejects — it only ever uses its item as a tool.
        event.setCancelled(true);

        ItemStack tool = event.getItemStack();
        if (tool == null || tool.getType().isAir()) {
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
            // Write the tool back with durability applied; a tool broken to amount 0 clears the slot.
            dispenserState.getInventory().setItem(event.getSlot(), toolCopy.getAmount() > 0 ? toolCopy : null);
        }
    }
}
