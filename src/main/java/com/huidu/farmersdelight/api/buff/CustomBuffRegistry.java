package com.huidu.farmersdelight.api.buff;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry that lets FarmersDelight's milk-consume listener clear addon-side custom buffs without
 * knowing each addon's manager API. See CustomBuff for the contract.
 *
 * Lifecycle: addons call register(CustomBuff) during onEnable and unregister(CustomBuff) during
 * onDisable. Registration is idempotent by CustomBuff.id() — re-registering with the same id replaces
 * the previous entry, which matches what a /plugman reload would naturally do.
 *
 * Iteration is COW-snapshot based, so reads (the consume listener) and writes (a plugin enabling
 * mid-game) don't lock against each other.
 *
 * The registry also raises FarmersDelightBuffChangeEvent on real level transitions — see
 * syncState(Player, String) for how an addon reports its own state changes.
 */
@ApiStatus.NonExtendable
public final class CustomBuffRegistry {

    private static final List<CustomBuff> ENTRIES = new CopyOnWriteArrayList<>();
    // Parallel id→buff index so PAPI placeholder lookups (called from HUD plugins at tick rate ×
    // online-player count × placeholder count) avoid the prior O(N) linear scan + ArrayList copy.
    // Kept in sync with ENTRIES under the assumption that register/unregister only fire on plugin
    // enable/disable — those code paths are single-threaded and rare, so the two-store cost is fine.
    private static final Map<String, CustomBuff> BY_ID = new ConcurrentHashMap<>();
    private static final List<CustomBuff> ENTRIES_VIEW = Collections.unmodifiableList(ENTRIES);
    // Last level observed per (player, buff id) — the diff cache that turns the "push the current
    // state as often as you like" contract into one FarmersDelightBuffChangeEvent per real transition.
    // Same shape as the bossbar renderer's last-pushed-value cache, and required for the same reason:
    // an addon ticking its own buff cannot know what the registry last reported, so the registry has
    // to remember. An entry is dropped as soon as the level returns to 0, so the map holds only
    // currently-buffed players; forget drops what is left when a player disconnects still buffed.
    private static final Map<UUID, Map<String, Integer>> LAST_LEVELS = new ConcurrentHashMap<>();
    // Buff master switch, mirrored here from FarmersDelight's config (buff.enabled) so every entry point can
    // check it with one field read instead of reaching back through the plugin singleton. Written by the
    // config load / reload path and read from region and tick threads, so it is volatile (R-CONC-002).
    // Registration still works while it is off — an addon enabling into a switched-off server must not fail,
    // it just gets no grants until an admin turns the system back on.
    private static volatile boolean systemEnabled = true;

    private CustomBuffRegistry() {
    }

    /** Publishes the buff.enabled master switch. Called by FarmersDelight's config load. */
    @ApiStatus.Internal
    public static void setSystemEnabled(boolean enabled) {
        systemEnabled = enabled;
    }

    /**
     * True while the buff system is switched on. When false no buff is granted, restored, ticked or drawn;
     * every entry point below stays a safe no-op rather than throwing, so an addon can call the registry
     * unconditionally. Addons can read this to skip their own per-tick buff work entirely.
     */
    public static boolean isSystemEnabled() {
        return systemEnabled;
    }

    /** Add buff to the registry. If a buff with the same CustomBuff#id() is already
     *  registered, that entry is replaced (mirrors a clean re-register after a plugin reload). */
    public static void register(CustomBuff buff) {
        Objects.requireNonNull(buff, "buff");
        Objects.requireNonNull(buff.id(), "buff.id()");
        ENTRIES.removeIf(existing -> existing.id().equals(buff.id()));
        ENTRIES.add(buff);
        BY_ID.put(buff.id(), buff);
    }

    /** Drop the registered entry whose id matches buff.id(). No-op when not present. */
    public static void unregister(CustomBuff buff) {
        if (buff == null || buff.id() == null) return;
        ENTRIES.removeIf(existing -> existing.id().equals(buff.id()));
        BY_ID.remove(buff.id());
    }

    /** Drop the registered entry by id. No-op when not present. */
    public static void unregister(String id) {
        if (id == null) return;
        ENTRIES.removeIf(existing -> existing.id().equals(id));
        BY_ID.remove(id);
    }

    /** All registered buffs in insertion order as an unmodifiable view over the live COW list.
     *  No allocation per call — safe to iterate while a concurrent register/unregister mutates the
     *  underlying list (CopyOnWriteArrayList provides snapshot iteration). */
    public static List<CustomBuff> all() {
        return ENTRIES_VIEW;
    }

    /** O(1) lookup by registered buff id (e.g. "brewinandchewin:tipsy"). Returns null
     *  when no buff with that id is currently registered. Drives the PlaceholderAPI hot path. */
    public static CustomBuff byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /**
     * Re-read the buff registered under buffId on player and raise a
     * FarmersDelightBuffChangeEvent if — and only if — its level actually moved since the last
     * time this player/buff pair was looked at (gained, lost, or level changed). A call that finds the
     * same level as before does nothing at all: no event, no allocation beyond the lookup. That is what
     * makes it safe to call this after every state mutation, including a duration refresh, without
     * turning the event into a per-tick firehose.
     *
     * An addon that keeps its own buff state calls this after mutating it (granting a dose,
     * decaying a level, expiring a timer). The registry's own mutating paths — apply,
     * clearAll, clearOne, restoreAll — already sync themselves, so an addon
     * that only grants through those does not need to call this at all.
     *
     * Returns true when a transition was detected and the event was fired.
     */
    public static boolean syncState(Player player, String buffId) {
        return syncState(player, byId(buffId));
    }

    /** #syncState(Player, String) for a buff instance you already hold. */
    public static boolean syncState(Player player, CustomBuff buff) {
        if (player == null || buff == null || buff.id() == null) {
            return false;
        }
        int level;
        int remaining;
        try {
            level = Math.max(0, buff.level(player));
            remaining = level > 0 ? Math.max(0, buff.remainingSeconds(player)) : 0;
        } catch (RuntimeException ignored) {
            // A broken addon reporter must not break the caller that triggered the sync.
            return false;
        }
        Integer previous = swapLastLevel(player.getUniqueId(), buff.id(), level);
        int previousLevel = previous == null ? 0 : previous;
        if (previousLevel == level) {
            return false;
        }
        fireChange(new FarmersDelightBuffChangeEvent(player, buff.id(), previousLevel, level, remaining));
        return true;
    }

    /**
     * Delivers a change event for a transition the diff cache has already consumed, so it must not be
     * dropped. PluginManager rejects a synchronous event dispatched from a non-tick thread by throwing, and
     * an addon that decays its buff on an async task would therefore commit the transition and then lose the
     * event with nothing able to re-derive it. Off a tick thread the finished event is handed to the global
     * region instead, so the caller returns normally and listeners still see it.
     */
    private static void fireChange(FarmersDelightBuffChangeEvent event) {
        if (Bukkit.isPrimaryThread()) {
            callChange(event);
            return;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !FarmersDelightPlugin.isEnabled0()) {
            // No scheduler to hand it to (the plugin is down); dispatching inline is the only option left
            // and callChange absorbs the rejection.
            callChange(event);
            return;
        }
        plugin.scheduler().run(() -> callChange(event));
    }

    private static void callChange(FarmersDelightBuffChangeEvent event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException ignored) {
            // A listener throwing must not roll back the buff change that already happened.
        }
    }

    /** Run #syncState(Player, CustomBuff) for every registered buff. Useful after a bulk
     *  change (a relog restore, an admin wipe) when the caller doesn't know which buffs moved. */
    public static void syncAll(Player player) {
        if (player == null) return;
        for (CustomBuff buff : ENTRIES) {
            syncState(player, buff);
        }
    }

    /**
     * Re-syncs every player the diff cache still holds a level for. A buff that simply runs out of time is
     * expired by whoever owns it — FarmersDelight's own effect ticker, an addon's timer — and none of those
     * paths go through the registry, so without this pass the loss half of the transition would never be
     * reported. Cheap when nobody is buffed: the cache is empty and the pass returns on the first read.
     *
     * Each player is synced on their own scheduler, because reading a buff's level means reading state the
     * owning region thread mutates. A player who is no longer online is dropped from the cache instead.
     */
    @ApiStatus.Internal
    public static void syncTrackedPlayers() {
        if (LAST_LEVELS.isEmpty()) {
            return;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        for (UUID playerId : LAST_LEVELS.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                LAST_LEVELS.remove(playerId);
                continue;
            }
            if (plugin == null) {
                syncAll(player);
            } else {
                plugin.scheduler().runForEntity(player, () -> syncAll(player));
            }
        }
    }

    /**
     * Drop player's cached buff levels. Call on quit: the cache exists only to diff against
     * live state, and a disconnected player has none. Purely hygiene — a player who reconnects is
     * diffed from scratch, which at worst re-reports a buff they still have as newly gained.
     */
    public static void forget(Player player) {
        if (player != null) {
            LAST_LEVELS.remove(player.getUniqueId());
        }
    }

    /** Records level for the pair and returns what was there before (null = never seen).
     *  Storing 0 is the same as forgetting, so the map only ever holds currently-buffed players. */
    private static Integer swapLastLevel(UUID playerId, String buffId, int level) {
        if (level <= 0) {
            Map<String, Integer> levels = LAST_LEVELS.get(playerId);
            if (levels == null) {
                return null;
            }
            Integer previous = levels.remove(buffId);
            // Racy but benign: a concurrent put for another buff can land between the isEmpty check
            // and the outer remove. This is only a diff cache, so the worst outcomes are one leftover
            // empty map, or one spurious "gained" event on the next sync for that other buff.
            if (levels.isEmpty()) {
                LAST_LEVELS.remove(playerId, levels);
            }
            return previous;
        }
        return LAST_LEVELS.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>()).put(buffId, level);
    }

    /**
     * Persist every registered buff's state onto player (see CustomBuff#saveState).
     * Drives FarmersDelight's central buff-persistence lifecycle on quit; per-buff exceptions are
     * swallowed so one misbehaving addon can't block the rest of the save.
     */
    public static void saveAll(Player player) {
        // Gated on the master switch for the same reason restoreAll is, but the consequence here is worse:
        // switching the system off clears the live buff state, so an ungated save would write that emptiness
        // over the player's stored buffs on their next quit and destroy it permanently. Skipping the write
        // leaves whatever is already stored intact, ready for the system being switched back on.
        if (player == null || !systemEnabled) return;
        for (CustomBuff buff : ENTRIES) {
            try {
                buff.saveState(player);
            } catch (RuntimeException ignored) {
                // Per-buff isolation — a broken addon serializer must not abort the others.
            }
        }
    }

    /**
     * Restore every registered buff's state onto player (see CustomBuff#restoreState).
     * Called on join and again after the configured retry delay; restoreState is gap-filling
     * so the repeat call is safe.
     */
    public static void restoreAll(Player player) {
        // Restoring would put a buff straight back into play, so the master switch has to gate it too. The
        // saved state stays on the player, so nothing is lost if the system is switched on again.
        if (player == null || !systemEnabled) return;
        for (CustomBuff buff : ENTRIES) {
            try {
                buff.restoreState(player);
            } catch (RuntimeException ignored) {
                // Per-buff isolation — see saveAll.
                continue;
            }
            // A restore that actually put a buff back is a 0-to-N transition worth reporting; the
            // second, delayed restore is gap-filling, so it finds the same level and stays silent.
            syncState(player, buff);
        }
    }

    /**
     * Grant the registered buff with id to player at level (1-based) for
     * durationSeconds (see CustomBuff#apply). Returns true when a buff with
     * that id is registered and accepted the grant, false when the id is unknown or the buff
     * can't be granted. Drives the /fd buff give admin command.
     */
    public static boolean apply(Player player, String id, int level, int durationSeconds) {
        // Reports "not granted" rather than throwing when the system is off, which is the same answer an
        // addon already handles for an unknown or non-grantable buff id.
        if (player == null || id == null || !systemEnabled) return false;
        CustomBuff buff = BY_ID.get(id);
        if (buff == null) return false;
        boolean applied;
        try {
            applied = buff.apply(player, level, durationSeconds);
        } catch (RuntimeException ignored) {
            // A misbehaving addon's apply must not crash the command handler.
            return false;
        }
        // Outside the try so a listener's exception can't be mistaken for a failed grant. syncState is
        // a no-op when the grant only refreshed an already-held level.
        if (applied) {
            syncState(player, buff);
        }
        return applied;
    }

    /**
     * Remove every active registered buff from player — vanilla milk_bucket semantics
     * extended to custom state. Returns the count actually removed.
     */
    public static int clearAll(Player player) {
        if (player == null) return 0;
        int removed = 0;
        for (CustomBuff buff : ENTRIES) {
            try {
                if (buff.isActive(player)) {
                    buff.remove(player);
                    removed++;
                }
            } catch (RuntimeException ignored) {
                // A misbehaving addon shouldn't break the whole milk wipe; swallow per-buff so the
                // remaining registry entries still get their chance.
                continue;
            }
            syncState(player, buff);
        }
        return removed;
    }

    /**
     * The registered buffs currently active on player (both priorities). Used by the milk-bottle
     * cleanser to weigh custom buffs alongside vanilla effects when picking one to remove.
     */
    public static java.util.List<CustomBuff> activeBuffs(Player player) {
        java.util.List<CustomBuff> active = new java.util.ArrayList<>();
        if (player == null) {
            return active;
        }
        for (CustomBuff buff : ENTRIES) {
            try {
                if (buff.isActive(player)) {
                    active.add(buff);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return active;
    }

    /**
     * Remove exactly one active buff from player, preferring non-low-priority entries.
     * Returns the removed buff (or null when nothing was active). Mirrors the original
     * farmersdelight:milk_bottle semantics modulated by BAC's
     * brewinandchewin:low_priority/milk_bottle effect tag.
     */
    public static CustomBuff clearOne(Player player) {
        if (player == null) return null;
        CustomBuff lowPriorityFallback = null;
        for (CustomBuff buff : ENTRIES) {
            try {
                if (!buff.isActive(player)) continue;
                if (buff.isLowPriority()) {
                    if (lowPriorityFallback == null) lowPriorityFallback = buff;
                    continue;
                }
                buff.remove(player);
            } catch (RuntimeException ignored) {
                continue;
            }
            syncState(player, buff);
            return buff;
        }
        if (lowPriorityFallback != null) {
            try {
                lowPriorityFallback.remove(player);
            } catch (RuntimeException ignored) {
                return null;
            }
            syncState(player, lowPriorityFallback);
            return lowPriorityFallback;
        }
        return null;
    }
}
