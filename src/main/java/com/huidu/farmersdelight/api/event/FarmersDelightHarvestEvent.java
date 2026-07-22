package com.huidu.farmersdelight.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Fired when a player harvests a FarmersDelight crop through one of the plugin's Java harvest
 * handlers — right-clicking a mushroom colony with shears or a knife, and harvesting mature rice.
 * A notification hook for stats / quests / economy integrations.
 *
 * Not cancellable. Vetoing a harvest belongs to the protection layer: FarmersDelight already asks
 * its ProtectionCompat facade (WorldGuard flags plus AntiGriefLib's 24+ land plugins) before every
 * harvest, and this event is fired only after that check has passed and before any drop is spawned.
 * A land plugin that wants to block harvesting should expose itself through that facade rather than
 * listen here, so the interaction is denied cleanly instead of half-applied.
 *
 * Which harvests this does NOT cover
 * The tomato harvest is not reported. Tomato plants are implemented entirely in CraftEngine YAML
 * (an on: right_click function chain in the block's configuration) with no Java handler for
 * this event to hook, so there is nothing on FarmersDelight's side that observes the harvest.
 * Integrators who need tomato harvests must listen to CraftEngine's own
 * CustomBlockInteractEvent and filter on the block id (farmersdelight:tomatoes /
 * farmersdelight:budding_tomatoes / farmersdelight:tomato_crop_on_rope), matching the approach
 * FarmersDelight's own WorldGuard integration uses for that block.
 *
 * Drops
 * getDrops() is best-effort. It lists the items FarmersDelight is about to spawn when the
 * handler computes them itself (the mushroom colony). It is empty when the drops come from a
 * CraftEngine loot table or a configured break-loot function chain evaluated inside CraftEngine
 * (mature rice), because those items are produced by the engine and never pass through FarmersDelight
 * as a list. An empty list therefore means "not enumerable", not "no drops". The list and its stacks
 * are copies: editing them changes nothing.
 */
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
        List<ItemStack> copies = new java.util.ArrayList<>(drops.size());
        for (ItemStack drop : drops) {
            if (drop != null) {
                copies.add(drop.clone());
            }
        }
        return Collections.unmodifiableList(copies);
    }

    /** The harvesting player's unique id. */
    public UUID getPlayerId() {
        return playerId;
    }

    /** The harvesting player's name at the time of the harvest (may be null). */
    public String getPlayerName() {
        return playerName;
    }

    /** The harvested block's location (block-aligned corner). */
    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    /** The CraftEngine block id that was harvested, e.g. farmersdelight:brown_mushroom_colony. */
    public String getBlockId() {
        return blockId;
    }

    /** A copy of the tool used for the harvest (shears, knife, ...); null when harvested bare-handed. */
    public ItemStack getTool() {
        return tool == null ? null : tool.clone();
    }

    /** Copies of the items about to be dropped; empty when the drops are not enumerable (see the
     *  class javadoc). Unmodifiable — editing it does not change what drops. */
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
