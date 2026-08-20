package com.huidu.farmersdelight.api.recipe;

import java.util.List;

// A special recipe shown in the FarmersDelight recipe menu's "special recipes" section. These are
// non-crafting recipes (composting, sunlight/water conditions, catalysts...) with a fixed input to
// output to condition layout. Addons register them through FarmersDelightApi.registerSpecialRecipe.
// The optional displayType picks how the recipe is presented in-game: DISPLAY_RECIPE (default) opens a
// detail page with input/output/condition slots; DISPLAY_ITEM_DESCRIPTION is a pure-information entry
// that stays in the list and shows its whole description as the list item's lore, with no detail page.
public class SpecialRecipeInfo {

    public static final String DISPLAY_RECIPE = "recipe";
    public static final String DISPLAY_ITEM_DESCRIPTION = "item_description";

    private final String id;
    private final String titleKey;
    private final String iconItemId;
    private final List<String> descriptionKeys;
    private final List<SlotEntry> inputSlots;
    private final List<SlotEntry> outputSlots;
    private final boolean hasSunlight;
    private final boolean hasWater;
    private final boolean hasCatalystInfo;
    private final List<SlotEntry> catalystSlots;
    private final String displayType;

    public SpecialRecipeInfo(String id, String titleKey, String iconItemId,
                             List<String> descriptionKeys,
                             List<SlotEntry> inputSlots,
                             List<SlotEntry> outputSlots,
                             boolean hasSunlight,
                             boolean hasWater,
                             boolean hasCatalystInfo,
                             List<SlotEntry> catalystSlots) {
        this(id, titleKey, iconItemId, descriptionKeys, inputSlots, outputSlots,
                hasSunlight, hasWater, hasCatalystInfo, catalystSlots, DISPLAY_RECIPE);
    }

    public SpecialRecipeInfo(String id, String titleKey, String iconItemId,
                             List<String> descriptionKeys,
                             List<SlotEntry> inputSlots,
                             List<SlotEntry> outputSlots,
                             boolean hasSunlight,
                             boolean hasWater,
                             boolean hasCatalystInfo,
                             List<SlotEntry> catalystSlots,
                             String displayType) {
        this.id = id;
        this.titleKey = titleKey;
        this.iconItemId = iconItemId;
        this.descriptionKeys = descriptionKeys != null ? List.copyOf(descriptionKeys) : List.of();
        this.inputSlots = inputSlots != null ? List.copyOf(inputSlots) : List.of();
        this.outputSlots = outputSlots != null ? List.copyOf(outputSlots) : List.of();
        this.hasSunlight = hasSunlight;
        this.hasWater = hasWater;
        this.hasCatalystInfo = hasCatalystInfo;
        this.catalystSlots = catalystSlots != null ? List.copyOf(catalystSlots) : List.of();
        this.displayType = displayType != null ? displayType : DISPLAY_RECIPE;
    }

    public String id() { return id; }
    public String titleKey() { return titleKey; }
    public String iconItemId() { return iconItemId; }
    public List<String> descriptionKeys() { return descriptionKeys; }
    public List<SlotEntry> inputSlots() { return inputSlots; }
    public List<SlotEntry> outputSlots() { return outputSlots; }
    public boolean hasSunlight() { return hasSunlight; }
    public boolean hasWater() { return hasWater; }
    public boolean hasCatalystInfo() { return hasCatalystInfo; }
    public List<SlotEntry> catalystSlots() { return catalystSlots; }
    public String displayType() { return displayType; }

    /**
     * A slot's display entry: either a concrete item id / "#tag" reference (itemId), or a block
     * behavior's configured block list (behaviorBlockId + behaviorListKey, e.g. the organic_compost
     * behavior's "activators"). The behavior form is resolved lazily at render time so a live
     * edit of the block's config is picked up.
     */
    public record SlotEntry(String itemId, String behaviorBlockId, String behaviorListKey, String nameKey, List<String> loreKeys) {
        public SlotEntry(String itemId, String nameKey, List<String> loreKeys) {
            this(itemId, null, null, nameKey, loreKeys);
        }

        public SlotEntry {
            loreKeys = loreKeys != null ? List.copyOf(loreKeys) : List.of();
        }

        public boolean isBehaviorList() {
            return behaviorBlockId != null && behaviorListKey != null && !behaviorListKey.isBlank();
        }
    }
}
