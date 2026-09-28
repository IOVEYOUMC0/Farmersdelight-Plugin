package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;

import java.util.Set;

public final class RiceCropRules {

    private static final Key RICE_CROP_ID = Key.of("farmersdelight:rice");

    private RiceCropRules() {
    }

    public static boolean isSourceWater(Block block) {
        if (block == null) {
            return false;
        }

        Material type = block.getType();
        if (type == Material.WATER) {
            if (block.getBlockData() instanceof Levelled levelled) {
                return levelled.getLevel() == 0;
            }
            return true;
        }
        return false;
    }

    public static boolean isSingleSourceWater(Block block) {
        return isSourceWater(block) && !isWaterBlock(block.getRelative(BlockFace.UP));
    }

    public static boolean isValidSoil(Block block) {
        return isValidSoil(block, RICE_CROP_ID);
    }

    public static boolean isValidSoil(Block block, Key cropId) {
        if (block == null) {
            return false;
        }

        SoilRuleSupport.SoilRules configuredRules = TallCropBlockBehavior.getSoilRules(cropId);
        if (configuredRules != null && configuredRules.isConfigured()) {
            return SoilRuleSupport.matches(block, configuredRules);
        }

        Material type = block.getType();
        if (type == Material.FARMLAND
                || type == Material.DIRT
                || type == Material.GRASS_BLOCK
                || type == Material.MUD
                || type == Material.CLAY
                || type == Material.SAND
                || type == Material.COARSE_DIRT
                || type == Material.ROOTED_DIRT
                || type == Material.PODZOL
                || type == Material.MYCELIUM) {
            return true;
        }

        // These can never be supporting soil; avoid a custom-block lookup for the common empty/fluid cases.
        if (type == Material.AIR || type == Material.WATER) {
            return false;
        }

        ImmutableBlockState customState = CraftEngineBlocks.getCustomBlockState(block);
        if (customState == null || customState.isEmpty()) {
            return false;
        }

        Set<Key> tags = customState.settings().tags();
        for (Key tag : tags) {
            String tagStr = tag.toString().toLowerCase();
            if (tagStr.contains("farmland") || tagStr.contains("soil") || tagStr.contains("dirt")) {
                return true;
            }
        }

        return false;
    }

    public static Block getWaterSourceBlock(Block plantingBlock, Block blockBelow, Key cropId) {
        if (isSourceWater(plantingBlock)) {
            return plantingBlock;
        }

        if (isSourceWater(blockBelow)) {
            return blockBelow;
        }

        return null;
    }

    public static Block getSupportingSoilBlock(Block plantingBlock, Block blockBelow, Key cropId, boolean requiresWater) {
        if (!requiresWater) {
            return blockBelow;
        }

        Block waterBlock = getWaterSourceBlock(plantingBlock, blockBelow, cropId);
        if (waterBlock != null) {
            return waterBlock.getRelative(BlockFace.DOWN);
        }

        return blockBelow;
    }

    public static boolean canPlantRiceAt(Block waterBlock) {
        return canPlantRiceAt(waterBlock, RICE_CROP_ID);
    }

    public static boolean canPlantRiceAt(Block waterBlock, Key cropId) {
        return isSingleSourceWater(waterBlock)
                && isValidSoil(waterBlock.getRelative(BlockFace.DOWN), cropId);
    }

    private static boolean isWaterBlock(Block block) {
        return block != null && block.getType() == Material.WATER;
    }

    public static boolean canLowerRiceStay(Block block) {
        return canLowerRiceStay(block, RICE_CROP_ID);
    }

    public static boolean canLowerRiceStay(Block block, Key cropId) {
        if (block == null) {
            return false;
        }

        // Require water at planting time in canPlantRiceAt. Once planted, CE uses a carrier state,
        // so continued survival depends on valid soil beneath the lower half.
        return isValidSoil(block.getRelative(BlockFace.DOWN), cropId);
    }
}
