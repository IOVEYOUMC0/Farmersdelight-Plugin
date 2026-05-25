package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HorseFeedTemptListener implements Listener {

    private static final long DEFAULT_TICK_INTERVAL = 10L;
    private static final int DEFAULT_TICK_BUDGET = 64;

    private final FarmersDelightPlugin plugin;
    private final Set<UUID> activeTempters = ConcurrentHashMap.newKeySet();
    private final Set<UUID> scheduledTempterTicks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Player> activeTempterPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PetFoodConfig.PetFoodDefinition> activeTemptDefinitions = new ConcurrentHashMap<>();
    private final Map<String, PetFoodConfig.PetFoodDefinition> temptFoods = new HashMap<>();
    private boolean enabled;
    private long tickInterval;
    private int tickBudget;
    private int tickCursor;
    private PluginTask task;

    public HorseFeedTemptListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reload();
    }

    public void reload() {
        loadConfig();
        restartTask();
    }

    private void restartTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        activeTempters.clear();
        activeTempterPlayers.clear();
        activeTemptDefinitions.clear();
        scheduledTempterTicks.clear();
        if (!enabled) {
            return;
        }

        task = plugin.scheduler().runRepeating(this::tickTemptGoals, tickInterval, tickInterval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        activeTempters.clear();
        activeTempterPlayers.clear();
        activeTemptDefinitions.clear();
        scheduledTempterTicks.clear();
    }

    private void loadConfig() {
        PetFoodConfig config = plugin.getPetFoodConfig();
        long shortestInterval = Long.MAX_VALUE;
        temptFoods.clear();

        for (Map.Entry<String, PetFoodConfig.PetFoodDefinition> entry : config.getFoodDefinitions().entrySet()) {
            PetFoodConfig.PetFoodDefinition definition = entry.getValue();
            if (!definition.temptEnabled) {
                continue;
            }
            temptFoods.put(entry.getKey().toLowerCase(Locale.ROOT), definition);
            shortestInterval = Math.min(shortestInterval, definition.temptTickInterval);
        }

        enabled = !temptFoods.isEmpty();
        tickInterval = shortestInterval == Long.MAX_VALUE ? DEFAULT_TICK_INTERVAL : shortestInterval;
        tickBudget = Math.max(1, plugin.getConfig().getInt("performance.pet-tempt-tick-budget", DEFAULT_TICK_BUDGET));
        plugin.getLogger().info("Loaded pet-food tempt config: enabled=" + enabled
                + ", foods=" + temptFoods.size()
                + ", interval=" + tickInterval);
    }

    private void refreshTemptStatus(Player player) {
        UUID playerId = player.getUniqueId();
        if (!enabled) {
            activeTempters.remove(playerId);
            activeTempterPlayers.remove(playerId);
            activeTemptDefinitions.remove(playerId);
            return;
        }
        PetFoodConfig.PetFoodDefinition definition = getHeldTemptFood(player).orElse(null);
        if (definition != null) {
            activeTempters.add(playerId);
            activeTempterPlayers.put(playerId, player);
            activeTemptDefinitions.put(playerId, definition);
        } else {
            activeTempters.remove(playerId);
            activeTempterPlayers.remove(playerId);
            activeTemptDefinitions.remove(playerId);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        refreshTemptStatusNextTick(event.getPlayer());
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        refreshTemptStatusNextTick(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        activeTempters.remove(playerId);
        activeTempterPlayers.remove(playerId);
        activeTemptDefinitions.remove(playerId);
        scheduledTempterTicks.remove(playerId);
    }

    private void tickTemptGoals() {
        if (!enabled) return;
        if (activeTempterPlayers.isEmpty()) return;

        java.util.List<Map.Entry<UUID, Player>> snapshot = java.util.List.copyOf(activeTempterPlayers.entrySet());
        int size = snapshot.size();
        int budget = Math.min(tickBudget, size);
        int start = tickCursor >= size ? 0 : tickCursor;

        for (int processed = 0; processed < budget; processed++) {
            Map.Entry<UUID, Player> entry = snapshot.get((start + processed) % size);
            UUID playerId = entry.getKey();
            Player player = entry.getValue();
            if (player == null || !scheduledTempterTicks.add(playerId)) {
                continue;
            }

            try {
                plugin.scheduler().runForEntity(player, () -> {
                    try {
                        tickTemptPlayer(playerId, player);
                    } finally {
                        scheduledTempterTicks.remove(playerId);
                    }
                });
            } catch (RuntimeException e) {
                scheduledTempterTicks.remove(playerId);
                activeTempters.remove(playerId);
                activeTempterPlayers.remove(playerId);
                activeTemptDefinitions.remove(playerId);
            }
        }
        tickCursor = size == 0 ? 0 : (start + Math.max(1, budget)) % size;
    }

    private boolean isHoldingTemptFood(Player player) {
        return getHeldTemptFood(player).isPresent();
    }

    private Optional<PetFoodConfig.PetFoodDefinition> getHeldTemptFood(Player player) {
        return getTemptFood(player.getInventory().getItemInMainHand())
                .or(() -> getTemptFood(player.getInventory().getItemInOffHand()));
    }

    private Optional<PetFoodConfig.PetFoodDefinition> getTemptFood(ItemStack item) {
        String customId = item == null ? null : ItemUtils.getCustomItemId(item);
        if (customId == null) {
            return Optional.empty();
        }

        PetFoodConfig.PetFoodDefinition definition = temptFoods.get(customId.toLowerCase(Locale.ROOT));
        if (definition == null) {
            return Optional.empty();
        }
        return Optional.of(definition);
    }

    private void tickTemptPlayer(UUID playerId, Player player) {
        if (player == null || !player.isOnline() || !player.isValid()) {
            activeTempters.remove(playerId);
            activeTempterPlayers.remove(playerId);
            activeTemptDefinitions.remove(playerId);
            return;
        }

        PetFoodConfig.PetFoodDefinition definition = activeTemptDefinitions.get(playerId);
        if (definition == null || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
            activeTempters.remove(playerId);
            activeTempterPlayers.remove(playerId);
            activeTemptDefinitions.remove(playerId);
            return;
        }

        Location targetLocation = player.getLocation();
        for (Entity nearby : player.getNearbyEntities(definition.temptRange, definition.temptRange, definition.temptRange)) {
            if (nearby instanceof Mob mob) {
                scheduleMobTempt(mob, playerId, targetLocation.clone(), definition);
            }
        }
    }

    private void scheduleMobTempt(Mob mob, UUID playerId, Location targetLocation, PetFoodConfig.PetFoodDefinition definition) {
        try {
            plugin.scheduler().runForEntity(mob, () -> tryMoveToLocation(mob, playerId, targetLocation, definition));
        } catch (RuntimeException ignored) {
        }
    }

    private void tryMoveToLocation(Mob mob, UUID playerId, Location targetLocation, PetFoodConfig.PetFoodDefinition definition) {
        if (mob == null || !mob.isValid() || mob.isDead() || targetLocation == null || targetLocation.getWorld() == null) {
            return;
        }
        if (!definition.entities.contains(mob.getType()) || !targetLocation.getWorld().equals(mob.getWorld())) {
            return;
        }
        if (mob.getLocation().distanceSquared(targetLocation) > definition.temptRangeSquared) {
            return;
        }
        if (definition.temptIgnoreOwnedTamed && mob.getTarget() == null
                && mob instanceof AbstractHorse horse && horse.isTamed()
                && horse.getOwner() != null && playerId.equals(horse.getOwner().getUniqueId())) {
            return;
        }

        try {
            mob.getPathfinder().moveTo(targetLocation, definition.temptMoveSpeed);
        } catch (Throwable ignored) {
        }
    }

    private void refreshTemptStatusNextTick(Player player) {
        try {
            plugin.scheduler().runForEntity(player, () -> {
                if (player.isOnline()) {
                    refreshTemptStatus(player);
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

}

