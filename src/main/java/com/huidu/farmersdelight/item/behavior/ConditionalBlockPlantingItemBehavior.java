package com.huidu.farmersdelight.item.behavior;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import com.huidu.farmersdelight.util.VanillaAdvancements;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Item behavior that picks a CraftEngine block to place based on which block the player right-clicked.
 * Each rule maps a clicked block id (CE custom id or minecraft:<material>) to a CE block id;
 * on click the behavior identifies the clicked block, looks it up in the rules, and places the mapped
 * CE block at the slot adjacent to the clicked face. When no rule matches it returns
 * InteractionResult#PASS, leaving the next behavior — or the unmodified vanilla item logic —
 * to handle the click. So a wheat-seed-style click on plain farmland falls through to vanilla wheat,
 * while a click on a configured custom soil places the configured CE crop instead.
 *
 * YAML:
 * <pre>
 * items:
 *   minecraft:wheat_seeds:
 *     behavior:
 *       type: farmersdelight:conditional_block_planting
 *       rules:
 *         - target: farmersdelight:rich_soil_farmland
 *           block:  farmersdelight:rich_wheat
 * </pre>
 *
 * Both target and block are namespaced ids. Vanilla blocks are matched as
 * minecraft:<material_name_lowercase>.
 */
public final class ConditionalBlockPlantingItemBehavior extends ItemBehavior {

    public static final ItemBehaviorFactory<ConditionalBlockPlantingItemBehavior> FACTORY = new Factory();

    private final Map<Key, Key> rules;

    private ConditionalBlockPlantingItemBehavior(Map<Key, Key> rules) {
        this.rules = rules;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        // Only the top face counts as planting; side/bottom clicks pass through to their normal meaning.
        if (context.getClickedFace() != Direction.UP) {
            return InteractionResult.PASS;
        }
        World world = (World) context.getLevel().platformWorld();
        BlockPos clickedPos = context.getClickedPos();
        Block clickedBlock = world.getBlockAt(clickedPos.x(), clickedPos.y(), clickedPos.z());

        Key clickedId = identifyBlock(clickedBlock);
        Key blockToPlace = rules.get(clickedId);
        if (blockToPlace == null) {
            return InteractionResult.PASS;
        }

        Optional<BlockDefinition> blockDef = BukkitBlockManager.instance().blockById(blockToPlace);
        if (blockDef.isEmpty()) {
            CraftEngine.instance().logger().warn(
                    "ConditionalBlockPlantingItemBehavior: rule for " + clickedId
                            + " references unknown block " + blockToPlace);
            return InteractionResult.PASS;
        }

        BlockPos placePos = clickedPos.relative(context.getClickedFace());
        Block target = world.getBlockAt(placePos.x(), placePos.y(), placePos.z());
        if (!target.getType().isAir()) {
            return InteractionResult.PASS;
        }

        // R-SEC-001: this path intercepts the click and places the block itself via CraftEngineBlocks.place,
        // so vanilla's own WorldGuard build check never fires — gate on canBuild (master flag) here, or a
        // player without build rights could plant inside a protected region.
        Player player = context.getPlayer();
        org.bukkit.entity.Player bukkitPlayer = player == null ? null : Bukkit.getPlayer(player.uuid());
        if (bukkitPlayer != null && !ProtectionCompat.canBuild(bukkitPlayer, target)) {
            return InteractionResult.PASS;
        }

        Location loc = new Location(world, placePos.x() + 0.5, placePos.y(), placePos.z() + 0.5);
        boolean placed = CraftEngineBlocks.place(loc, blockDef.get().defaultState(), true);
        if (!placed) {
            return InteractionResult.PASS;
        }

        if (player != null) {
            if (!player.isCreativeMode()) {
                Item item = context.getItem();
                item.shrink(1);
            }
            player.swingHand(context.getHand());
        }
        // The crop was placed via CraftEngineBlocks.place, bypassing vanilla's placed_block trigger, so award
        // the vanilla "A Seedy Place" advancement manually.
        if (bukkitPlayer != null) {
            VanillaAdvancements.grantPlantSeed(bukkitPlayer);
        }
        return InteractionResult.SUCCESS;
    }

    /** CE custom id if the block is a CraftEngine block, else minecraft:<material>. */
    private static Key identifyBlock(Block block) {
        String customId = CustomBlockUtils.getId(block);
        if (customId != null) {
            return Key.of(customId);
        }
        return Key.of("minecraft", block.getType().getKey().getKey());
    }

    private static final class Factory implements ItemBehaviorFactory<ConditionalBlockPlantingItemBehavior> {
        @Override
        public ConditionalBlockPlantingItemBehavior create(Pack pack, Path path, Key key, ConfigSection section) {
            List<Object> rawRules = section.getList("rules");
            Map<Key, Key> rules = new HashMap<>();
            if (rawRules != null) {
                for (Object entry : rawRules) {
                    if (!(entry instanceof Map<?, ?> map)) continue;
                    Object t = map.get("target");
                    Object b = map.get("block");
                    if (t == null || b == null) continue;
                    rules.put(Key.of(t.toString()), Key.of(b.toString()));
                }
            }
            return new ConditionalBlockPlantingItemBehavior(rules);
        }
    }
}
