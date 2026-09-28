package com.huidu.farmersdelight.api.buff;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.PluginAccess;
import com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ApiStatus.NonExtendable
public final class CustomBuffRegistry {

    private static final List<CustomBuff> ENTRIES = new CopyOnWriteArrayList<>();
    // Index buffs by ID for frequent placeholder lookups without scanning the registration list.
    // Registration and removal update both stores during the plugin lifecycle.
    private static final Map<String, CustomBuff> BY_ID = new ConcurrentHashMap<>();
    private static final List<CustomBuff> ENTRIES_VIEW = Collections.unmodifiableList(ENTRIES);
    // Last level observed per (player, buff id) — the diff cache that turns the "push the current
    // state as often as you like" contract into one FarmersDelightBuffChangeEvent per real transition.
    // Same shape as the bossbar renderer's last-pushed-value cache, and required for the same reason:
    // an addon ticking its own buff cannot know what the registry last reported, so the registry has
    // to remember. An entry is dropped as soon as the level returns to 0, so the map holds only
    // currently-buffed players; forget drops what is left when a player disconnects still buffed.
    private static final Map<UUID, Map<String, Integer>> LAST_LEVELS = new ConcurrentHashMap<>();
    // Cache buff.enabled for a single-field check at every entry point.
    // The reload path publishes this volatile value to region and tick threads.
    // Addons may still register buffs while disabled; only granting buffs is suppressed.
    private static volatile boolean systemEnabled = true;

    private CustomBuffRegistry() {
    }

    @ApiStatus.Internal
    public static void setSystemEnabled(boolean enabled) {
        systemEnabled = enabled;
    }

    public static boolean isSystemEnabled() {
        return systemEnabled;
    }

    public static void register(CustomBuff buff) {
        Objects.requireNonNull(buff, "buff");
        Objects.requireNonNull(buff.id(), "buff.id()");
        ENTRIES.removeIf(existing -> existing.id().equals(buff.id()));
        ENTRIES.add(buff);
        BY_ID.put(buff.id(), buff);
    }

    public static void unregister(CustomBuff buff) {
        if (buff == null || buff.id() == null) return;
        ENTRIES.removeIf(existing -> existing.id().equals(buff.id()));
        BY_ID.remove(buff.id());
    }

    public static void unregister(String id) {
        if (id == null) return;
        ENTRIES.removeIf(existing -> existing.id().equals(id));
        BY_ID.remove(id);
    }

    public static List<CustomBuff> all() {
        return ENTRIES_VIEW;
    }

    public static CustomBuff byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static boolean syncState(Player player, String buffId) {
        return syncState(player, byId(buffId));
    }

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

    private static void fireChange(FarmersDelightBuffChangeEvent event) {
        if (Bukkit.isPrimaryThread()) {
            callChange(event);
            return;
        }
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin == null) {
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

    public static void syncAll(Player player) {
        if (player == null) return;
        for (CustomBuff buff : ENTRIES) {
            syncState(player, buff);
        }
    }

    @ApiStatus.Internal
    public static void syncTrackedPlayers() {
        if (LAST_LEVELS.isEmpty()) {
            return;
        }
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        for (UUID playerId : LAST_LEVELS.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                LAST_LEVELS.remove(playerId);
                continue;
            }
            if (plugin == null) {
                LAST_LEVELS.remove(playerId);
                continue;
            }
            plugin.scheduler().runForEntity(player, () -> {
                if (player.isOnline()) {
                    syncAll(player);
                } else {
                    LAST_LEVELS.remove(playerId);
                }
            }, () -> LAST_LEVELS.remove(playerId));
        }
    }

    public static void forget(Player player) {
        if (player != null) {
            LAST_LEVELS.remove(player.getUniqueId());
        }
    }

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

    /** Removes one named buff with the same exception isolation and transition sync as clearAll. */
    public static boolean clear(Player player, String id) {
        if (player == null || id == null) return false;
        CustomBuff buff = byId(id);
        if (buff == null) return false;
        try {
            if (!buff.isActive(player)) return false;
            buff.remove(player);
        } catch (RuntimeException ignored) {
            return false;
        }
        syncState(player, buff);
        return true;
    }

    public static List<CustomBuff> activeBuffs(Player player) {
        List<CustomBuff> active = new ArrayList<>();
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
