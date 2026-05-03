package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks online players that currently have custom food effects.
 * Only tracked players are processed each tick so the scheduled task stays light.
 */
public class EffectListener implements Listener {

    private static final Set<UUID> playersWithEffects = ConcurrentHashMap.newKeySet();
    private static final long TICK_INTERVAL = 4L;
    private final FarmersDelightPlugin plugin;
    private BukkitTask effectTask;

    public EffectListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public static void trackPlayer(UUID playerId) {
        playersWithEffects.add(playerId);
    }

    public static void untrackPlayer(UUID playerId) {
        playersWithEffects.remove(playerId);
    }

    public static boolean isTracked(UUID playerId) {
        return playersWithEffects.contains(playerId);
    }

    public void start() {
        if (effectTask != null) {
            return;
        }
        effectTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (playersWithEffects.isEmpty()) {
                return;
            }

            Iterator<UUID> iterator = playersWithEffects.iterator();
            while (iterator.hasNext()) {
                UUID playerId = iterator.next();
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    EffectManager.tick(player);
                } else {
                    iterator.remove();
                }
            }
        }, 1L, TICK_INTERVAL);
    }

    public void stop() {
        if (effectTask != null) {
            effectTask.cancel();
            effectTask = null;
        }
        playersWithEffects.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (EffectManager.hasNourishment(player)) {
            trackPlayer(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        untrackPlayer(event.getPlayer().getUniqueId());
    }
}
