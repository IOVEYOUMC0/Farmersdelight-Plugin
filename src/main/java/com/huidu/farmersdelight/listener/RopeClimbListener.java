package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.RopeBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

public class RopeClimbListener implements Listener {

    private static final double ROPE_MIN = 7.0 / 16.0;
    private static final double ROPE_MAX = 9.0 / 16.0;
    private static final double CLIMB_SPEED = 0.2;
    private static final double DESCEND_SPEED = -0.1;

    private final FarmersDelightPlugin plugin;
    private volatile double cachedReach = -1;
    private volatile Boolean cachedClimbEnabled = null;

    public RopeClimbListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    private double getReach() {
        double reach = cachedReach;
        if (reach >= 0) return reach;
        reach = plugin.getConfig().getDouble("rope.climb-reach", 0.5);
        cachedReach = reach;
        return reach;
    }

    private boolean isClimbEnabled() {
        Boolean enabled = cachedClimbEnabled;
        if (enabled != null) return enabled;
        enabled = plugin.getConfig().getBoolean("rope.climb-enabled", true);
        cachedClimbEnabled = enabled;
        return enabled;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || from == null) return;

        Player player = event.getPlayer();
        if (!isClimbEnabled() || player.isInWater() || player.isFlying() || player.isGliding()) return;

        if (!isPlayerInsideRope(player, to)) return;

        player.setFallDistance(0);

        if (player.isSneaking()) {
            player.setVelocity(new Vector(0, Math.max(DESCEND_SPEED, player.getVelocity().getY()), 0));
            return;
        }

        if (player.isJumping() && to.getY() > from.getY() && player.getVelocity().getY() < CLIMB_SPEED) {
            player.setVelocity(new Vector(0, CLIMB_SPEED, 0));
        }
    }

    private boolean isPlayerInsideRope(Player player, Location loc) {
        double reach = getReach();
        double relX = loc.getX() - loc.getBlockX();
        double relZ = loc.getZ() - loc.getBlockZ();

        if (relX < ROPE_MIN - reach || relX > ROPE_MAX + reach
                || relZ < ROPE_MIN - reach || relZ > ROPE_MAX + reach) {
            return false;
        }

        Block headBlock = loc.getBlock();
        if (isRopeBlock(headBlock)) return true;
        Block feetBlock = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        return isRopeBlock(feetBlock);
    }

    private static boolean isRopeBlock(Block block) {
        return CustomBlockUtils.hasBehavior(block, RopeBlockBehavior.class);
    }
}
