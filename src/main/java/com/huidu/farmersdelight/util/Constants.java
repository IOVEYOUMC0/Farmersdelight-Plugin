package com.huidu.farmersdelight.util;

public final class Constants {

    public static final String TAG_KNIVES = "farmersdelight:knives";
    public static final String TAG_AXES = "minecraft:axes";
    public static final String TAG_PICKAXES = "minecraft:pickaxes";
    public static final String TAG_SHOVELS = "minecraft:shovels";
    public static final String ACTION_AXE_DIG = "farmersdelight:axe_dig";
    public static final String ACTION_AXE_STRIP = "farmersdelight:axe_strip";
    public static final String ACTION_PICKAXE_DIG = "farmersdelight:pickaxe_dig";
    public static final String ACTION_SHOVEL_DIG = "farmersdelight:shovel_dig";
    public static final String ITEM_RICE = "farmersdelight:rice";
    public static final String ITEM_CABBAGE_SEEDS = "farmersdelight:cabbage_seeds";
    public static final String ITEM_TOMATO_SEEDS = "farmersdelight:tomato_seeds";
    public static final String ITEM_ONION = "farmersdelight:onion";
    public static final String ITEM_HAM = "farmersdelight:ham";
    public static final String ITEM_SMOKED_HAM = "farmersdelight:smoked_ham";
    public static final String ITEM_HONEY_GLAZED_HAM = "farmersdelight:honey_glazed_ham";
    public static final String ITEM_SKILLET = "farmersdelight:skillet";
    public static final String ITEM_NETHERITE_KNIFE = "farmersdelight:netherite_knife";
    public static final String ITEM_ROTTEN_TOMATO = "farmersdelight:rotten_tomato";
    public static final String ITEM_SHEARS = "minecraft:shears";
    public static final String BLOCK_COOKING_POT = "farmersdelight:cooking_pot";
    public static final String BLOCK_SKILLET = "farmersdelight:skillet";
    public static final String BLOCK_STOVE = "farmersdelight:stove";
    public static final String BLOCK_CUTTING_BOARD = "farmersdelight:cutting_board";
    public static final String BLOCK_RICE = "farmersdelight:rice";
    public static final String BLOCK_WILD_RICE = "farmersdelight:wild_rice";
    public static final String BLOCK_BROWN_MUSHROOM = "farmersdelight:brown_mushroom";
    public static final String BLOCK_RED_MUSHROOM = "farmersdelight:red_mushroom";
    public static final String BLOCK_BROWN_MUSHROOM_COLONY = "farmersdelight:brown_mushroom_colony";
    public static final String BLOCK_RED_MUSHROOM_COLONY = "farmersdelight:red_mushroom_colony";
    public static final String BLOCK_ONIONS = "farmersdelight:onions";
    public static final String BLOCK_CABBAGES = "farmersdelight:cabbages";
    public static final String BLOCK_TOMATOES = "farmersdelight:tomatoes";
    public static final String BLOCK_BUDDING_TOMATOES = "farmersdelight:budding_tomatoes";
    public static final String BEHAVIOR_COOKING_POT = "farmersdelight:cooking_pot";
    public static final String BEHAVIOR_CUTTING_BOARD = "farmersdelight:cutting_board";
    public static final String BEHAVIOR_SKILLET = "farmersdelight:skillet";
    public static final String BEHAVIOR_STOVE = "farmersdelight:stove";
    public static final String BEHAVIOR_TALL_CROP = "farmersdelight:tall_crop";
    public static final String BEHAVIOR_TATAMI = "farmersdelight:tatami";
    public static final String BEHAVIOR_UPPER_HALF_LOOT_RELAY = "farmersdelight:upper_half_loot_relay";
    public static final String BEHAVIOR_WILD_RICE = "farmersdelight:wild_rice";
    public static final String BEHAVIOR_ROPE = "farmersdelight:rope";
    public static final String BEHAVIOR_MUSHROOM_COLONY = "farmersdelight:mushroom_colony";
    public static final String BEHAVIOR_WILD_PLANT = "farmersdelight:wild_plant";
    public static final String BEHAVIOR_TOMATO_VINE = "farmersdelight:tomato_vine";
    public static final String BEHAVIOR_ORGANIC_COMPOST = "farmersdelight:organic_compost";
    public static final String BEHAVIOR_RICH_SOIL = "farmersdelight:rich_soil";
    public static final String BEHAVIOR_RICH_SOIL_FARMLAND = "farmersdelight:rich_soil_farmland";
    public static final String ITEM_BEHAVIOR_CONDITIONAL_PLANTING = "farmersdelight:conditional_block_planting";
    public static final String BLOCK_RICH_SOIL_FARMLAND = "farmersdelight:rich_soil_farmland";
    public static final String ITEM_ORGANIC_COMPOST = "farmersdelight:organic_compost";
    public static final String ITEM_RICH_SOIL = "farmersdelight:rich_soil";
    public static final String BLOCK_TOMATO_CROP_ON_ROPE = "farmersdelight:tomato_crop_on_rope";
    public static final String SOUND_COOKING_POT_BOIL = "farmersdelight:block.cooking_pot.boil";
    public static final String SOUND_COOKING_POT_BOIL_SOUP = "farmersdelight:block.cooking_pot.boil_soup";
    public static final String SOUND_CUTTING_BOARD_KNIFE = "farmersdelight:block.cutting_board.knife";
    public static final String SOUND_SKILLET_SIZZLE = "farmersdelight:block.skillet.sizzle";
    public static final String SOUND_SKILLET_ADD_FOOD = "farmersdelight:block.skillet.add_food";
    public static final String SOUND_SKILLET_ATTACK_STRONG = "farmersdelight:item.skillet.attack.strong";
    public static final String SOUND_SKILLET_ATTACK_WEAK = "farmersdelight:item.skillet.attack.weak";
    public static final String SOUND_STOVE_CRACKLE = "farmersdelight:block.stove.crackle";
    public static final int DEFAULT_COOKING_TIME_SKILLET = 600;
    public static final int MINIMUM_COOKING_TIME_SKILLET = 60;
    public static final int DEFAULT_COOKING_TIME_COOKING_POT = 200;
    public static final int DEFAULT_COOKING_POT_DISPLAY_VISIBILITY_CHECK_INTERVAL_TICKS = 100;
    public static final int DEFAULT_COOKING_POT_PLACE_INTERACTION_COOLDOWN_MS = 1000;
    public static final int DEFAULT_COMFORT_DURATION = 300;
    public static final int DEFAULT_NOURISHMENT_DURATION = 300;
    public static final float SKILLET_COOKING_TIME_REDUCTION = 0.2f;
    public static final float SKILLET_FIRE_ASPECT_BONUS = 0.05f;
    // Lowered from 0.2 / 0.05 after spark profiling: dense stove scenes (3000+ lit stoves) generate
    // world.spawnParticle / playSound broadcast packets at rate = stoves × chance × slots. Even with
    // R-PERF-005 chunk-tracked gating, packet floor is proportional to chance. 0.1 smoke / 0.02
    // crackle keep visual identity intact while cutting hot-tick packet count roughly in half.
    public static final float STOVE_PARTICLE_CHANCE = 0.1f;
    public static final float STOVE_CRACKLE_CHANCE = 0.02f;
    public static final float SKILLET_PARTICLE_CHANCE = 0.1f;
    // Lowered from 0.03 after spark profiling — same rationale as STOVE_* above.
    public static final float SKILLET_SIZZLE_CHANCE = 0.01f;
    public static final float CUTTING_BOARD_FAIL_VOLUME = 0.25f;
    public static final float CUTTING_BOARD_FAIL_PITCH = 0.5f;

    private Constants() {
    }
}
