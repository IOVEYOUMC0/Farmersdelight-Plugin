package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ingredient parsing against the advanced tag groups the loaded packs declare.
 *
 *
 * An advtag: ingredient is only meaningful with a published snapshot, so these tests drive the same
 * setter the recipe loaders call. A group that cannot be resolved must refuse the recipe rather than parse
 * into an ingredient that can never match; the plain item and tag forms must be untouched by all of this.
 * Each test clears the snapshot afterwards, because the field is static and would otherwise leak a
 * declaration into the next test.
 */
class RecipeParsingSupportTest {

    @AfterEach
    void clearGroups() {
        RecipeParsingSupport.setAdvancedTagGroups(AdvancedTagGroups.EMPTY);
    }

    private static void declare(Map<String, List<String>> declared) {
        RecipeParsingSupport.setAdvancedTagGroups(AdvancedTagGroups.compile(declared));
    }

    private static Map<String, List<String>> declared(String... pairs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], List.of(pairs[i + 1].split(",")));
        }
        return map;
    }

    @Test
    void aDeclaredGroupBecomesAChoiceOfItsItemsInDeclaredOrder() {
        declare(declared("meats", "minecraft:beef,minecraft:porkchop,minecraft:chicken"));

        RecipeIngredient ingredient = RecipeParsingSupport.parseIngredientChoice("advtag:meats");

        RecipeIngredient.Choice choice = assertInstanceOf(RecipeIngredient.Choice.class, ingredient);
        assertEquals(List.of(
                new RecipeIngredient.Item(Key.of("minecraft:beef")),
                new RecipeIngredient.Item(Key.of("minecraft:porkchop")),
                new RecipeIngredient.Item(Key.of("minecraft:chicken"))), choice.options());
    }

    @Test
    void aSingleMemberGroupBecomesThatItemRatherThanAChoice() {
        declare(declared("meats", "minecraft:beef"));

        RecipeIngredient ingredient = RecipeParsingSupport.parseIngredientChoice("advtag:meats");

        assertEquals(new RecipeIngredient.Item(Key.of("minecraft:beef")), ingredient);
    }

    @Test
    void aGroupReferenceResolvesThroughAChoiceOptionToo() {
        declare(declared("meats", "minecraft:beef"));

        RecipeIngredient ingredient = RecipeParsingSupport.parseIngredientChoice("advtag:meats|minecraft:carrot");

        RecipeIngredient.Choice choice = assertInstanceOf(RecipeIngredient.Choice.class, ingredient);
        assertEquals(List.of(
                new RecipeIngredient.Item(Key.of("minecraft:beef")),
                new RecipeIngredient.Item(Key.of("minecraft:carrot"))), choice.options());
    }

    @Test
    void anUndeclaredGroupRefusesTheIngredient() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RecipeParsingSupport.parseIngredientChoice("advtag:meats"));

        assertTrue(error.getMessage().contains("meats"), error.getMessage());
    }

    @Test
    void aDroppedGroupRefusesTheIngredient() {
        declare(Map.of("a", List.of("advtag:a")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RecipeParsingSupport.parseIngredientChoice("advtag:a"));

        assertTrue(error.getMessage().contains("could not be resolved"), error.getMessage());
    }

    @Test
    void anEditorGeneratedItemMapWithAGroupReferenceResolvesTheSameWay() {
        declare(declared("meats", "minecraft:beef,minecraft:porkchop"));

        RecipeIngredient ingredient = RecipeParsingSupport.parseIngredientValue(Map.of("item", "advtag:meats"));

        RecipeIngredient.Choice choice = assertInstanceOf(RecipeIngredient.Choice.class, ingredient);
        assertEquals(List.of(
                new RecipeIngredient.Item(Key.of("minecraft:beef")),
                new RecipeIngredient.Item(Key.of("minecraft:porkchop"))), choice.options());
    }

    @Test
    void aPlainItemIdStillParsesAsThatItem() {
        declare(declared("meats", "minecraft:beef"));

        assertEquals(new RecipeIngredient.Item(Key.of("minecraft:carrot")),
                RecipeParsingSupport.parseIngredientChoice("minecraft:carrot"));
    }

    @Test
    void aTagIngredientStillParsesAsATag() {
        declare(declared("meats", "minecraft:beef"));

        assertEquals(new RecipeIngredient.Tag(Key.of("minecraft:meats")),
                RecipeParsingSupport.parseIngredientChoice("#minecraft:meats"));
    }
}
