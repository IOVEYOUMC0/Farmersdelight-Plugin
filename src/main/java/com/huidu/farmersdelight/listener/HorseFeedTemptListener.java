package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class HorseFeedTemptListener implements Listener {

    private static final long DEFAULT_TICK_INTERVAL = 10L;
    private static final int DEFAULT_TICK_BUDGET = 128;

    private final FarmersDelightPlugin plugin;
    private final Set<UUID> scheduledTempterTicks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> scheduledTemptRefreshes = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Player> activeTempterPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PetFoodConfig.PetFoodDefinition> activeTemptDefinitions = new ConcurrentHashMap<>();
    private final Map<String, PetFoodConfig.PetFoodDefinition> temptFoods = new ConcurrentHashMap<>();
    // Written by loadConfig on the reload path, read by the tick loop and item-swap/offhand handlers
    // on region threads, so a happens-before edge is required.
    private volatile boolean enabled;
    // Written by loadConfig on the reload path, read only by restartTask on the same path when
    // creating the repeating task — never read by the task body, so no volatile needed.
    private long tickInterval;
    // Written by loadConfig on the reload path, read by the tick loop on the scheduler thread,
    // so a happens-before edge is required.
    private volatile int tickBudget;
    // Written and read exclusively within tickTemptGoals, which is the sole repeating task body.
    private int tickCursor;
    private volatile PluginTask task;
    // Structural-change generation for activeTempterPlayers. tickTemptGoals caches an indexable snapshot
    // from it, skipping List.copyOf per iteration when the membership hasn't changed (the common case).
    // The generation is mutated by multiple threads (region threads), so AtomicLong is used; the cached
    // snapshot is read/written only by the single-threaded tickTemptGoals, so a plain field suffices.
    private final AtomicLong tempterGeneration = new AtomicLong();
    private List<Map.Entry<UUID, Player>> cachedTempterSnapshot = List.of();
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
        scheduledTemptRefreshes.clear();
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
        scheduledTemptRefreshes.clear();
    }

    private void loadConfig(boolean logSummary) {
        PetFoodConfig config = plugin.getPetFoodConfig();
        long shortestInterval = Long.MAX_VALUE;
        temptFoods.clear();

        for (Map.Entry<String, PetFoodConfig.PetFoodDefinition> entry : config.getFoodDefinitions().entrySet()) {
            PetFoodConfig.PetFoodDefinition definition = entry.getValue();
            if (!definition.tempt().enabled()) {
                continue;
            }
            temptFoods.put(entry.getKey().toLowerCase(Locale.ROOT), definition);
            shortestInterval = Math.min(shortestInterval, definition.tempt().tickInterval());
        }

        enabled = !temptFoods.isEmpty();
        tickInterval = shortestInterval == Long.MAX_VALUE ? DEFAULT_TICK_INTERVAL : shortestInterval;
        tickBudget = Math.max(1, plugin.getConfig().getInt("performance.budgets.pet-tempt-tick-budget", DEFAULT_TICK_BUDGET));
        if (logSummary) {
            I18n.logDetail("startup", "pet_food.tempt_loaded",
                    "enabled", enabled,
                    "foods", temptFoods.size(),
                    "interval", tickInterval);
        }
    }

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

    // The three methods below maintain the activeTempterPlayers / activeTemptDefinitions parallel
    // collections centrally, both to avoid scattered consistency bugs and as the sole mutation point
    // for tickTemptGoals' snapshot cache generation.
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

    // When no tempt-enabled pet food exists, these two hot-path handlers are no-ops: without this guard,
    // every hotbar scroll and every offhand swap would schedule a region task for refreshTemptStatus only
    // to discover that the feature is off. The !enabled check inside refreshTemptStatus stays as the
    // authority — it covers the config-reload race where the feature is toggled between schedule and run.
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
    public void onDropItem(PlayerDropItemEvent event) {
        if (!enabled) return;
        refreshTemptStatusNextTick(event.getPlayer());
    }

    @EventHandler
    public void onConsumeItem(PlayerItemConsumeEvent event) {
        if (!enabled) return;
        refreshTemptStatusNextTick(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled || !(event.getWhoClicked() instanceof Player player)) return;
        refreshTemptStatusNextTick(player);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!enabled || !(event.getWhoClicked() instanceof Player player)) return;
        refreshTemptStatusNextTick(player);
    }

    @EventHandler
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!enabled || !(event.getEntity() instanceof Player player)) return;
        refreshTemptStatusNextTick(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        removeTempter(playerId);
        scheduledTempterTicks.remove(playerId);
        scheduledTemptRefreshes.remove(playerId);
    }

    private void tickTemptGoals() {
        if (!enabled) return;
        if (activeTempterPlayers.isEmpty()) return;

        // Rebuild the indexable snapshot only when membership changes (generation bump); otherwise reuse
        // the cache so List.copyOf is not needed on every iteration.
        long generation = tempterGeneration.get();
        if (cachedTempterSnapshotGeneration != generation) {
            cachedTempterSnapshot = List.copyOf(activeTempterPlayers.entrySet());
            cachedTempterSnapshotGeneration = generation;
        }
        List<Map.Entry<UUID, Player>> snapshot = cachedTempterSnapshot;
        int size = snapshot.size();
        int budget = Math.min(tickBudget, size);
        int start = tickCursor >= size ? 0 : tickCursor;
        // Resolve once per loop: on Paper/Spigot the repeating task is already on the main thread,
        // so tickTemptPlayer can be called directly and skip the runForEntity task allocation per tempter
        // (on Folia it's required for region-thread safety, on Paper it's just overhead).
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
                        // When the player is retired the finally block won't execute, so the retirement
                        // callback must clear the guard; otherwise scheduledTempterTicks would hold the
                        // UUID forever, permanently disabling tempt for that player.
                        scheduledTempterTicks.remove(playerId);
                    });
                } catch (RuntimeException e) {
                    scheduledTempterTicks.remove(playerId);
                    removeTempter(playerId);
                }
            } else {
                // Paper/Spigot: already on the main thread — call directly, no task allocation needed.
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

        // Commands and other plugins can replace the held stack without a Bukkit inventory event. The
        // periodic validation is the final authority and prevents a stale entry from scanning forever.
        PetFoodConfig.PetFoodDefinition heldDefinition = getHeldTemptFood(player).orElse(null);
        if (heldDefinition == null) {
            removeTempter(playerId);
            return;
        }
        PetFoodConfig.PetFoodDefinition definition = activeTemptDefinitions.get(playerId);
        if (!heldDefinition.equals(definition)) {
            activeTemptDefinitions.put(playerId, heldDefinition);
            definition = heldDefinition;
        }
        if (definition == null || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
            removeTempter(playerId);
            return;
        }

        // player.getLocation() already returns a new copy, and the scheduled task only reads it,
        // so sharing a single snapshot is safe; no need to clone per nearby entity.
        Location targetLocation = player.getLocation();
        Set<EntityType> wantedTypes = definition.entities();
        PetFoodConfig.TemptSettings tempt = definition.tempt();
        double rangeSq = tempt.rangeSquared();
        for (Entity nearby : getChunkEntitiesInRange(targetLocation, tempt.range())) {
            if (nearby.getLocation().distanceSquared(targetLocation) > rangeSq) continue;
            // Filter by the configured tempt EntityType set before region dispatch. Without this guard,
            // every unrelated entity (sheep/cows/random hostile) within temptRange would produce a
            // runForEntity dispatch, and each mob's tryMoveToLocation would discard it at the type
            // check — wasting one Folia region task per tick, per unrelated entity.
            if (nearby instanceof Mob mob && wantedTypes.contains(mob.getType())) {
                scheduleMobTempt(mob, playerId, targetLocation, definition);
            }
        }
    }

    // Scan chunk entity lists instead of getNearbyEntities to avoid blocking on Folia region threads.
    // Since the tempt range is bounded (configurable, default 10), loaded chunks within range are iterated.
    private List<Entity> getChunkEntitiesInRange(Location center, double range) {
        World world = center.getWorld();
        if (world == null) return List.of();
        int minCX = (center.getBlockX() - (int) Math.ceil(range)) >> 4;
        int maxCX = (center.getBlockX() + (int) Math.ceil(range)) >> 4;
        int minCZ = (center.getBlockZ() - (int) Math.ceil(range)) >> 4;
        int maxCZ = (center.getBlockZ() + (int) Math.ceil(range)) >> 4;
        List<Entity> result = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                // Folia rejects world/chunk access owned by another region. Keep the bounded scan
                // useful for chunks owned by this player's region and skip the rest; those mobs are
                // picked up when the player enters their region.
                if (plugin.scheduler().isFolia()
                        && !plugin.scheduler().isOwnedByCurrentRegion(
                        new Location(world, (cx << 4) + 8, 0, (cz << 4) + 8))) {
                    continue;
                }
                if (world.isChunkLoaded(cx, cz)) {
                    result.addAll(List.of(world.getChunkAt(cx, cz).getEntities()));
                }
            }
        }
        return result;
    }

    private void scheduleMobTempt(Mob mob, UUID playerId, Location targetLocation, PetFoodConfig.PetFoodDefinition definition) {
        if (plugin.scheduler().isFolia()) {
            try {
                plugin.scheduler().runForEntity(mob, () -> tryMoveToLocation(mob, playerId, targetLocation, definition));
            } catch (RuntimeException ignored) {
            }
        } else {
            // Paper/Spigot: already on the main thread (tickTemptPlayer is called directly), so call
            // movement logic directly. Saves one BukkitTask allocation per nearby entity per tempter
            // per tick — in large animal farms (100+ horses next to a feeding player), this avoids
            // 100+ task allocations per tick interval.
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
        PetFoodConfig.TemptSettings tempt = definition.tempt();
        if (mob.getLocation().distanceSquared(targetLocation) > tempt.rangeSquared()) {
            return;
        }
        if (tempt.ignoreOwnedTamed() && mob.getTarget() == null
                && mob instanceof AbstractHorse horse && horse.isTamed()
                && horse.getOwner() != null && playerId.equals(horse.getOwner().getUniqueId())) {
            return;
        }

        try {
            mob.getPathfinder().moveTo(targetLocation, tempt.moveSpeed());
        } catch (Throwable ignored) {
        }
    }

    private void refreshTemptStatusNextTick(Player player) {
        UUID playerId = player.getUniqueId();
        if (!scheduledTemptRefreshes.add(playerId)) {
            return;
        }
        try {
            plugin.scheduler().runLaterForEntity(player, () -> {
                try {
                    if (player.isOnline()) {
                        refreshTemptStatus(player);
                    }
                } finally {
                    scheduledTemptRefreshes.remove(playerId);
                }
            }, 1L);
        } catch (RuntimeException ignored) {
            scheduledTemptRefreshes.remove(playerId);
        }
    }

}
