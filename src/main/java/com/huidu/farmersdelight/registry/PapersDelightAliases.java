package com.huidu.farmersdelight.registry;

import com.huidu.farmersdelight.effect.FoodBuffFunction;
import com.huidu.farmersdelight.util.Constants;
import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.behavior.CompositeItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.CommonFunctions;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * PapersDelight's behaviour identifiers, pointed at this plugin's mechanics.
 *
 *
 * That content pack names the station behaviours under its own namespace, so on a server that keeps the
 * pack and swaps the plugin its cooking pot, cutting board, skillet and stove load as blocks with no
 * behaviour at all. The aliases below reuse the factory this plugin already registered under its own name,
 * optionally rewriting the few arguments the two sides spell differently.
 *
 *
 * Nothing here replaces a registration that already exists: a pack or another plugin defining the same
 * identifier keeps its own version, and the aliases are only added when this plugin's own behaviour is
 * present.
 */
public final class PapersDelightAliases {

    private static final String COOKING_POT = "papersdelight:cooking_pot";
    private static final String CUTTING_BOARD = "papersdelight:cutting_board";
    private static final String SKILLET = "papersdelight:skillet";
    private static final String STOVE = "papersdelight:stove";
    private static final String SKILLET_ITEM = "papersdelight:skillet_item";
    private static final String NOURISHMENT_EFFECT = "papersdelight:nourishment_effect";
    private static final String COMFORT_EFFECT = "papersdelight:comfort_effect";

    /**
     * The pack's handheld skillet cooking model, which its resource pack ships under this plugin's name. Left
     * out of the alias when the model id cannot be resolved, because the handheld display is the only part
     * that uses it.
     */
    private static final String SKILLET_COOKING_MODEL = "farmersdelight:skillet_cooking";

    /**
     * Identifiers the pack uses that this plugin has no equivalent for, listed so an operator can see why
     * part of a pack stays inert instead of hunting for it. Kept as strings rather than a set because the
     * order is the order they are logged in.
     */
    private static final List<String> UNSUPPORTED = List.of(
            "papersdelight:advanced_crop",
            "papersdelight:double_crop",
            "papersdelight:roped_crop",
            "papersdelight:wild_rice",
            "papersdelight:farmland",
            "papersdelight:organic_compost",
            "papersdelight:rich_soil",
            "papersdelight:rope",
            "papersdelight:rope_block",
            "papersdelight:basket",
            "papersdelight:pairable_block",
            "papersdelight:horizontal_double_block",
            "papersdelight:horizontal_double_block_item",
            "papersdelight:skewer_item",
            "papersdelight:high_temperature",
            "papersdelight:grant_advancement",
            "papersdelight:remove_random_effect",
            "papersdelight:villager_food_point");

    private PapersDelightAliases() {
    }

    public static List<String> unsupported() {
        return UNSUPPORTED;
    }

    public static void registerBlockBehaviors() {
        // The pack configures these behaviours without arguments, so the plugin's own defaults apply; the only
        // station whose arguments differ is the stove.
        registerBlock(COOKING_POT, Constants.BEHAVIOR_COOKING_POT, UnaryOperator.identity());
        registerBlock(CUTTING_BOARD, Constants.BEHAVIOR_CUTTING_BOARD, UnaryOperator.identity());
        registerBlock(SKILLET, Constants.BEHAVIOR_SKILLET, UnaryOperator.identity());
        registerBlock(STOVE, Constants.BEHAVIOR_STOVE, PapersDelightAliases::stoveSection);
    }

    public static void registerItemBehaviors() {
        Key alias = Key.of(SKILLET_ITEM);
        if (BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(alias) != null) {
            return;
        }
        var handheld = BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(Key.of(Constants.ITEM_BEHAVIOR_SKILLET));
        if (handheld == null) {
            return;
        }
        ItemBehaviorFactory<ItemBehavior> factory = (pack, path, id, section) -> {
            ConfigSection normalized = skilletItemSection(section);
            ItemBehavior cooking = handheld.factory().create(pack, path, id, normalized);
            String block = normalized == null ? null : normalized.getString("block");
            if (block == null) {
                return cooking;
            }
            // The pack's item places the station block itself, which our handheld behaviour does not do.
            return new CompositeItemBehavior(List.of(cooking, new BlockItemBehavior(Key.of(block))));
        };
        ItemBehaviors.register(alias, factory);
    }

    public static void registerFunctions() {
        // The pack writes effect durations in ticks; this plugin's own functions read seconds.
        registerFunction(NOURISHMENT_EFFECT, FoodBuffFunction.Kind.NOURISHMENT);
        registerFunction(COMFORT_EFFECT, FoodBuffFunction.Kind.COMFORT);
    }

    private static void registerBlock(String alias, String source, UnaryOperator<ConfigSection> normalize) {
        Key aliasKey = Key.of(alias);
        if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(aliasKey) != null) {
            return;
        }
        var original = BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of(source));
        if (original == null) {
            return;
        }
        BlockBehaviors.register(aliasKey, (block, section) -> original.factory().create(block, normalize.apply(section)));
    }

    private static void registerFunction(String alias, FoodBuffFunction.Kind kind) {
        Key aliasKey = Key.of(alias);
        if (BuiltInRegistries.COMMON_FUNCTION_TYPE.getValue(aliasKey) != null) {
            return;
        }
        CommonFunctions.register(aliasKey, FoodBuffFunction.ticksFactory(kind, CommonConditions::fromConfig));
    }

    /**
     * Maps the pack's stove arguments onto this plugin's names.
     *
     *
     * The pack names its crackling sound sound; everything else it passes there (interval, volume,
     * pitch) belongs to its own sound loop and has no counterpart. Its damage lives in a separate
     * papersdelight:high_temperature behaviour that this plugin does not implement, so the stove's own
     * burn, ignite and extinguish stay at their defaults: disabling them would leave the station harmless.
     */
    static ConfigSection stoveSection(@Nullable ConfigSection section) {
        ConfigSection normalized = copy(section);
        if (!normalized.containsKey("crackle-sound") && normalized.containsKey("sound")) {
            normalized.put("crackle-sound", normalized.get("sound"));
        }
        return normalized;
    }

    /**
     * Fills in the handheld cooking model the pack ships but does not name, and drops the pack's placement
     * argument: it belongs to BlockItemBehavior, which is added by the caller.
     */
    static ConfigSection skilletItemSection(@Nullable ConfigSection section) {
        ConfigSection normalized = copy(section);
        if (!normalized.containsKey("cooking-model")) {
            normalized.put("cooking-model", SKILLET_COOKING_MODEL);
        }
        return normalized;
    }

    private static ConfigSection copy(@Nullable ConfigSection section) {
        return section == null ? ConfigSection.ofRoot(new LinkedHashMap<>(Map.of())) : section.copy();
    }
}
