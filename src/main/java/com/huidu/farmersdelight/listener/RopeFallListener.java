package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.RopeBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

public class RopeFallListener implements Listener {

    private static final double ROPE_MIN = 7.0 / 16.0;
    private static final double ROPE_MAX = 9.0 / 16.0;
    private static final double REACH = 0.5;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;

        if (isOnRope(player)) {
            event.setCancelled(true);
        }
    }

    private static boolean isOnRope(Player player) {
        Location loc = player.getLocation();
        double relX = loc.getX() - loc.getBlockX();
        double relZ = loc.getZ() - loc.getBlockZ();

        if (relX < ROPE_MIN - REACH || relX > ROPE_MAX + REACH
                || relZ < ROPE_MIN - REACH || relZ > ROPE_MAX + REACH) {
            return false;
        }

        Block feetBlock = loc.getBlock();
        if (isRopeBlock(feetBlock)) return true;
        Block belowBlock = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        return isRopeBlock(belowBlock);
    }

    private static boolean isRopeBlock(Block block) {
        return CustomBlockUtils.hasBehavior(block, RopeBlockBehavior.class);
    }
}

