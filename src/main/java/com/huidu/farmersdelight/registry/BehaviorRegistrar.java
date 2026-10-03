package com.huidu.farmersdelight.registry;

import com.huidu.farmersdelight.api.registry.ContentRegistration;
import com.huidu.farmersdelight.block.behavior.BasketBlockBehavior;
import com.huidu.farmersdelight.block.behavior.ConnectedRugBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.DoubleBlockRugBlockBehavior;
import com.huidu.farmersdelight.block.behavior.MushroomColonyBehavior;
import com.huidu.farmersdelight.block.behavior.OrganicCompostBlockBehavior;
import com.huidu.farmersdelight.block.behavior.RichSoilBlockBehavior;
import com.huidu.farmersdelight.block.behavior.RichSoilFarmlandBlockBehavior;
import com.huidu.farmersdelight.block.behavior.RopeBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TatamiPairingBehavior;
import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import com.huidu.farmersdelight.block.behavior.WildPlantBlockBehavior;
import com.huidu.farmersdelight.block.behavior.WildRiceBlockBehavior;
import com.huidu.farmersdelight.condition.IsAdultCondition;
import com.huidu.farmersdelight.condition.IsBurningCondition;
import com.huidu.farmersdelight.condition.IsKnifeCondition;
import com.huidu.farmersdelight.effect.FoodBuffFunction;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.item.behavior.ConditionalBlockPlantingItemBehavior;
import com.huidu.farmersdelight.item.behavior.SkilletItemBehavior;
import com.huidu.farmersdelight.loot.AwardAdvancementFunction;
import com.huidu.farmersdelight.util.Constants;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.loot.function.LootFunctionFactory;
import net.momirealms.craftengine.core.loot.function.LootFunctions;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.CommonFunctions;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

public final class BehaviorRegistrar {

    private BehaviorRegistrar() {
    }

    public static void registerBlockBehaviors() {
        registerBehavior(Constants.BEHAVIOR_CONNECTED_RUG, ConnectedRugBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_DOUBLE_BLOCK, DoubleBlockRugBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_BASKET, BasketBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_COOKING_POT, CookingPotBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_CUTTING_BOARD, CuttingBoardBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_SKILLET, SkilletBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_STOVE, StoveCookingBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TALL_CROP, TallCropBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TATAMI, TatamiPairingBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_RICE, WildRiceBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_ROPE, RopeBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_MUSHROOM_COLONY, MushroomColonyBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_PLANT, WildPlantBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TOMATO_VINE, TomatoVineBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_ORGANIC_COMPOST, OrganicCompostBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_RICH_SOIL, RichSoilBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_RICH_SOIL_FARMLAND, RichSoilFarmlandBlockBehavior.FACTORY);
        // Other plugins' block behaviors: re-applied on every pass, skipped when CraftEngine already has them.
        ContentRegistration.apply(ContentRegistration.Kind.BLOCK_BEHAVIOR);

        // Census line, not news on a healthy boot: routed through the startup detail channel so it is
        // recorded at FINE normally and raised to INFO only for an operator debugging the load phase.
        I18n.logDetail("startup", "plugin.registered_block_behaviors");
    }

    public static void registerItemBehaviors() {
        registerItemBehavior();
        registerItemBehavior(Constants.ITEM_BEHAVIOR_SKILLET, SkilletItemBehavior.FACTORY);
        ContentRegistration.apply(ContentRegistration.Kind.ITEM_BEHAVIOR);
    }

    public static void registerFunctions() {
        registerFunction("farmersdelight:comfort",
                FoodBuffFunction.factory(FoodBuffFunction.Kind.COMFORT, CommonConditions::fromConfig));
        registerFunction("farmersdelight:nourishment",
                FoodBuffFunction.factory(FoodBuffFunction.Kind.NOURISHMENT, CommonConditions::fromConfig));
        ContentRegistration.apply(ContentRegistration.Kind.FUNCTION);
    }

    // Conditions and loot functions the bundled drop packs use, so a rule that needs plugin knowledge (what
    // counts as a knife, whether the victim was an adult) or plugin state (the advancement system) can live
    // in the pack next to the rest of its loot instead of in a Java listener.
    public static void registerConditions() {
        registerCondition(Constants.CONDITION_IS_ADULT, IsAdultCondition.FACTORY);
        registerCondition(Constants.CONDITION_IS_BURNING, IsBurningCondition.FACTORY);
        registerCondition(Constants.CONDITION_IS_KNIFE, IsKnifeCondition.FACTORY);
        ContentRegistration.apply(ContentRegistration.Kind.CONDITION);
    }

    public static void registerLootFunctions() {
        registerLootFunction(Constants.LOOT_FUNCTION_AWARD_ADVANCEMENT, AwardAdvancementFunction.FACTORY);
        ContentRegistration.apply(ContentRegistration.Kind.LOOT_FUNCTION);
    }

    /**
     * Re-applies this plugin's own CraftEngine registrations and every external registration another plugin
     * made through {@code ContentRegistration}. Idempotent: ids CraftEngine already reports are skipped, so
     * this is safe to call on every reload pass. The targeted CraftEngine versions keep their built-in type
     * registries for the whole class-loader lifetime, so the pass is defence in depth: it covers a
     * CraftEngine that rebuilds them later, and any entry that goes missing for another reason.
     *
     * <p>Called by the plugin's reload path; the load phase reaches the same five methods through
     * {@code LoadPhaseRegistrar}.
     */
    public static void replayContentRegistrations() {
        registerBlockBehaviors();
        registerItemBehaviors();
        registerFunctions();
        registerConditions();
        registerLootFunctions();
        I18n.logDetail("startup", "plugin.replayed_content_registrations",
                "count", ContentRegistration.registeredIds().size());
    }

    private static void registerBehavior(String key, BlockBehaviorFactory<?> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(keyObj) == null) {
            BlockBehaviors.register(keyObj, factory);
        }
    }

    private static void registerItemBehavior() {
        registerItemBehavior(Constants.ITEM_BEHAVIOR_CONDITIONAL_PLANTING, ConditionalBlockPlantingItemBehavior.FACTORY);
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

    private static <T extends Condition<Context>> void registerCondition(String key, ConditionFactory<Context, T> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.COMMON_CONDITION_TYPE.getValue(keyObj) == null) {
            CommonConditions.register(keyObj, factory);
        }
    }

    private static void registerLootFunction(String key, LootFunctionFactory<?> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.LOOT_FUNCTION_TYPE.getValue(keyObj) == null) {
            LootFunctions.register(keyObj, factory);
        }
    }
}
