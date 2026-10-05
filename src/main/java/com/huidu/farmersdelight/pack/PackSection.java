package com.huidu.farmersdelight.pack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A FarmersDelight content section a CraftEngine pack can declare.
 *
 *
 * Each entry pairs the root key a pack file uses (the CraftEngine section id the parser claims) with the
 * root key the same content carries once it reaches this plugin. The two differ only where the plugin already
 * had a root key of its own before packs could declare one, so the existing readers stay untouched: a pack
 * author writes cooking_recipes: and com.huidu.farmersdelight.recipe.CookingPotRecipeManager
 * still looks up cooking_pot_recipes.
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

    /**
     * Resolves a CraftEngine section key to its section. A key may carry a #suffix (CraftEngine
     * accepts several sections of one type per file this way); the suffix selects the advancement namespace
     * and is not part of the section id.
     */
    static PackSection bySectionId(String sectionId) {
        return BY_SECTION_ID.get(sectionId.toLowerCase(Locale.ROOT));
    }

    /** Strips a #suffix from a CraftEngine section key. */
    static String baseSectionId(String sectionKey) {
        int hash = sectionKey.indexOf('#');
        return (hash == -1 ? sectionKey : sectionKey.substring(0, hash)).toLowerCase(Locale.ROOT);
    }

    /** Reads the #suffix of a CraftEngine section key, or null when it has none. */
    static String namespaceSuffix(String sectionKey) {
        int hash = sectionKey.indexOf('#');
        if (hash == -1) {
            return null;
        }
        String suffix = sectionKey.substring(hash + 1).trim();
        return suffix.isEmpty() ? null : suffix;
    }
}
