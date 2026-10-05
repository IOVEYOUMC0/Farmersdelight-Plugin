package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CraftEngine resolves a #tag ingredient as the tag's datapack members (FD's generated common-tags)
 * plus every pack's settings.tags declarations (core AbstractRecipeSerializer:95-121 ->
 * ItemManager.itemIdsByTag), so a custom item only joins a tag by declaring it. Two finished Barbecue's
 * Delight skewers could craft one of ours because those skewers declared c:foods/raw_meat and
 * c:foods/cooked_meat; the fix removed the ingredient-class declarations from the finished items
 * instead of narrowing the recipes. These tests pin both halves down offline.
 *
 * Red if: (1) a finished item declares an ingredient-class c:foods/... tag again, or (2) the skewer
 * recipes stop being tag driven (explicit id lists, or a dropped tag), which would also cut the addon and
 * vanilla ingredients the tags exist for.
 */
class SkewerIngredientOverlapTest {

    private static final Path RECIPES = Path.of(
            "src/main/resources/craftengine/farmersdelight/configuration/recipes.yml");
    private static final Path COMMON_TAGS = Path.of("src/main/resources/common-tags.yml");
    private static final Path FD_ITEMS = Path.of(
            "src/main/resources/craftengine/farmersdelight/configuration/items.yml");

    /**
     * The finished dishes the addons ship that must never satisfy a skewer ingredient slot.
     *
     * Explicit test data on purpose: this used to be read out of the sibling repositories, which CI does not
     * check out, so the suite only passed on a machine that happened to have them next to this one. Keep the ids
     * in sync with each addon's own item list (Barbeque's Delight, Brewin' And Chewin, Crabber's Delight,
     * End's Delight); the "no addon item may declare an ingredient-class tag" half belongs in those repositories'
     * own tests, where their files are present.
     */
    private static final List<String> ADDON_FINISHED_ITEMS = List.of(
            "barbequesdelight:raw_cod_skewer", "barbequesdelight:raw_salmon_skewer",
            "barbequesdelight:raw_chicken_skewer", "barbequesdelight:raw_mushroom_skewer",
            "barbequesdelight:raw_beef_skewer", "barbequesdelight:raw_lamb_skewer",
            "barbequesdelight:raw_rabbit_skewer", "barbequesdelight:raw_pork_sausage_skewer",
            "barbequesdelight:raw_potato_skewer", "barbequesdelight:raw_vegetable_skewer",
            "barbequesdelight:grilled_cod_skewer", "barbequesdelight:grilled_salmon_skewer",
            "barbequesdelight:grilled_chicken_skewer", "barbequesdelight:grilled_mushroom_skewer",
            "barbequesdelight:grilled_beef_skewer", "barbequesdelight:grilled_lamb_skewer",
            "barbequesdelight:grilled_rabbit_skewer", "barbequesdelight:grilled_pork_sausage_skewer",
            "barbequesdelight:grilled_potato_skewer", "barbequesdelight:grilled_vegetable_skewer",
            "barbequesdelight:kebab_wrap", "barbequesdelight:kebab_sandwich",
            "barbequesdelight:bibimbap",
            "brewinandchewin:cheesy_pasta", "brewinandchewin:horror_lasagna",
            "brewinandchewin:scarlet_pierogi", "brewinandchewin:pizza_slice",
            "brewinandchewin:ham_and_cheese_sandwich", "brewinandchewin:pizza",
            "crabbersdelight:crab_cakes", "crabbersdelight:surf_and_turf",
            "crabbersdelight:shrimp_skewer", "crabbersdelight:clam_bake",
            "crabbersdelight:shrimp_fried_rice", "crabbersdelight:stuffed_nautilus_shell",
            "crabbersdelight:squid_kebab", "crabbersdelight:frog_leg_kebab",
            "endsdelight:stir_fried_shulker_meat");

    /** Tag members FD's generated datapack installs, which is how CraftEngine resolves a vanilla tag. */
    private static Map<String, Set<String>> datapackMembers() throws Exception {
        ConfigurationSection tags = load(COMMON_TAGS).getConfigurationSection("tags");
        Map<String, Set<String>> members = new LinkedHashMap<>();
        for (String tag : tags.getKeys(false)) {
            members.put(tag, new LinkedHashSet<>(tags.getStringList(tag)));
        }
        return members;
    }

    /** Items a pack puts into a tag through settings.tags; CraftEngine merges these into the tag. */
    private static Map<String, Set<String>> declaredMembers(Path itemsFile) throws Exception {
        Map<String, Set<String>> declared = new LinkedHashMap<>();
        if (!Files.isRegularFile(itemsFile)) {
            return declared;
        }
        ConfigurationSection section = load(itemsFile).getConfigurationSection("items");
        for (String id : section.getKeys(false)) {
            for (String tag : section.getStringList(id + ".settings.tags")) {
                declared.computeIfAbsent(tag, key -> new LinkedHashSet<>()).add(id);
            }
        }
        return declared;
    }

    /** Only this repository's pack: CI checks out FarmersDelight alone, so nothing else may be read. */
    private static List<Map<String, Set<String>>> allPacks() throws Exception {
        return List.of(declaredMembers(FD_ITEMS));
    }

    /** Every item id that would satisfy one ingredient slot, the way CraftEngine resolves it. */
    private static Set<String> slotMembers(Object slot, Map<String, Set<String>> datapack,
            List<Map<String, Set<String>>> packs) {
        Object value;
        if (slot instanceof ConfigurationSection section) {
            value = section.get("items");
        } else if (slot instanceof Map<?, ?> map) {
            value = map.get("items");
        } else {
            value = null;
        }
        List<String> entries = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                entries.add(String.valueOf(element));
            }
        } else if (value instanceof String text) {
            entries.add(text);
        }
        Set<String> members = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                String tag = entry.substring(1);
                members.addAll(datapack.getOrDefault(tag, Set.of()));
                for (Map<String, Set<String>> pack : packs) {
                    members.addAll(pack.getOrDefault(tag, Set.of()));
                }
            } else {
                members.add(entry);
            }
        }
        return members;
    }

    private static List<Object> craftingSlots() throws Exception {
        ConfigurationSection root = load(RECIPES).getConfigurationSection("recipes");
        List<Object> slots = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection recipe = root.getConfigurationSection(id);
            if (!"shapeless".equals(recipe.getString("type"))) {
                continue;
            }
            for (Object ingredient : recipe.getList("ingredients")) {
                if (ingredient instanceof ConfigurationSection || ingredient instanceof Map<?, ?>) {
                    slots.add(ingredient);
                }
            }
        }
        return slots;
    }

    private static Set<String> resolvedMembers() throws Exception {
        Map<String, Set<String>> datapack = datapackMembers();
        List<Map<String, Set<String>>> packs = allPacks();
        Set<String> all = new LinkedHashSet<>();
        for (Object slot : craftingSlots()) {
            all.addAll(slotMembers(slot, datapack, packs));
        }
        return all;
    }

    @Test
    void noFinishedItemIsAnIngredientOfASkewerRecipe() throws Exception {
        Set<String> members = resolvedMembers();
        List<String> offenders = new ArrayList<>();
        for (String id : ADDON_FINISHED_ITEMS) {
            if (members.contains(id)) {
                offenders.add(id);
            }
        }
        assertTrue(offenders.isEmpty(), "finished items still satisfy a skewer ingredient: " + offenders);
    }

    @Test
    void theRecipesStayTagDrivenAndKeepTheirIngredients() throws Exception {
        for (Object slot : craftingSlots()) {
            Object value;
            if (slot instanceof ConfigurationSection section) {
                value = section.get("items");
            } else {
                value = ((Map<?, ?>) slot).get("items");
            }
            List<String> entries = new ArrayList<>();
            for (Object element : (List<?>) value) {
                entries.add(String.valueOf(element));
            }
            assertFalse(entries.isEmpty(), "an ingredient slot lost its entries");
            for (String entry : entries) {
                assertTrue(entry.startsWith("#c:"),
                        "the skewer slots must stay tag driven, found " + entries);
            }
        }
        Set<String> members = resolvedMembers();
        for (String id : List.of("minecraft:beef", "minecraft:cooked_beef", "minecraft:cod",
                "minecraft:carrot", "minecraft:brown_mushroom", "farmersdelight:minced_beef",
                "farmersdelight:cod_slice", "farmersdelight:ham")) {
            // crabbersdelight:cooked_crab and endsdelight:raw_dragon_meat are ingredient-class items of sibling
            // packs: they belong in those repositories' tests, not here.
            assertTrue(members.contains(id), id + " must stay a valid skewer ingredient");
        }
    }

    @Test
    void theStickSlotStaysAVanillaStick() throws Exception {
        ConfigurationSection root = load(RECIPES).getConfigurationSection("recipes");
        for (String id : List.of("farmersdelight:meat_skewer", "farmersdelight:vegetable_skewer",
                "farmersdelight:cooked_meat_skewer_from_crafting")) {
            List<?> ingredients = root.getConfigurationSection(id).getList("ingredients");
            assertTrue(ingredients.contains("minecraft:stick"), id + " still needs a plain stick");
        }
    }

    private static YamlConfiguration load(Path path) throws IOException, InvalidConfigurationException {
        File file = path.toFile();
        assertTrue(file.isFile(), "missing file " + file.getAbsolutePath());
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        return config;
    }
}
