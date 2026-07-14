package com.huidu.farmersdelight.api.buff;

import org.bukkit.entity.Player;

/**
 * A server-side custom buff an addon tracks outside of vanilla PotionEffects — typical examples
 * are FarmersDelight's Comfort / Nourishment and Brewin' And Chewin's Tipsy / Sweet Heart / Raging /
 * Intoxication, which keep their own per-player state maps that vanilla milk_bucket can't see.
 *
 * <p>Register implementations through CustomBuffRegistry#register during your plugin's
 * onEnable. FarmersDelight's central milk-consume listener will then route:
 * <ul>
 *   <li>minecraft:milk_bucket → remove every active registered buff from the drinker
 *       (vanilla "milk wipes all" semantics, extended to custom state);</li>
 *   <li>farmersdelight:milk_bottle → remove exactly one active buff, preferring entries with
 *       isLowPriority() returning false so the original mod's
 *       brewinandchewin:low_priority/milk_bottle tag semantics carry over.</li>
 * </ul>
 *
 * <p>Implementations must be idempotent: remove(Player) on a player without the buff is a
 * no-op, and isActive(Player) returns false both before activation and after removal.
 */
public interface CustomBuff {

    /** Stable id used for de-duplication in the registry and (if you want) logging. */
    String id();

    /** True iff player currently has this buff active. */
    boolean isActive(Player player);

    /** Drop the buff from player. Must be safe to call when the buff isn't currently active
     *  and from the player's region thread; the FD listener invokes this from
     *  PlayerItemConsumeEvent, which runs on the consuming player's thread. */
    void remove(Player player);

    /** Grant this buff to player at level (1-based, mirroring level(Player):
     *  1 = baseline / amp 0) for durationSeconds. Powers admin grant tooling (the
     *  /fd buff give command) so every registered buff can be handed out through the registry
     *  without the command knowing each addon's apply API. Return true when the buff was applied,
     *  false when this buff can't be granted programmatically (the default) so callers can report
     *  it. Implementations run on the target player's region thread. */
    default boolean apply(Player player, int level, int durationSeconds) {
        return false;
    }

    /** When a single-buff cleanser (milk_bottle) picks a buff to remove and any non-low-priority buff
     *  is currently active, low-priority ones are skipped. Default false; flip to true
     *  for "ambient drunk meter"-style buffs (Tipsy) so the bottle prefers to cancel one of the harder
     *  effects first, matching the original low_priority/milk_bottle effect tag from BAC. */
    default boolean isLowPriority() {
        return false;
    }

    /** Current effective level for player — 1-based (1 = baseline / amp 0,
     *  2 = amp 1, …), or 0 when inactive. Powers HUD placeholders that show
     *  the buff's current strength. Default: returns 1 when isActive(Player) is true,
     *  0 otherwise. */
    default int level(Player player) {
        return isActive(player) ? 1 : 0;
    }

    /** Seconds the buff has left on player, or 0 when inactive. Powers HUD
     *  placeholders that show the buff's remaining duration. Default: 0 (unknown / not
     *  reported). */
    default int remainingSeconds(Player player) {
        return 0;
    }

    /** Translatable lang key for the buff's display name (e.g. "buff.farmersdelight.comfort").
     *  Used by HUD placeholders that print the buff name. Default: empty string (the placeholder will
     *  fall back to the buff's id()). */
    default String nameKey() {
        return "";
    }

    /** Persist this buff's live state onto player so it survives relog / restart — and, on a
     *  network that syncs the whole player PDC (HuskSync / MySQLPlayerDataBridge etc.), carries across
     *  servers. FarmersDelight's central persistence lifecycle calls this on quit while the buff's
     *  in-memory state is still intact. Store under your own namespaced PDC keys.
     *
     *  <p>Default no-op — implement only for buffs that should survive a relog; genuinely ephemeral
     *  effects can stay in-memory. An inactive buff should remove its keys so no stale entry lingers. */
    default void saveState(Player player) {
    }

    /** Restore this buff's state onto player from wherever saveState(Player) wrote it.
     *  FarmersDelight calls this on join and once more after a short (configurable) delay, so that
     *  whole-profile sync plugins which apply the synced PDC a moment after join are still caught.
     *
     *  <p>MUST be gap-filling: if the buff is already active (from the immediate restore, or a dose the
     *  player gained since joining), leave it untouched — otherwise the retry would clobber live state.
     *  Default no-op. */
    default void restoreState(Player player) {
    }
}
