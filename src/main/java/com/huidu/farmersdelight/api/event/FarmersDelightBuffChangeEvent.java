package com.huidu.farmersdelight.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/**
 * Fired when a registered custom buff's level on a player really changes — gained (0 to N), lost
 * (N to 0), or moved between non-zero levels. It is NOT fired when a buff is merely refreshed:
 * re-drinking at the same level pushes the duration out without changing the level, which is by far
 * the most common update and would otherwise flood listeners.
 *
 * The transition is detected inside
 * com.huidu.farmersdelight.api.buff.CustomBuffRegistry against a last-known-level map, the
 * same last-pushed-value diff the buff bossbar renderer uses, so callers can notify the registry as
 * often as they like (every tick, if that is simplest) and still see one event per real change.
 * Addons that keep their own buff state should call
 * CustomBuffRegistry.syncState(player, buffId) after mutating it; the registry's own grant and
 * clear paths (admin grant, milk bucket, milk bottle) sync themselves.
 *
 * Not cancellable — the buff has already changed by the time this fires. Fired outside any station
 * or block-entity lock, on the thread that performed the change, which for buff work is the affected
 * player's region thread.
 */
@ApiStatus.NonExtendable
public class FarmersDelightBuffChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final String buffId;
    private final int previousLevel;
    private final int newLevel;
    private final int remainingSeconds;

    public FarmersDelightBuffChangeEvent(Player player, String buffId,
                                         int previousLevel, int newLevel, int remainingSeconds) {
        this.playerId = player == null ? null : player.getUniqueId();
        this.playerName = player == null ? null : player.getName();
        this.buffId = buffId;
        this.previousLevel = previousLevel;
        this.newLevel = newLevel;
        this.remainingSeconds = remainingSeconds;
    }

    /** The affected player's unique id. */
    public UUID getPlayerId() {
        return playerId;
    }

    /** The affected player's name at the time of the change (may be null). */
    public String getPlayerName() {
        return playerName;
    }

    /** The registered buff id, e.g. farmersdelight:comfort or brewinandchewin:tipsy. */
    public String getBuffId() {
        return buffId;
    }

    /** The level before the change; 0 means the player did not have the buff. */
    public int getPreviousLevel() {
        return previousLevel;
    }

    /** The level after the change; 0 means the buff was lost. Levels are 1-based, so 1 is the
     *  baseline strength (potion amplifier 0). */
    public int getNewLevel() {
        return newLevel;
    }

    /** Seconds left on the buff after the change, or 0 when it was lost or reports no duration. */
    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    /** True when the player did not have this buff before and does now. */
    public boolean isGained() {
        return previousLevel == 0 && newLevel > 0;
    }

    /** True when the player had this buff before and no longer does. */
    public boolean isLost() {
        return previousLevel > 0 && newLevel == 0;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
