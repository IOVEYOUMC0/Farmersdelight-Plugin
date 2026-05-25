package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks online players that currently have custom food effects.
 * Only tracked players are processed each tick so the scheduled task stays light.
 */
public class EffectListener implements Listener {

    private static final Set<UUID> playersWithEffects = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Player> trackedPlayers = new ConcurrentHashMap<>();
    private static final Set<UUID> scheduledTicks = ConcurrentHashMap.newKeySet();
    private static final long TICK_INTERVAL = 4L;
    private final FarmersDelightPlugin plugin;
    private PluginTask effectTask;

    public EffectListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public static void trackPlayer(UUID playerId) {
        playersWithEffects.add(playerId);
    }

    public static void trackPlayer(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        playersWithEffects.add(playerId);
        trackedPlayers.put(playerId, player);
    }

    public static void untrackPlayer(UUID playerId) {
        playersWithEffects.remove(playerId);
        trackedPlayers.remove(playerId);
        scheduledTicks.remove(playerId);
    }

    public static boolean isTracked(UUID playerId) {
        return playersWithEffects.contains(playerId);
    }

    public void start() {
        if (effectTask != null) {
            return;
        }
        effectTask = plugin.scheduler().runRepeating(() -> {
            if (playersWithEffects.isEmpty()) {
                return;
            }

            Iterator<Map.Entry<UUID, Player>> iterator = trackedPlayers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Player> entry = iterator.next();
                UUID playerId = entry.getKey();
                Player player = entry.getValue();
                if (!playersWithEffects.contains(playerId)) {
                    iterator.remove();
                    continue;
                }
                if (player == null || !scheduledTicks.add(playerId)) {
                    continue;
                }
                try {
                    plugin.scheduler().runForEntity(player, () -> {
                        try {
                            if (player.isOnline()) {
                                EffectManager.tick(player);
                            } else {
                                untrackPlayer(playerId);
                            }
                        } finally {
                            scheduledTicks.remove(playerId);
                        }
                    });
                } catch (RuntimeException e) {
                    scheduledTicks.remove(playerId);
                    untrackPlayer(playerId);
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
        trackedPlayers.clear();
        scheduledTicks.clear();
        EffectManager.clearAll();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (EffectManager.hasComfort(player) || EffectManager.hasNourishment(player)) {
            trackPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        untrackPlayer(event.getPlayer().getUniqueId());
        EffectManager.clearPlayer(event.getPlayer());
    }
}

