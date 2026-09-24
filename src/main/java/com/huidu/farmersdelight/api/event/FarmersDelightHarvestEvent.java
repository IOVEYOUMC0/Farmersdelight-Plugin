package com.huidu.farmersdelight.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApiStatus.NonExtendable
public class FarmersDelightHarvestEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final Location location;
    private final String blockId;
    private final ItemStack tool;
    private final List<ItemStack> drops;

    public FarmersDelightHarvestEvent(Player player, Location location, String blockId,
                                      ItemStack tool, List<ItemStack> drops) {
        this.playerId = player == null ? null : player.getUniqueId();
        this.playerName = player == null ? null : player.getName();
        this.location = location == null ? null : location.clone();
        this.blockId = blockId;
        this.tool = tool == null ? null : tool.clone();
        this.drops = copyDrops(drops);
    }

    private static List<ItemStack> copyDrops(List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) {
            return List.of();
        }
        List<ItemStack> copies = new ArrayList<>(drops.size());
        for (ItemStack drop : drops) {
            if (drop != null) {
                copies.add(drop.clone());
            }
        }
        return Collections.unmodifiableList(copies);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    public String getBlockId() {
        return blockId;
    }

    public ItemStack getTool() {
        return tool == null ? null : tool.clone();
    }

    public List<ItemStack> getDrops() {
        return drops;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
