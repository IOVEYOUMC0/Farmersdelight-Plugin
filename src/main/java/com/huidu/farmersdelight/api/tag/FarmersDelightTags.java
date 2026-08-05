package com.huidu.farmersdelight.api.tag;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;

/**
 * Public tag-key constants matching the original mod's ModTags and CommonTags.
 * Addons reference these instead of hardcoding tag strings, so the tag paths stay aligned with the
 * plugin's own tag usage and survive tag-path renames.
 *
 * <p>Every constant here is a NamespacedKey formatted as namespace:path matching
 * the tag JSON filename in the resource pack / datapack.</p>
 */
public final class FarmersDelightTags {

    private FarmersDelightTags() {}

    // ── Block tags ────────────────────────────────────────────────────────

    /** Blocks that can heat cooking workstations. */
    public static final String BLOCK_HEAT_SOURCES = "farmersdelight:heat_sources";
    /** Blocks that transfer heat from a source below to a workstation above. */
    public static final String BLOCK_HEAT_CONDUCTORS = "farmersdelight:heat_conductors";
    /** Heat sources that also render a tray beneath supported blocks. */
    public static final String BLOCK_TRAY_HEAT_SOURCES = "farmersdelight:tray_heat_sources";
    /** Blocks that accelerate Organic Compost decomposition. */
    public static final String BLOCK_COMPOST_ACTIVATORS = "farmersdelight:compost_activators";
    /** Blocks that drop a Cake Slice when mined with a knife. */
    public static final String BLOCK_DROPS_CAKE_SLICE = "farmersdelight:drops_cake_slice";
    /** Blocks that grow Mushroom Colonies. */
    public static final String BLOCK_MUSHROOM_COLONY_GROWABLE_ON = "farmersdelight:mushroom_colony_growable_on";
    /** Crops planted below a soil block that can be bonemealed by Rich Soil. */
    public static final String BLOCK_PLANTED_FROM_BELOW = "farmersdelight:planted_from_below";
    /** Placeable feast blocks (large multi-serving meals). */
    public static final String BLOCK_FEASTS = "farmersdelight:feasts";
    /** Pie blocks (placeable desserts, 4 slices). */
    public static final String BLOCK_PIES = "farmersdelight:pies";
    /** Blocks made mostly of straw (populates MINEABLE_WITH_KNIFE). */
    public static final String BLOCK_STRAW_BLOCKS = "farmersdelight:straw_blocks";
    /** Common surface blocks used for biome blending. */
    public static final String BLOCK_TERRAIN = "farmersdelight:terrain";
    /** Blocks whose growth is not boosted by Rich Soil. */
    public static final String BLOCK_UNAFFECTED_BY_RICH_SOIL = "farmersdelight:unaffected_by_rich_soil";
    /** Cabinet blocks. */
    public static final String BLOCK_CABINETS = "farmersdelight:cabinets";
    /** Wooden cabinet blocks (subset of CABINETS, burnable). */
    public static final String BLOCK_CABINETS_WOODEN = "farmersdelight:cabinets/wooden";
    /** Mushroom colony blocks. */
    public static final String BLOCK_MUSHROOM_COLONIES = "farmersdelight:mushroom_colonies";
    /** Blocks mineable with a knife. */
    public static final String BLOCK_MINEABLE_WITH_KNIFE = "farmersdelight:mineable/knife";
    /** Rope blocks. */
    public static final String BLOCK_ROPES = "farmersdelight:ropes";
    /** Wild crop blocks. */
    public static final String BLOCK_WILD_CROPS = "farmersdelight:wild_crops";
    /** Campfire signal smoke source blocks. */
    public static final String BLOCK_CAMPFIRE_SIGNAL_SMOKE = "farmersdelight:campfire_signal_smoke";

    // ── Item tags ─────────────────────────────────────────────────────────

    /** Knife tools. */
    public static final String ITEM_KNIVES = "farmersdelight:tools/knives";
    /** Items enchantable with Backstabbing (populated by tools/knives). */
    public static final String ITEM_KNIFE_ENCHANTABLE = "farmersdelight:enchantable/knife";
    /** Multi-ingredient foods not contained in a bowl/plate. */
    public static final String ITEM_SNACKS = "farmersdelight:snacks";
    /** Prepared foods contained in a bowl or plate. */
    public static final String ITEM_MEALS = "farmersdelight:meals";
    /** Bottled drinkable consumables. */
    public static final String ITEM_DRINKS = "farmersdelight:drinks";
    /** Prepared sweet foods (desserts). */
    public static final String ITEM_SWEETS = "farmersdelight:sweets";
    /** Feast items (placeable large meals). */
    public static final String ITEM_FEASTS = "farmersdelight:feasts";
    /** Pie items (placeable desserts). */
    public static final String ITEM_PIES = "farmersdelight:pies";
    /** Items rendered flat on the cutting board. */
    public static final String ITEM_FLAT_ON_CUTTING_BOARD = "farmersdelight:flat_on_cutting_board";
    /** Containers used for sneak-clicking in the cooking pot. */
    public static final String ITEM_SERVING_CONTAINERS = "farmersdelight:serving_containers";
    /** Tools that can harvest straw from grassy plants. */
    public static final String ITEM_STRAW_HARVESTERS = "farmersdelight:straw_harvesters";
    /** Cabinet items. */
    public static final String ITEM_CABINETS = "farmersdelight:cabinets";
    /** Wooden cabinet items. */
    public static final String ITEM_CABINETS_WOODEN = "farmersdelight:cabinets/wooden";
    /** Canvas sign items. */
    public static final String ITEM_CANVAS_SIGNS = "farmersdelight:canvas_signs";
    /** Hanging canvas sign items. */
    public static final String ITEM_HANGING_CANVAS_SIGNS = "farmersdelight:hanging_canvas_signs";
    /** Mushroom colony items. */
    public static final String ITEM_MUSHROOM_COLONIES = "farmersdelight:mushroom_colonies";
    /** Wild crop items. */
    public static final String ITEM_WILD_CROPS = "farmersdelight:wild_crops";
    /** Items tagged as milk (clears one buff on consume). */
    public static final String ITEM_MILK = "farmersdelight:milk";

    // ── Entity type tags ──────────────────────────────────────────────────

    /** Entities that can eat Dog Food when tame. */
    public static final String ENTITY_DOG_FOOD_USERS = "farmersdelight:dog_food_users";
    /** Entities that can eat Horse Feed when tame. */
    public static final String ENTITY_HORSE_FEED_USERS = "farmersdelight:horse_feed_users";
    /** Entities tempted by Horse Feed. */
    public static final String ENTITY_HORSE_FEED_TEMPTED = "farmersdelight:horse_feed_tempted";

    // ── Common tags (c: namespace, cross-mod recipes) ─────────────────────

    /** Common knife-mineable blocks tag. */
    public static final String COMMON_MINEABLE_WITH_KNIFE = "c:mineable/knife";
    /** Common knife tool tag. */
    public static final String COMMON_TOOLS_KNIFE = "c:tools/knife";
    /** Common cabbage crop tag. */
    public static final String COMMON_CROPS_CABBAGE = "c:crops/cabbage";
    /** Common tomato crop tag. */
    public static final String COMMON_CROPS_TOMATO = "c:crops/tomato";
    /** Common onion crop tag. */
    public static final String COMMON_CROPS_ONION = "c:crops/onion";
    /** Common rice crop tag. */
    public static final String COMMON_CROPS_RICE = "c:crops/rice";
    /** Common storage block tag for cabbage. */
    public static final String COMMON_STORAGE_BLOCKS_CABBAGE = "c:storage_blocks/cabbage";
    /** Common storage block tag for rice. */
    public static final String COMMON_STORAGE_BLOCKS_RICE = "c:storage_blocks/rice";
}
