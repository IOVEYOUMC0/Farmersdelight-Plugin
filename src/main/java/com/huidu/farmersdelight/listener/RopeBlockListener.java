package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.RopeBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class RopeBlockListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public RopeBlockListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRopeRetract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        if (!CustomBlockUtils.hasBehavior(block, RopeBlockBehavior.class)) return;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!mainHand.getType().isAir()) return;

        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        World world = block.getWorld();
        int bottomY = block.getY();
        int checkY = block.getY() - 1;
        while (checkY >= world.getMinHeight()) {
            Block below = world.getBlockAt(block.getX(), checkY, block.getZ());
            if (CustomBlockUtils.hasBehavior(below, RopeBlockBehavior.class)) {
                bottomY = checkY;
                checkY--;
            } else {
                break;
            }
        }

        Block bottomBlock = world.getBlockAt(block.getX(), bottomY, block.getZ());
        boolean isCreative = player.getGameMode() == GameMode.CREATIVE;

        if (!isCreative) {
            ItemStack recovered = RopeBlockBehavior.buildRopeItemStatic();
            if (recovered != null) {
                if (!player.getInventory().addItem(recovered).isEmpty()) {
                    world.dropItemNaturally(bottomBlock.getLocation(), recovered);
                }
            }
        }

        CraftEngineBlocks.remove(bottomBlock);
        world.playSound(bottomBlock.getLocation(), Sound.BLOCK_WOOL_BREAK, 1.0f, 1.0f);

        BlockPos bp = new BlockPos(block.getX(), bottomY, block.getZ());
        Bukkit.getScheduler().runTask(plugin,
                () -> RopeBlockBehavior.refreshAdjacentRopes(world, bp));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        Bukkit.getScheduler().runTask(plugin, () ->
                RopeBlockBehavior.refreshAdjacentRopes(world, pos));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        Bukkit.getScheduler().runTask(plugin, () ->
                RopeBlockBehavior.refreshAdjacentRopes(world, pos));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (event.getChangedType() != event.getBlock().getType()) {
            Block block = event.getBlock();
            World world = block.getWorld();
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
            Bukkit.getScheduler().runTask(plugin, () ->
                    RopeBlockBehavior.refreshAdjacentRopes(world, pos));
        }
    }
}
