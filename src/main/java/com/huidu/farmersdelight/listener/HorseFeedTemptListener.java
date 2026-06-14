package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.i18n.I18n;
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
    private static final int DEFAULT_TICK_BUDGET = 128;

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
    // activeTempterPlayers 的结构变更代数。tickTemptGoals 据此缓存可索引快照,成员不变(常态)时
    // 不再每周期 List.copyOf。代数由多线程(各 region 线程)修改,用 AtomicLong;快照缓存只在单线程的
    // tickTemptGoals 中读写,故为普通字段。
    private final java.util.concurrent.atomic.AtomicLong tempterGeneration = new java.util.concurrent.atomic.AtomicLong();
    private java.util.List<Map.Entry<UUID, Player>> cachedTempterSnapshot = java.util.List.of();
    private long cachedTempterSnapshotGeneration = -1L;

    public HorseFeedTemptListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reload(true);
    }

    public void reload() {
        reload(false);
    }

    public void reload(boolean logSummary) {
        loadConfig(logSummary);
        restartTask();
    }

    private void restartTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearTempters();
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
        clearTempters();
        scheduledTempterTicks.clear();
    }

    private void loadConfig(boolean logSummary) {
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
        if (logSummary) {
            I18n.logInfo("pet_food.tempt_loaded",
                    "enabled", enabled,
                    "foods", temptFoods.size(),
                    "interval", tickInterval);
        }
    }

    private void refreshTemptStatus(Player player) {
        UUID playerId = player.getUniqueId();
        if (!enabled) {
            removeTempter(playerId);
            return;
        }
        PetFoodConfig.PetFoodDefinition definition = getHeldTemptFood(player).orElse(null);
        if (definition != null) {
            addTempter(playerId, player, definition);
        } else {
            removeTempter(playerId);
        }
    }

    // 以下三个方法集中维护 activeTempters / activeTempterPlayers / activeTemptDefinitions 三个并行集合,
    // 既避免散落各处的三处一致改动出错,也是 tickTemptGoals 快照缓存的唯一代数变更点。
    private void addTempter(UUID playerId, Player player, PetFoodConfig.PetFoodDefinition definition) {
        activeTempters.add(playerId);
        activeTempterPlayers.put(playerId, player);
        activeTemptDefinitions.put(playerId, definition);
        tempterGeneration.incrementAndGet();
    }

    private void removeTempter(UUID playerId) {
        boolean changed = activeTempters.remove(playerId);
        changed |= activeTempterPlayers.remove(playerId) != null;
        changed |= activeTemptDefinitions.remove(playerId) != null;
        if (changed) {
            tempterGeneration.incrementAndGet();
        }
    }

    private void clearTempters() {
        boolean had = !activeTempters.isEmpty() || !activeTempterPlayers.isEmpty() || !activeTemptDefinitions.isEmpty();
        activeTempters.clear();
        activeTempterPlayers.clear();
        activeTemptDefinitions.clear();
        if (had) {
            tempterGeneration.incrementAndGet();
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
        removeTempter(playerId);
        scheduledTempterTicks.remove(playerId);
    }

    private void tickTemptGoals() {
        if (!enabled) return;
        if (activeTempterPlayers.isEmpty()) return;

        // 仅在成员发生结构变更(代数改变)时才重建可索引快照,常态下复用缓存,避免每周期 List.copyOf。
        long generation = tempterGeneration.get();
        if (cachedTempterSnapshotGeneration != generation) {
            cachedTempterSnapshot = java.util.List.copyOf(activeTempterPlayers.entrySet());
            cachedTempterSnapshotGeneration = generation;
        }
        java.util.List<Map.Entry<UUID, Player>> snapshot = cachedTempterSnapshot;
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
                removeTempter(playerId);
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
            removeTempter(playerId);
            return;
        }

        PetFoodConfig.PetFoodDefinition definition = activeTemptDefinitions.get(playerId);
        if (definition == null || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
            removeTempter(playerId);
            return;
        }

        // player.getLocation() 已经返回一个全新的副本，且计划任务只会读取它，因此
        // 共享单个快照是安全的——无需为每个附近的生物分别克隆。
        Location targetLocation = player.getLocation();
        for (Entity nearby : player.getNearbyEntities(definition.temptRange, definition.temptRange, definition.temptRange)) {
            if (nearby instanceof Mob mob) {
                scheduleMobTempt(mob, playerId, targetLocation, definition);
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
