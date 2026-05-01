package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public class HorseFeedTemptListener {

    private static final String HORSE_FEED_ID = "farmersdelight:horse_feed";
    private static final double TEMPT_RANGE = 10.0D;
    private static final double TEMPT_RANGE_SQUARED = TEMPT_RANGE * TEMPT_RANGE;
    private static final double MOVE_SPEED = 1.25D;
    private static final long TICK_INTERVAL = 10L;

    private final FarmersDelightPlugin plugin;
    private BukkitTask task;

    public HorseFeedTemptListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            return;
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTemptGoals, TICK_INTERVAL, TICK_INTERVAL);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tickTemptGoals() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isHoldingHorseFeed(player)) {
                continue;
            }

            Location location = player.getLocation();
            for (Entity nearby : player.getNearbyEntities(TEMPT_RANGE, TEMPT_RANGE, TEMPT_RANGE)) {
                if (!(nearby instanceof Mob mob) || !isHorseFeedTempted(mob)) {
                    continue;
                }

                if (mob.getLocation().distanceSquared(location) > TEMPT_RANGE_SQUARED) {
                    continue;
                }

                if (mob.getTarget() == null && mob instanceof AbstractHorse horse && horse.isTamed() && horse.getOwner() == player) {
                    continue;
                }

                tryMoveToPlayer(mob, player);
            }
        }
    }

    private boolean isHoldingHorseFeed(Player player) {
        return hasHorseFeed(player.getInventory().getItemInMainHand())
                || hasHorseFeed(player.getInventory().getItemInOffHand());
    }

    private boolean hasHorseFeed(ItemStack item) {
        return item != null && HORSE_FEED_ID.equals(ItemUtils.getCustomItemId(item));
    }

    private boolean isHorseFeedTempted(Mob mob) {
        return mob instanceof Horse || mob instanceof Donkey || mob instanceof Mule;
    }

    private void tryMoveToPlayer(Mob mob, Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR || !player.isValid() || player.isDead()) {
            return;
        }

        try {
            mob.getPathfinder().moveTo(player, MOVE_SPEED);
        } catch (Throwable ignored) {
        }
    }
}
