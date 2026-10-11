package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.bukkit.util.InteractUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.EventUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.world.BlockHitResult;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public final class CraftEngineAdapter {

    private CraftEngineAdapter() {
    }

    public static boolean hasBlockInteraction(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return false;
        }
        BlockHitResult hit = interactionHit(event);
        ItemStack held = event.getItem();
        // Synthetic clicks without a hit or item cannot safely override a block action.
        if (hit == null || held == null || held.isEmpty()) {
            return true;
        }
        return InteractUtils.isInteractable(event.getPlayer(), block.getBlockData(), hit,
                BukkitItemManager.instance().wrap(held));
    }

    static BlockHitResult interactionHit(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return null;
        }
        Location point = EventUtils.getInteractionPoint(event);
        if (point == null) {
            return null;
        }
        return new BlockHitResult(new Vec3d(point.getX(), point.getY(), point.getZ()),
                DirectionUtils.toDirection(event.getBlockFace()),
                new BlockPos(block.getX(), block.getY(), block.getZ()), false);
    }

    public static World toWorld(Object levelObj) {
        if (levelObj instanceof World world) {
            return world;
        }
        try {
            return LevelProxy.INSTANCE.getWorld(levelObj);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static BlockPos toBlockPos(Object posObj) {
        if (posObj instanceof BlockPos pos) {
            return pos;
        }
        try {
            return LocationUtils.fromBlockPos(posObj);
        } catch (Exception ignored) {
            return null;
        }
    }

}
