package fr.ateastudio.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class ProfessionCookingExperienceEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final String source;
    private final ItemStack result;
    private final float baseExperience;

    public ProfessionCookingExperienceEvent(UUID playerId, String playerName, String source, ItemStack result, float baseExperience) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.source = source;
        this.result = result == null ? null : result.clone();
        this.baseExperience = baseExperience;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getSource() {
        return source;
    }

    public ItemStack getResult() {
        return result == null ? null : result.clone();
    }

    public float getBaseExperience() {
        return baseExperience;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
