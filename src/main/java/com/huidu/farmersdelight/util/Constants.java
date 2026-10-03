package com.huidu.farmersdelight.util;

public final class Constants {

    public static final String TAG_KNIVES = "farmersdelight:tools/knives";
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
    public static final String BEHAVIOR_BASKET = "farmersdelight:basket";
    public static final String BEHAVIOR_CONNECTED_RUG = "farmersdelight:connected_rug";
    public static final String BEHAVIOR_DOUBLE_BLOCK = "farmersdelight:double_block";
    public static final String BEHAVIOR_COOKING_POT = "farmersdelight:cooking_pot";
    public static final String BEHAVIOR_CUTTING_BOARD = "farmersdelight:cutting_board";
    public static final String BEHAVIOR_SKILLET = "farmersdelight:skillet";
    public static final String BEHAVIOR_STOVE = "farmersdelight:stove";
    public static final String BEHAVIOR_TALL_CROP = "farmersdelight:tall_crop";
    public static final String BEHAVIOR_TATAMI = "farmersdelight:tatami";
    public static final String BEHAVIOR_WILD_RICE = "farmersdelight:wild_rice";
    public static final String BEHAVIOR_ROPE = "farmersdelight:rope";
    public static final String BEHAVIOR_MUSHROOM_COLONY = "farmersdelight:mushroom_colony";
    public static final String BEHAVIOR_WILD_PLANT = "farmersdelight:wild_plant";
    public static final String BEHAVIOR_TOMATO_VINE = "farmersdelight:tomato_vine";
    public static final String BEHAVIOR_ORGANIC_COMPOST = "farmersdelight:organic_compost";
    public static final String BEHAVIOR_RICH_SOIL = "farmersdelight:rich_soil";
    public static final String BEHAVIOR_RICH_SOIL_FARMLAND = "farmersdelight:rich_soil_farmland";
    public static final String ITEM_BEHAVIOR_CONDITIONAL_PLANTING = "farmersdelight:conditional_block_planting";
    public static final String ITEM_BEHAVIOR_SKILLET = "farmersdelight:skillet_item";
    public static final String CONDITION_IS_ADULT = "farmersdelight:is_adult";
    public static final String CONDITION_IS_BURNING = "farmersdelight:is_burning";
    public static final String CONDITION_IS_KNIFE = "farmersdelight:is_knife";
    public static final String LOOT_FUNCTION_AWARD_ADVANCEMENT = "farmersdelight:award_advancement";
    public static final String ITEM_SETTING_PET_FOOD = "farmersdelight:pet_food";
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
    // Stove ignition/extinguishing sounds, the same ones the mod plays from AbstractStoveBlock and the
    // pack used to play before that logic moved into the stove behavior.
    public static final String SOUND_STOVE_IGNITE = "minecraft:item.flintandsteel.use";
    public static final String SOUND_STOVE_IGNITE_FIRE_CHARGE = "minecraft:item.firecharge.use";
    public static final String SOUND_STOVE_EXTINGUISH = "minecraft:block.fire.extinguish";
    public static final String SOUND_STOVE_EXTINGUISH_WATER = "minecraft:entity.generic.extinguish_fire";
    public static final int DEFAULT_COOKING_TIME_SKILLET = 600;
    public static final int MINIMUM_COOKING_TIME_SKILLET = 60;
    public static final int DEFAULT_COOKING_TIME_COOKING_POT = 200;
    public static final int DEFAULT_COMFORT_DURATION = 300;
    public static final int DEFAULT_NOURISHMENT_DURATION = 300;
    public static final float SKILLET_COOKING_TIME_REDUCTION = 0.2f;
    public static final float SKILLET_FIRE_ASPECT_BONUS = 0.05f;
    // Smoke and crackle probabilities control per-slot effect traffic.
    // Lower probabilities reduce broadcasts in dense cooking areas without changing cooking progress.
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
