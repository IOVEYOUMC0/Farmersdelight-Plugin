package com.huidu.farmersdelight.item.behavior;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
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
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

        // This path places directly through CraftEngineBlocks.place and cancels native interaction.
        // Check canBuild here because native placement protection will not run.
        Player player = context.getPlayer();
        org.bukkit.entity.Player bukkitPlayer = player != null
                && player.platformPlayer() instanceof org.bukkit.entity.Player platformPlayer
                ? platformPlayer : null;
        if (bukkitPlayer != null && !ProtectionCompat.canPlace(bukkitPlayer, target, null)) {
            return InteractionResult.FAIL;
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
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

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
                    if (!(entry instanceof Map<?, ?> map)) {
                        CraftEngine.instance().logger().warn("ConditionalBlockPlantingItemBehavior: " + key
                                + " has a rule that is not a section, ignoring it");
                        continue;
                    }
                    Object t = map.get("target");
                    Object b = map.get("block");
                    if (t == null || b == null) {
                        CraftEngine.instance().logger().warn("ConditionalBlockPlantingItemBehavior: " + key
                                + " has a rule missing 'target' or 'block', ignoring it");
                        continue;
                    }
                    rules.put(Key.of(t.toString()), Key.of(b.toString()));
                }
            }
            // The rules are the entire behavior: with none of them every click returns PASS and the item is
            // indistinguishable from one that never declared the behavior at all. Report it against the item's
            // config node so the mistake is visible instead of leaving an author to wonder why their seed still
            // plants the vanilla crop. CraftEngine keeps the item and drops only its behavior.
            if (rules.isEmpty()) {
                throw KnownResourceException.missingArgument("rules", ConfigConstants.ARGUMENT_LIST);
            }
            return new ConditionalBlockPlantingItemBehavior(rules);
        }
    }
}
