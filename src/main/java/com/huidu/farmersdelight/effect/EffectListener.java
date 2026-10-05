package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.buff.CustomBuff;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class EffectListener implements Listener {

    private static final Set<UUID> playersWithEffects = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Player> trackedPlayers = new ConcurrentHashMap<>();
    private static final Set<UUID> scheduledTicks = ConcurrentHashMap.newKeySet();
    // Effect task tick cadence, resolved from config once at start(). The same value is the amount
    // durations decrement by each pass, so every consumer reads it back through tickInterval() to stay
    // identical. A changed interval takes effect on the next start (plugin enable / server restart),
    // keeping the running scheduler period and the duration decrement in lockstep.
    // Config: performance.budgets.effect-tick-interval-ticks (default 4, min 1).
    private static final long DEFAULT_TICK_INTERVAL = 4L;
    private static volatile long tickInterval = DEFAULT_TICK_INTERVAL;

    static long tickInterval() {
        return tickInterval;
    }
    // Default delay (ticks) for the post-join PDC restore retry; overridable via config. 40 ticks (2s)
    // comfortably clears a whole-profile sync plugin's async apply without a visible gap.
    private static final int DEFAULT_RESTORE_RETRY_DELAY_TICKS = 40;
    // Items carrying this CraftEngine item tag act as the single-buff cleanser (milk_bottle and any
    // future milk-bottle-like drink), so the trigger is data-driven instead of a hardcoded item id.
    private static final Key MILK_TAG = Key.of("farmersdelight:milk");
    // Cadence of the buff-transition sync pass. Remaining times are reported in whole seconds, so a
    // one-second pass is as fine-grained as a transition can be observed. The pass returns immediately
    // while no player carries a buff, so this runs at a flat cost on an idle server.
    private static final long BUFF_SYNC_INTERVAL_TICKS = 20L;
    private final FarmersDelightPlugin plugin;
    private volatile PluginTask effectTask;
    private volatile PluginTask buffSyncTask;
    // Protect effectTask against concurrent region-thread starts and tick-thread cancellation.
    private final Object taskLock = new Object();
    private volatile boolean stopped = true;
    // Resolved once per start() so the tick body does not re-probe it per pass.
    private volatile boolean folia;
    // Active instance for the static trackPlayer facade, so a producer can re-arm the ticker without
    // routing through the plugin singleton. Set in start(), identity-cleared in stop().
    private static volatile EffectListener active;

    public EffectListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        registerOwnBuffs();
    }

    private static void registerOwnBuffs() {
        CustomBuffRegistry.register(new CustomBuff() {
            @Override public String id() { return "farmersdelight:comfort"; }
            @Override public boolean isActive(Player player) { return EffectManager.hasComfort(player); }
            @Override public void remove(Player player) { EffectManager.removeComfort(player); }
            @Override public boolean apply(Player player, int level, int durationSeconds) {
                EffectManager.applyComfort(player, durationSeconds, level);
                return true;
            }
            @Override public int level(Player player) { return EffectManager.comfortLevel(player); }
            @Override public int remainingSeconds(Player player) { return EffectManager.comfortRemainingSeconds(player); }
            @Override public String nameKey() { return "buff.farmersdelight.comfort"; }
            @Override public void saveState(Player player) { EffectManager.saveComfortToPdc(player); }
            @Override public void restoreState(Player player) {
                if (EffectManager.restoreComfortFromPdc(player)) trackPlayer(player);
            }
        });
        CustomBuffRegistry.register(new CustomBuff() {
            @Override public String id() { return "farmersdelight:nourishment"; }
            @Override public boolean isActive(Player player) { return EffectManager.hasNourishment(player); }
            @Override public void remove(Player player) { EffectManager.removeNourishment(player); }
            @Override public boolean apply(Player player, int level, int durationSeconds) {
                EffectManager.applyNourishment(player, durationSeconds, level);
                return true;
            }
            @Override public int level(Player player) { return EffectManager.nourishmentLevel(player); }
            @Override public int remainingSeconds(Player player) { return EffectManager.nourishmentRemainingSeconds(player); }
            @Override public String nameKey() { return "buff.farmersdelight.nourishment"; }
            @Override public void saveState(Player player) { EffectManager.saveNourishmentToPdc(player); }
            @Override public void restoreState(Player player) {
                if (EffectManager.restoreNourishmentFromPdc(player)) trackPlayer(player);
            }
        });
    }

    public static void trackPlayer(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        playersWithEffects.add(playerId);
        trackedPlayers.put(playerId, player);
        // Add to the pool BEFORE re-arming, so a tick pass concurrently draining to empty either observes
        // this player (and does not cancel) or cancels and is immediately restarted by the ensure below.
        EffectListener listener = active;
        if (listener != null) {
            listener.ensureTicking();
        }
    }

    public static void untrackPlayer(UUID playerId) {
        playersWithEffects.remove(playerId);
        trackedPlayers.remove(playerId);
        scheduledTicks.remove(playerId);
    }

    public void start() {
        // Resolve Folia once: on Paper/Spigot the repeating task already runs on the main thread, so 
        // can call EffectManager.tick directly and skip one BukkitTask allocation per tracked player per
        // tick pass (100 buffed players × 5 passes/sec = 500 task allocations/sec saved).
        folia = plugin.scheduler().isFolia();
        synchronized (taskLock) {
            stopped = false;
            // Resolve the tick interval only while no pass is scheduled, so the running scheduler period
            // and the per-pass duration decrement can never disagree. ensureTicking reuses this value for
            // every later on-demand restart, so a restart mid-session keeps the same cadence.
            if (effectTask == null) {
                tickInterval = Math.max(1L, plugin.getConfigInt((int) DEFAULT_TICK_INTERVAL,
                        "performance.budgets.effect-tick-interval-ticks"));
            }
        }
        active = this;
        // Nobody has a food buff on a fresh start, so the ticker is armed on demand by trackPlayer.
        // Restores that already landed (a reload while players are online) are covered by this ensure.
        if (!playersWithEffects.isEmpty()) {
            ensureTicking();
        }
        ensureBuffSyncTicking();
    }

    private void ensureBuffSyncTicking() {
        synchronized (taskLock) {
            if (!stopped && buffSyncTask == null && plugin.isEnabled()
                    && CustomBuffRegistry.isSystemEnabled()) {
                buffSyncTask = plugin.scheduler().runRepeating(CustomBuffRegistry::syncTrackedPlayers,
                        BUFF_SYNC_INTERVAL_TICKS, BUFF_SYNC_INTERVAL_TICKS);
            }
        }
    }

    private void ensureTicking() {
        synchronized (taskLock) {
            if (!stopped && effectTask == null && plugin.isEnabled()
                    && CustomBuffRegistry.isSystemEnabled()) {
                effectTask = plugin.scheduler().runRepeating(this::tickEffects, 1L, tickInterval);
            }
        }
    }

    public void applySystemEnabled(boolean systemEnabled) {
        if (!systemEnabled) {
            synchronized (taskLock) {
                if (effectTask != null) {
                    effectTask.cancel();
                    effectTask = null;
                }
                if (buffSyncTask != null) {
                    buffSyncTask.cancel();
                    buffSyncTask = null;
                }
            }
            playersWithEffects.clear();
            trackedPlayers.clear();
            scheduledTicks.clear();
            // Clears the duration maps only. Retracting what is already drawn on screen is the display
            // manager's job and it runs its own switch-off pass in the same reload, so no player state is
            // touched from this thread.
            EffectManager.clearAll();
            return;
        }
        if (!playersWithEffects.isEmpty()) {
            ensureTicking();
        }
        ensureBuffSyncTicking();
    }

    private void tickEffects() {
        // Self-cancel at the same point as the pass's early-return guard. The pool drains to empty via
        // paths outside this pass (quit / death / milk cleanse / untrackPlayer), and those never touch the
        // task handle — checking only at the tail of a pass would leave the ticker running forever after
        // any of them. The locked recheck pairs with trackPlayer's add-then-ensureTicking so a player
        // added concurrently is never stranded without a running pass.
        if (playersWithEffects.isEmpty()) {
            synchronized (taskLock) {
                if (playersWithEffects.isEmpty() && effectTask != null) {
                    effectTask.cancel();
                    effectTask = null;
                }
            }
            return;
        }

        boolean folia = this.folia;
        Iterator<Map.Entry<UUID, Player>> iterator = trackedPlayers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Player> entry = iterator.next();
            UUID playerId = entry.getKey();
            Player player = entry.getValue();
            if (!playersWithEffects.contains(playerId)) {
                iterator.remove();
                continue;
            }
            if (player == null) {
                continue;
            }
            if (folia) {
                // Folia: EffectManager.tick touches player state, must run on the player's region thread.
                if (!scheduledTicks.add(playerId)) {
                    continue;
                }
                try {
                    // retired callback: on Folia the entity task is silently dropped if the player is
                    // retired after queueing but before running (no Quit/Death event). Without clearing
                    // scheduledTicks there, the guard above (scheduledTicks.add) stays false forever and
                    // EffectManager.tick never runs for that player again.
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
                    }, () -> scheduledTicks.remove(playerId));
                } catch (RuntimeException e) {
                    scheduledTicks.remove(playerId);
                    untrackPlayer(playerId);
                }
            } else {
                // Paper/Spigot: already on the main thread, call tick directly.
                if (!player.isOnline()) {
                    untrackPlayer(playerId);
                    continue;
                }
                try {
                    EffectManager.tick(player);
                } catch (RuntimeException e) {
                    untrackPlayer(playerId);
                }
            }
        }
    }

    public void stop() {
        synchronized (taskLock) {
            stopped = true;
            if (effectTask != null) {
                effectTask.cancel();
                effectTask = null;
            }
            if (buffSyncTask != null) {
                buffSyncTask.cancel();
                buffSyncTask = null;
            }
        }
        if (active == this) {
            active = null;
        }
        playersWithEffects.clear();
        trackedPlayers.clear();
        scheduledTicks.clear();
        EffectManager.clearAll();
        CustomBuffRegistry.unregister("farmersdelight:comfort");
        CustomBuffRegistry.unregister("farmersdelight:nourishment");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Central buff-persistence restore for EVERY registered buff (FD's own Comfort / Nourishment
        // and any addon buff, e.g. BAC Tipsy). Each buff pulls its own state back and starts tracking.
        CustomBuffRegistry.restoreAll(player);
        // Retry once after a configurable delay to catch whole-profile sync plugins (HuskSync /
        // MySQLPlayerDataBridge etc.) that apply the synced PDC a moment after join. restoreState is
        // gap-filling, so this is a no-op when the immediate restore already succeeded or the player
        // gained a buff since joining. <= 0 disables the retry (single-server needs no retry).
        long retryDelay = plugin.getConfigInt(DEFAULT_RESTORE_RETRY_DELAY_TICKS,
                "buff.persistence.restore-retry-delay-ticks",
                "buff-persistence.restore-retry-delay-ticks");
        if (retryDelay > 0) {
            plugin.scheduler().runLaterForEntity(player, () -> {
                if (player.isOnline()) {
                    CustomBuffRegistry.restoreAll(player);
                }
            }, retryDelay);
        }
    }

    // Persist every registered buff before the MONITOR handler below wipes FD's live maps. LOWEST so
    // the PDC writes land before whole-profile sync plugins (HuskSync etc.) snapshot the player on quit.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuitSave(PlayerQuitEvent event) {
        CustomBuffRegistry.saveAll(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        untrackPlayer(event.getPlayer().getUniqueId());
        EffectManager.clearPlayer(event.getPlayer());
        // Drops the diff baseline for the departing player, after the LOWEST handler above has saved their
        // buffs. Keeping it would both hold the entry for the rest of the server's uptime and make the next
        // join's restore look like "no change" whenever they reconnect at the level they left at, silencing
        // the gain event for a player who is visibly buffed.
        CustomBuffRegistry.forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Death clears the live buff (vanilla clears effects on death). The next quit's saveAll writes
        // the now-empty state, wiping the PDC copy — so no explicit death-wipe is needed here.
        untrackPlayer(event.getEntity().getUniqueId());
        EffectManager.clearPlayer(event.getEntity());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMilkConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item == null) return;
        if (item.getType() == Material.MILK_BUCKET) {
            CustomBuffRegistry.clearAll(event.getPlayer());
            return;
        }
        if (ItemUtils.hasCustomItemTag(item, MILK_TAG)) {
            milkBottleCleanse(event.getPlayer());
        }
    }

    private void milkBottleCleanse(Player player) {
        if (player == null) {
            return;
        }
        List<Runnable> primary = new ArrayList<>();
        List<Runnable> fallback = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            PotionEffectType type = effect.getType();
            primary.add(() -> player.removePotionEffect(type));
        }
        for (CustomBuff buff : CustomBuffRegistry.activeBuffs(player)) {
            (buff.isLowPriority() ? fallback : primary).add(() -> buff.remove(player));
        }
        List<Runnable> pool = primary.isEmpty() ? fallback : primary;
        if (!pool.isEmpty()) {
            try {
                pool.get(ThreadLocalRandom.current().nextInt(pool.size())).run();
            } catch (RuntimeException ignored) {
                // A misbehaving addon buff's remove() shouldn't escape the consume event; per-buff
                // isolation matching CustomBuffRegistry.clearAll/clearOne.
            }
        }
    }
}

