package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

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
    private final Set<UUID> scheduledTempterTicks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Player> activeTempterPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PetFoodConfig.PetFoodDefinition> activeTemptDefinitions = new ConcurrentHashMap<>();
    private final Map<String, PetFoodConfig.PetFoodDefinition> temptFoods = new ConcurrentHashMap<>();
    // Written by loadConfig on the reload path, read by the tick pass and by the item-held / swap-hand
    // handlers on region threads, so it needs the happens-before edge.
    private volatile boolean enabled;
    // Written by loadConfig on the reload path, read only by restartTask on that same path when the
    // repeating task is created, never by the task body — no cross-thread read, so no volatile needed.
    private long tickInterval;
    // Written by loadConfig on the reload path, read by the tick pass on the scheduler thread, so it
    // needs the happens-before edge.
    private volatile int tickBudget;
    // Read and written only inside tickTemptGoals, which is the single repeating task body.
    private int tickCursor;
    private volatile PluginTask task;
    // Structural-change generation of activeTempterPlayers. tickTemptGoals caches an indexable snapshot from it,
    // skipping a per-cycle List.copyOf when membership is unchanged (the common case). The generation is mutated by
    // multiple threads (region threads), hence AtomicLong; the snapshot cache is read/written only in the single-threaded
    // tickTemptGoals, hence a plain field.
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
            I18n.logDetail("startup", "pet_food.tempt_loaded",
                    "enabled", enabled,
                    "foods", temptFoods.size(),
                    "interval", tickInterval);
        }
    }

    /** Number of pet foods with tempting enabled, for the consolidated startup summary. */
    public int getTemptFoodCount() {
        return temptFoods.size();
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

    // The following three methods centrally maintain the parallel collections activeTempterPlayers /
    // activeTemptDefinitions, both avoiding scattered consistency errors and being the sole generation-change point for the
    // tickTemptGoals snapshot cache.
    private void addTempter(UUID playerId, Player player, PetFoodConfig.PetFoodDefinition definition) {
        activeTempterPlayers.put(playerId, player);
        activeTemptDefinitions.put(playerId, definition);
        tempterGeneration.incrementAndGet();
    }

    private void removeTempter(UUID playerId) {
        boolean changed = activeTempterPlayers.remove(playerId) != null;
        changed |= activeTemptDefinitions.remove(playerId) != null;
        if (changed) {
            tempterGeneration.incrementAndGet();
        }
    }

    private void clearTempters() {
        boolean had = !activeTempterPlayers.isEmpty() || !activeTemptDefinitions.isEmpty();
        activeTempterPlayers.clear();
        activeTemptDefinitions.clear();
        if (had) {
            tempterGeneration.incrementAndGet();
        }
    }

    // A server with no tempt-enabled pet food pays nothing for these two hot handlers: without the gate
    // every hotbar scroll and every offhand swap dispatched a region task just for refreshTemptStatus to
    // find the feature off. The inner !enabled check in refreshTemptStatus stays as the authority — it
    // covers the config-reload race where the feature is disabled between dispatch and run.
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!enabled) return;
        refreshTemptStatusNextTick(event.getPlayer());
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (!enabled) return;
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

        // Rebuild the indexable snapshot only on structural membership change (generation change); otherwise reuse the cache to avoid a per-cycle List.copyOf.
        long generation = tempterGeneration.get();
        if (cachedTempterSnapshotGeneration != generation) {
            cachedTempterSnapshot = java.util.List.copyOf(activeTempterPlayers.entrySet());
            cachedTempterSnapshotGeneration = generation;
        }
        java.util.List<Map.Entry<UUID, Player>> snapshot = cachedTempterSnapshot;
        int size = snapshot.size();
        int budget = Math.min(tickBudget, size);
        int start = tickCursor >= size ? 0 : tickCursor;
        // Resolve once per pass: on Paper/Spigot the repeating task is already on the main thread, so
        // we can call tickTemptPlayer directly and skip the per-tempter runForEntity task allocation
        // (which on Folia is needed for region-thread safety, on Paper is just overhead).
        boolean folia = plugin.scheduler().isFolia();

        for (int processed = 0; processed < budget; processed++) {
            Map.Entry<UUID, Player> entry = snapshot.get((start + processed) % size);
            UUID playerId = entry.getKey();
            Player player = entry.getValue();
            if (player == null) {
                continue;
            }

            if (folia) {
                if (!scheduledTempterTicks.add(playerId)) {
                    continue;
                }
                try {
                    plugin.scheduler().runForEntity(player, () -> {
                        try {
                            tickTemptPlayer(playerId, player);
                        } finally {
                            scheduledTempterTicks.remove(playerId);
                        }
                    }, () -> {
                        // When the player is retired the finally block does not run, so the guard must be
                        // cleared in the retired callback; otherwise scheduledTempterTicks holds the UUID
                        // forever and the tempt feature stays permanently disabled for that player.
                        scheduledTempterTicks.remove(playerId);
                    });
                } catch (RuntimeException e) {
                    scheduledTempterTicks.remove(playerId);
                    removeTempter(playerId);
                }
            } else {
                // Paper/Spigot: already on the main thread — call directly, no task allocation.
                try {
                    tickTemptPlayer(playerId, player);
                } catch (RuntimeException e) {
                    removeTempter(playerId);
                }
            }
        }
        tickCursor = size == 0 ? 0 : (start + Math.max(1, budget)) % size;
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

        // player.getLocation() already returns a fresh copy and the scheduled task only reads it, so
        // sharing a single snapshot is safe; no need to clone per nearby mob.
        Location targetLocation = player.getLocation();
        Set<EntityType> wantedTypes = definition.entities;
        for (Entity nearby : player.getNearbyEntities(definition.temptRange, definition.temptRange, definition.temptRange)) {
            // Filter by configured tempt EntityType set BEFORE region-scheduling. Without this gate, every
            // Mob in the temptRange box (sheep / cows / random hostiles next to a horse-feeding player)
            // incurred a runForEntity dispatch that the per-mob tryMoveToLocation would then drop on the
            // type check — wasting a Folia region task per irrelevant mob per tick.
            if (nearby instanceof Mob mob && wantedTypes.contains(mob.getType())) {
                scheduleMobTempt(mob, playerId, targetLocation, definition);
            }
        }
    }

    private void scheduleMobTempt(Mob mob, UUID playerId, Location targetLocation, PetFoodConfig.PetFoodDefinition definition) {
        if (plugin.scheduler().isFolia()) {
            try {
                plugin.scheduler().runForEntity(mob, () -> tryMoveToLocation(mob, playerId, targetLocation, definition));
            } catch (RuntimeException ignored) {
            }
        } else {
            // Paper/Spigot: already on the main thread (tickTemptPlayer was called directly), so invoke
            // the move directly. Saves one BukkitTask allocation per nearby mob per tempter pass — in a
            // large animal farm (100+ horses next to a feeding player) this avoids 100+ task allocations
            // per tick interval.
            try {
                tryMoveToLocation(mob, playerId, targetLocation, definition);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void tryMoveToLocation(Mob mob, UUID playerId, Location targetLocation, PetFoodConfig.PetFoodDefinition definition) {
        if (mob == null || !mob.isValid() || mob.isDead() || targetLocation == null || targetLocation.getWorld() == null) {
            return;
        }
        if (!targetLocation.getWorld().equals(mob.getWorld())) {
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
