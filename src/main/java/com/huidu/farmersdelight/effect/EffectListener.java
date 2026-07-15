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

/**
 * Tracks online players that currently have custom food effects.
 * Only tracked players are processed each tick, keeping the scheduled task lightweight.
 */
public class EffectListener implements Listener {

    private static final Set<UUID> playersWithEffects = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Player> trackedPlayers = new ConcurrentHashMap<>();
    private static final Set<UUID> scheduledTicks = ConcurrentHashMap.newKeySet();
    static final long TICK_INTERVAL = 4L;
    // Default delay (ticks) for the post-join PDC restore retry; overridable via config. 40 ticks (2s)
    // comfortably clears a whole-profile sync plugin's async apply without a visible gap.
    private static final int DEFAULT_RESTORE_RETRY_DELAY_TICKS = 40;
    // Items carrying this CraftEngine item tag act as the single-buff cleanser (milk_bottle and any
    // future milk-bottle-like drink), so the trigger is data-driven instead of a hardcoded item id.
    private static final Key MILK_TAG = Key.of("farmersdelight:milk");
    private final FarmersDelightPlugin plugin;
    private volatile PluginTask effectTask;

    public EffectListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        registerOwnBuffs();
    }

    /** Wire FD's Comfort / Nourishment into the buff registry so milk_bucket / milk_bottle clears
     *  them through the same code path addons use, and so PAPI placeholders can read their level /
     *  remaining time / name key. The unregister happens in stop(). */
    private static void registerOwnBuffs() {
        CustomBuffRegistry.register(new com.huidu.farmersdelight.api.buff.CustomBuff() {
            @Override public String id() { return "farmersdelight:comfort"; }
            @Override public boolean isActive(Player player) { return EffectManager.hasComfort(player); }
            @Override public void remove(Player player) { EffectManager.removeComfort(player); }
            @Override public boolean apply(Player player, int level, int durationSeconds) {
                EffectManager.applyComfort(player, durationSeconds);
                return true;
            }
            @Override public int level(Player player) { return EffectManager.hasComfort(player) ? 1 : 0; }
            @Override public int remainingSeconds(Player player) { return EffectManager.comfortRemainingSeconds(player); }
            @Override public String nameKey() { return "buff.farmersdelight.comfort"; }
            @Override public void saveState(Player player) { EffectManager.saveComfortToPdc(player); }
            @Override public void restoreState(Player player) {
                if (EffectManager.restoreComfortFromPdc(player)) trackPlayer(player);
            }
        });
        CustomBuffRegistry.register(new com.huidu.farmersdelight.api.buff.CustomBuff() {
            @Override public String id() { return "farmersdelight:nourishment"; }
            @Override public boolean isActive(Player player) { return EffectManager.hasNourishment(player); }
            @Override public void remove(Player player) { EffectManager.removeNourishment(player); }
            @Override public boolean apply(Player player, int level, int durationSeconds) {
                EffectManager.applyNourishment(player, durationSeconds);
                return true;
            }
            @Override public int level(Player player) { return EffectManager.hasNourishment(player) ? 1 : 0; }
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
    }

    public static void untrackPlayer(UUID playerId) {
        playersWithEffects.remove(playerId);
        trackedPlayers.remove(playerId);
        scheduledTicks.remove(playerId);
    }

    public void start() {
        if (effectTask != null) {
            return;
        }
        // Resolve Folia once: on Paper/Spigot the repeating task already runs on the main thread, so we
        // can call EffectManager.tick directly and skip one BukkitTask allocation per tracked player per
        // tick pass (100 buffed players × 5 passes/sec = 500 task allocations/sec saved).
        boolean folia = plugin.scheduler().isFolia();
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Death clears the live buff (vanilla clears effects on death). The next quit's saveAll writes
        // the now-empty state, wiping the PDC copy — so no explicit death-wipe is needed here.
        untrackPlayer(event.getEntity().getUniqueId());
        EffectManager.clearPlayer(event.getEntity());
    }

    /**
     * Milk-consume to addon buff wipe. Vanilla milk_bucket clears every active registered buff
     * (FD's own Comfort / Nourishment and BAC's Tipsy / Sweet Heart / Raging / Intoxication via the
     * addon registration); any custom item carrying the farmersdelight:milk tag (milk_bottle) removes
     * exactly one, preferring non-low-priority entries — same rule as the original mod's
     * brewinandchewin:low_priority/milk_bottle effect tag.
     */
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

    /**
     * The milk-bottle cleanser: removes exactly ONE effect at random, mirroring the mod's MilkBottleItem
     * (which picks uniformly from the drinker's milk-curable effects). The candidate pool is every active
     * vanilla potion effect plus every active non-low-priority custom buff — each an equal-weight candidate.
     * Low-priority custom buffs (BAC's booze, tagged brewinandchewin:low_priority/milk_bottle) form a fallback
     * pool used only when nothing else is curable, so a milk bottle sobers you up only as a last resort.
     */
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

