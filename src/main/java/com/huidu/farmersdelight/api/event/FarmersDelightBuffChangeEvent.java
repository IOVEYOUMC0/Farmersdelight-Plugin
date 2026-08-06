package com.huidu.farmersdelight.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

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

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getBuffId() {
        return buffId;
    }

    public int getPreviousLevel() {
        return previousLevel;
    }

    public int getNewLevel() {
        return newLevel;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    public boolean isGained() {
        return previousLevel == 0 && newLevel > 0;
    }

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
