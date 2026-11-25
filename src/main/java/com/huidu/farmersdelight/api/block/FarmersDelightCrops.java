package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.api.content.ContentId;
import com.huidu.farmersdelight.api.rule.CropRule;
import com.huidu.farmersdelight.api.rule.CropRules;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.CropBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;

// Read-only crop gating queries for addons that hook custom crop logic: whether a block (or a block id)
// is one of FarmersDelight's managed crops, whether it is mature enough to harvest right now, and the
// harvest tool rules. The Block overloads read the live world and therefore require the caller to be on
// the region thread that owns the block; they use the resident-chunk state variant, so an unloaded chunk
// is reported as "not a crop" instead of being loaded synchronously. The ContentId/Key overloads are pure
// registry lookups. Unknown / non-crop blocks resolve to sensible defaults (false / empty) rather than
// throwing.
public final class FarmersDelightCrops {

    private FarmersDelightCrops() {
    }

    // A block is a FarmersDelight tall crop (e.g. rice / the two-half clones configured with the
    // tall-crop behavior) or the tomato-vine behavior family.
    public static boolean isCrop(Block block) {
        return isCropBlockId(blockKey(block)) || CropRules.matching(block) != null;
    }

    public static boolean isCropBlockId(Key blockId) {
        if (blockId == null) {
            return false;
        }
        if (TallCropBlockBehavior.getBehavior(blockId) != null
                || TomatoVineBlockBehavior.getBehavior(blockId) != null) {
            return true;
        }
        BlockDefinition definition = CraftEngineBlocks.byId(blockId);
        return definition != null
                && CustomBlockUtils.getBehavior(definition.defaultState(), CropBlockBehavior.class) != null;
    }

    /** Neutral-id overload; no CraftEngine type in the caller's code. */
    public static boolean isCropBlockId(ContentId blockId) {
        return blockId != null && isCropBlockId(Key.of(blockId.value()));
    }

    /** Alias for callers that speak in terms of maturity rather than harvest readiness. */
    public static boolean isMature(Block block) {
        return isHarvestReady(block);
    }

    /** A read-only snapshot of the live crop at the block, or null when it is not a known crop. */
    public static @Nullable CropSnapshot snapshot(Block block) {
        if (block == null) {
            return null;
        }
        ImmutableBlockState state = CustomBlockUtils.getStateIfResident(block);
        if (state == null || state.isEmpty()) {
            CropRule rule = CropRules.matching(block);
            return rule == null ? null : new CropSnapshot(rule.id().value(), rule.age(block), -1,
                    null, false, rule.resetsOnHarvest(block), rule.isHarvestReady(block));
        }
        Key blockId = state.owner().value().id();
        TallCropBlockBehavior tall = TallCropBlockBehavior.getBehavior(blockId);
        if (tall != null) {
            int age = tall.getAge(state);
            return new CropSnapshot(blockId.toString(), age, tall.isUpperHalf(state)
                    ? tall.getMaxAgeUpper() : tall.getMaxAgeLower(),
                    seedItemId(state), true, tall.resetsOnHarvest(), isTallCropHarvestReady(tall, state));
        }
        TomatoVineBlockBehavior vine = TomatoVineBlockBehavior.getBehavior(blockId);
        if (vine != null) {
            Property<?> ageProperty = state.getProperty("age");
            int age = integerValue(state, ageProperty);
            return new CropSnapshot(blockId.toString(), age, maxAge(ageProperty), seedItemId(state),
                    false, false, vine.isHarvestReady(state));
        }
        CropBlockBehavior crop = CustomBlockUtils.getBehavior(state, CropBlockBehavior.class);
        if (crop != null) {
            return new CropSnapshot(blockId.toString(), crop.getAge(state), maxAge(crop.ageProperty),
                    seedItemId(state), false, false, crop.isMaxAge(state));
        }
        CropRule rule = CropRules.matching(block);
        if (rule != null) {
            return new CropSnapshot(blockId.toString(), rule.age(block), -1, null, false,
                    rule.resetsOnHarvest(block), rule.isHarvestReady(block));
        }
        return null;
    }

    // Whether the block is currently ready to harvest: for tall crops the upper half must be at its max
    // age (a reset-on-harvest crop drops from the top), for tomato vines the age must reach the max.
    public static boolean isHarvestReady(Block block) {
        if (block == null) {
            return false;
        }
        ImmutableBlockState state = CustomBlockUtils.getStateIfResident(block);
        if (state == null || state.isEmpty()) {
            CropRule rule = CropRules.matching(block);
            return rule != null && rule.isHarvestReady(block);
        }
        Key blockId = state.owner().value().id();
        TallCropBlockBehavior tallCrop = TallCropBlockBehavior.getBehavior(blockId);
        if (tallCrop != null) {
            return isTallCropHarvestReady(tallCrop, state);
        }
        TomatoVineBlockBehavior vine = TomatoVineBlockBehavior.getBehavior(blockId);
        if (vine != null) {
            return vine.isHarvestReady(state);
        }
        CropBlockBehavior crop = CustomBlockUtils.getBehavior(state, CropBlockBehavior.class);
        if (crop != null) {
            return crop.isMaxAge(state);
        }
        CropRule rule = CropRules.matching(block);
        return rule != null && rule.isHarvestReady(block);
    }

    // The current growth stage of a tall crop, or -1 when the block is not a tall crop.
    public static int tallCropAge(Block block) {
        TallCropBlockBehavior behavior = TallCropBlockBehavior.getBehavior(blockKey(block));
        if (behavior == null) {
            CropRule rule = CropRules.matching(block);
            return rule == null ? -1 : rule.age(block);
        }
        ImmutableBlockState state = CustomBlockUtils.getStateIfResident(block);
        return state == null ? -1 : behavior.getAge(state);
    }

    // Whether the given block id was declared with reset-on-harvest (the top is harvested and the crop
    // regrows). False for non-crops.
    public static boolean resetsOnHarvest(String blockId) {
        TallCropBlockBehavior behavior = TallCropBlockBehavior.getBehavior(Key.of(blockId));
        return behavior != null && behavior.resetsOnHarvest();
    }

    /** Neutral-id overload; no CraftEngine type in the caller's code. */
    public static boolean resetsOnHarvest(ContentId blockId) {
        return blockId != null && resetsOnHarvest(blockId.value());
    }

    /** Reads the registered crop rule for a live block; false for built-in/unknown crops. */
    public static boolean resetsOnHarvest(Block block) {
        CropRule rule = CropRules.matching(block);
        return rule != null && rule.resetsOnHarvest(block);
    }

    private static boolean isTallCropHarvestReady(TallCropBlockBehavior behavior, ImmutableBlockState state) {
        if (behavior.isUpperHalf(state)) {
            return behavior.isUpperMature(state);
        }
        return false;
    }

    @Nullable
    private static String seedItemId(ImmutableBlockState state) {
        return state.settings() == null || state.settings().itemId() == null
                ? null : state.settings().itemId().toString();
    }

    private static int maxAge(@Nullable Property<?> property) {
        if (property == null) {
            return -1;
        }
        int max = -1;
        for (Object value : property.possibleValues()) {
            if (value instanceof Number number && number.intValue() > max) {
                max = number.intValue();
            }
        }
        return max;
    }

    private static int integerValue(ImmutableBlockState state, @Nullable Property<?> property) {
        if (property == null || property.valueClass() != Integer.class) {
            return -1;
        }
        Object value = state.get(property);
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static Key blockKey(Block block) {
        if (block == null) {
            return null;
        }
        ImmutableBlockState state = CustomBlockUtils.getStateIfResident(block);
        return state == null || state.isEmpty() ? null : state.owner().value().id();
    }
}
