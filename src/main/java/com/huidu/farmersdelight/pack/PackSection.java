package com.huidu.farmersdelight.pack;

import java.util.HashMap;
import java.util.Map;

/**
 * A FarmersDelight content section a CraftEngine pack can declare.
 */
public enum PackSection {

    /** Cooking-pot recipes; read by CookingPotRecipeManager. */
    COOKING_POT("cooking_recipes", "cooking_pot_recipes"),
    /** Cutting-board recipes; read by CuttingBoardRecipeManager. */
    CUTTING_BOARD("cutting_recipes", "cutting_board_recipes"),
    /** Special-recipe menu cards; read by SpecialRecipeLoader. */
    SPECIAL_RECIPE("special_recipes", "special_recipes"),
    /**
     * Addon advancement trees. Kept under an own section id because CraftEngine claims both
     * advancement and advancements for its own parser, whose implementation is an empty
     * stub, so a pack file using those keys would be read by nobody.
     */
    ADVANCEMENTS("farmersdelight_advancements", "advancements"),
    /**
     * Advanced tag groups: ids a recipe can name instead of listing their members. Claimed on its own rather
     * than with the sections above, because CraftEngine registers one claim's ids as a unit - a pack or another
     * plugin that had already claimed this id would take the recipe sections down with it, and losing every
     * recipe is not a price worth paying for tag groups.
     */
    ADVANCED_TAGS("advanced_tags", "advanced_tags", true);

    private static final Map<String, PackSection> BY_SECTION_ID = new HashMap<>();

    static {
        for (PackSection section : values()) {
            BY_SECTION_ID.put(section.sectionId, section);
        }
    }

    private final String sectionId;
    private final String rootKey;
    private final boolean separateClaim;

    PackSection(String sectionId, String rootKey) {
        this(sectionId, rootKey, false);
    }

    PackSection(String sectionId, String rootKey, boolean separateClaim) {
        this.sectionId = sectionId;
        this.rootKey = rootKey;
        this.separateClaim = separateClaim;
    }

    /** Root key declared in the pack file, i.e. the CraftEngine section id. */
    public String sectionId() {
        return sectionId;
    }

    /** Root key the bridged configuration carries, i.e. what this plugin's readers look up. */
    public String rootKey() {
        return rootKey;
    }

    /** True when this section is claimed in a registration of its own instead of the shared one. */
    public boolean separateClaim() {
        return separateClaim;
    }

}
