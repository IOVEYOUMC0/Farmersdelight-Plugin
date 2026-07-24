package com.huidu.farmersdelight.registry;

import com.huidu.farmersdelight.block.behavior.*;
import com.huidu.farmersdelight.effect.FoodBuffFunction;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.item.behavior.ConditionalBlockPlantingItemBehavior;
import com.huidu.farmersdelight.util.Constants;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.CommonFunctions;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

/**
 * Registers FarmersDelight's CraftEngine block and item behavior factories. Extracted from the plugin
 * main class so the (verbose, append-only) registration list lives in one focused place. Registration
 * is idempotent: a factory is only registered if its key isn't already present, so re-invocation is safe.
 */
public final class BehaviorRegistrar {

    private BehaviorRegistrar() {
    }

    public static void registerBlockBehaviors() {
        registerBehavior(Constants.BEHAVIOR_BASKET, BasketBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_COOKING_POT, CookingPotBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_CUTTING_BOARD, CuttingBoardBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_SKILLET, SkilletBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_STOVE, StoveCookingBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TALL_CROP, TallCropBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TATAMI, TatamiPairingBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_UPPER_HALF_LOOT_RELAY, UpperHalfLootRelayBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_RICE, WildRiceBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_ROPE, RopeBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_MUSHROOM_COLONY, MushroomColonyBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_PLANT, WildPlantBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TOMATO_VINE, TomatoVineBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_ORGANIC_COMPOST, OrganicCompostBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_RICH_SOIL, RichSoilBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_RICH_SOIL_FARMLAND, RichSoilFarmlandBlockBehavior.FACTORY);

        // Census line, not news on a healthy boot: routed through the startup detail channel so it is
        // recorded at FINE normally and raised to INFO only for an operator debugging the load phase.
        I18n.logDetail("startup", "plugin.registered_block_behaviors");
    }

    public static void registerItemBehaviors() {
        registerItemBehavior(Constants.ITEM_BEHAVIOR_CONDITIONAL_PLANTING, ConditionalBlockPlantingItemBehavior.FACTORY);
    }

    /** Registers FD's custom CraftEngine event functions so a food item can grant an FD buff directly in
     *  its own config via events: on: consume: functions: - type: farmersdelight:comfort|nourishment. */
    public static void registerFunctions() {
        registerFunction("farmersdelight:comfort",
                FoodBuffFunction.factory(FoodBuffFunction.Kind.COMFORT, CommonConditions::fromConfig));
        registerFunction("farmersdelight:nourishment",
                FoodBuffFunction.factory(FoodBuffFunction.Kind.NOURISHMENT, CommonConditions::fromConfig));
    }

    private static void registerBehavior(String key, BlockBehaviorFactory<?> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(keyObj) == null) {
            BlockBehaviors.register(keyObj, factory);
        }
    }

    private static void registerItemBehavior(String key, ItemBehaviorFactory<?> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(keyObj) == null) {
            ItemBehaviors.register(keyObj, factory);
        }
    }

    private static <T extends Function<Context>> void registerFunction(String key, FunctionFactory<Context, T> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.COMMON_FUNCTION_TYPE.getValue(keyObj) == null) {
            CommonFunctions.register(keyObj, factory);
        }
    }
}
