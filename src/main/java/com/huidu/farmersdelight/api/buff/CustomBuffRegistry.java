package com.huidu.farmersdelight.api.buff;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry that lets FarmersDelight's milk-consume listener clear addon-side custom buffs without
 * knowing each addon's manager API. See CustomBuff for the contract.
 *
 * <p>Lifecycle: addons register during onEnable and
 * unregister during onDisable. Registration is idempotent
 * by CustomBuff#id() — re-registering with the same id replaces the previous entry, which
 * matches what a /plugman reload would naturally do.
 *
 * <p>Iteration is COW-snapshot based, so reads (the consume listener) and writes (a plugin enabling
 * mid-game) don't lock against each other.
 */
public final class CustomBuffRegistry {

    private static final List<CustomBuff> ENTRIES = new CopyOnWriteArrayList<>();
    // Parallel id→buff index so PAPI placeholder lookups (called from HUD plugins at tick rate ×
    // online-player count × placeholder count) avoid the prior O(N) linear scan + ArrayList copy.
    // Kept in sync with ENTRIES under the assumption that register/unregister only fire on plugin
    // enable/disable — those code paths are single-threaded and rare, so the two-store cost is fine.
    private static final Map<String, CustomBuff> BY_ID = new ConcurrentHashMap<>();
    private static final List<CustomBuff> ENTRIES_VIEW = Collections.unmodifiableList(ENTRIES);

    private CustomBuffRegistry() {
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
     * Persist every registered buff's state onto player (see CustomBuff#saveState).
     * Drives FarmersDelight's central buff-persistence lifecycle on quit; per-buff exceptions are
     * swallowed so one misbehaving addon can't block the rest of the save.
     */
    public static void saveAll(Player player) {
        if (player == null) return;
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
        if (player == null) return;
        for (CustomBuff buff : ENTRIES) {
            try {
                buff.restoreState(player);
            } catch (RuntimeException ignored) {
                // Per-buff isolation — see saveAll.
            }
        }
    }

    /**
     * Grant the registered buff with id to player at level (1-based) for
     * durationSeconds (see CustomBuff#apply). Returns true when a buff with
     * that id is registered and accepted the grant, false when the id is unknown or the buff
     * can't be granted. Drives the /fd buff give admin command.
     */
    public static boolean apply(Player player, String id, int level, int durationSeconds) {
        if (player == null || id == null) return false;
        CustomBuff buff = BY_ID.get(id);
        if (buff == null) return false;
        try {
            return buff.apply(player, level, durationSeconds);
        } catch (RuntimeException ignored) {
            // A misbehaving addon's apply must not crash the command handler.
            return false;
        }
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
            }
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
                return buff;
            } catch (RuntimeException ignored) {
            }
        }
        if (lowPriorityFallback != null) {
            try {
                lowPriorityFallback.remove(player);
            } catch (RuntimeException ignored) {
                return null;
            }
            return lowPriorityFallback;
        }
        return null;
    }
}
