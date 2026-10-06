package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.api.recipe.FillOutcome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared book reports a fill attempt on the fill button. A book that configures a button per
 * outcome gets that button; a book that does not gets the status appended as a lore line on the fill
 * button, which is how the book behaved before the per-outcome buttons existed.
 */
class RecipeBookFillVariantTest {

    private static final Path GUI = Path.of("src/main/resources/gui.yml");
    private static final Path RECIPE_BOOK_GUI = Path.of("src/main/java/com/huidu/farmersdelight/gui/recipebook/RecipeBookGui.java");

    @Test
    void everyOutcomeResolvesToItsOwnButtonOrToTheStatusLine() {
        assertEquals("fill-success", RecipeBookGui.fillVariantKey(FillOutcome.FILLED));
        assertEquals("fill-missing", RecipeBookGui.fillVariantKey(FillOutcome.MISSING_INGREDIENTS));
        assertEquals("fill-inventory-full", RecipeBookGui.fillVariantKey(FillOutcome.INVENTORY_FULL));
        assertNull(RecipeBookGui.fillVariantKey(FillOutcome.NOTHING), "nothing happened, so no button changes");

        assertNull(RecipeBookGui.fillStatusKey(FillOutcome.FILLED), "the filler reopens the station instead");
        assertEquals("gui.recipe.missing_ingredients", RecipeBookGui.fillStatusKey(FillOutcome.MISSING_INGREDIENTS));
        assertEquals("gui.recipe.inventory_full", RecipeBookGui.fillStatusKey(FillOutcome.INVENTORY_FULL));
        assertNull(RecipeBookGui.fillStatusKey(FillOutcome.NOTHING));
    }

    @Test
    void theShippedBookConfiguresAllFourFillButtons() throws IOException, InvalidConfigurationException {
        ConfigurationSection items = detailItems();
        for (String key : new String[]{"fill", "fill-success", "fill-missing", "fill-inventory-full"}) {
            ConfigurationSection item = items.getConfigurationSection(key);
            assertNotNull(item, "recipe-book detail is missing " + key);
            assertEquals("<green><lang:gui.recipe.fill>", item.getString("name"), key + " shares the fill label");
        }
        assertEquals("HOPPER", items.getString("fill-success.material"));
        assertEquals("HOPPER", items.getString("fill-missing.material"));
        assertEquals("BARRIER", items.getString("fill-inventory-full.material"),
                "a full inventory is a refusal, so that button reads as one");

        assertTrue(items.getStringList("fill.lore").contains("<yellow><lang:gui.recipe.fill_hint>"));
        assertTrue(items.getStringList("fill.lore").contains("<gray><lang:gui.recipe.fill_hint_shift>"));
        assertTrue(items.getStringList("fill-success.lore").contains("<green><lang:gui.recipe.fill_success>"));
        assertTrue(items.getStringList("fill-missing.lore").contains("<red><lang:gui.recipe.fill_missing>"));
        assertTrue(items.getStringList("fill-inventory-full.lore").contains("<red><lang:gui.recipe.fill_inventory_full>"));
    }

    @Test
    void bothShippedLocalesCarryEveryFillText() throws IOException {
        for (String locale : List.of("en_us", "zh_cn")) {
            String json = Files.readString(Path.of("src/main/resources/craftengine/farmersdelight/resourcepack/assets/farmersdelight/lang/" + locale + ".json"));
            for (String key : List.of("gui.recipe.fill", "gui.recipe.fill_hint", "gui.recipe.fill_hint_shift",
                    "gui.recipe.fill_success", "gui.recipe.fill_missing", "gui.recipe.fill_inventory_full")) {
                assertTrue(json.contains("\"" + key + "\""), locale + " is missing " + key);
            }
        }
    }

    @Test
    void theBookLooksUpTheOutcomeButtonBeforeFallingBackToTheStatusLine() throws IOException {
        String source = Files.readString(RECIPE_BOOK_GUI);
        int variantLookup = source.indexOf("cfg.button(variantKey)");
        int loreFallback = source.indexOf("Text.deserialize(I18n.get(statusKey, player))");
        assertTrue(variantLookup > 0, "the book must look up the outcome's own button");
        assertTrue(source.contains("if (variant != null)"), "a configured variant wins over the lore fallback");
        assertTrue(loreFallback > variantLookup, "the lore fallback has to come after the variant lookup");
        assertTrue(source.contains("cfg.button(\"fill\")"), "an unconfigured book keeps the base fill button");
    }

    private static ConfigurationSection detailItems() throws IOException, InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(Files.readString(GUI));
        ConfigurationSection detail = configuration.getConfigurationSection("recipe-book-gui.detail");
        assertNotNull(detail, "gui.yml has no recipe-book-gui.detail section");
        ConfigurationSection items = detail.getConfigurationSection("items");
        assertNotNull(items, "the recipe-book detail page has no items");
        return items;
    }
}
