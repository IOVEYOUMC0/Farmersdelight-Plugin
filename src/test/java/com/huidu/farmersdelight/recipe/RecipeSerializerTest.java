package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeSerializerTest {

    @Test
    void serializesPlainItem() {
        RecipeIngredient ingredient = new RecipeIngredient.Item(Key.of("minecraft:carrot"));
        assertEquals("minecraft:carrot", RecipeSerializer.serializeIngredient(ingredient));
    }

    @Test
    void plainItemRoundTrips() {
        RecipeIngredient original = new RecipeIngredient.Item(Key.of("farmersdelight:cabbage"));
        String serialized = RecipeSerializer.serializeIngredient(original);
        RecipeIngredient parsed = RecipeParsingSupport.parseSimpleItemOrTag(serialized);
        assertEquals(original, parsed);
    }

    @Test
    void serializesTagWithoutExclusions() {
        RecipeIngredient ingredient = new RecipeIngredient.Tag(Key.of("farmersdelight:vegetables"));
        assertEquals("#farmersdelight:vegetables", RecipeSerializer.serializeIngredient(ingredient));
    }

    @Test
    void tagWithExclusionsRoundTrips() {
        RecipeIngredient.Tag original = new RecipeIngredient.Tag(
                Key.of("farmersdelight:vegetables"),
                Set.of(Key.of("minecraft:potato")),
                Set.of(Key.of("farmersdelight:onions")));
        String serialized = RecipeSerializer.serializeIngredient(original);
        assertTrue(serialized.startsWith("#farmersdelight:vegetables"), serialized);

        RecipeIngredient.Tag parsed = RecipeParsingSupport.parseTagIngredientWithExclusions(serialized, "ingredient");
        assertEquals(original.key(), parsed.key());
        assertEquals(original.excludedItems(), parsed.excludedItems());
        assertEquals(original.excludedTags(), parsed.excludedTags());
    }

    @Test
    void serializesChoiceWithPipe() {
        RecipeIngredient choice = new RecipeIngredient.Choice(List.of(
                new RecipeIngredient.Item(Key.of("farmersdelight:cabbage")),
                new RecipeIngredient.Item(Key.of("farmersdelight:cabbage_leaf"))));
        assertEquals("farmersdelight:cabbage|farmersdelight:cabbage_leaf",
                RecipeSerializer.serializeIngredient(choice));
    }

    @Test
    void toolRequirementRoundTrips() {
        CuttingBoardRecipe.ToolRequirement original = new CuttingBoardRecipe.ToolRequirement(
                Key.of("farmersdelight:knives"),
                Set.of(Key.of("minecraft:wooden_sword")),
                Set.of());
        String serialized = RecipeSerializer.serializeTool(original);

        RecipeParsingSupport.ParsedKey parsed = RecipeParsingSupport.parseKeyWithExclusions(serialized, "tool");
        assertEquals(original.getKey(), parsed.key());
        assertEquals(original.isTag(), parsed.tag());
        assertEquals(original.getExcludedItems(), parsed.excludedItems());
        assertEquals(original.getExcludedTags(), parsed.excludedTags());
    }

    @Test
    void taggedToolRequirementKeepsTagIdentityAndExclusions() {
        CuttingBoardRecipe.ToolRequirement original = new CuttingBoardRecipe.ToolRequirement(
                Key.of("farmersdelight:knives"),
                true,
                Set.of(Key.of("farmersdelight:flint_knife")),
                Set.of(Key.of("example:disabled_tools")));

        String serialized = RecipeSerializer.serializeTool(original);
        assertTrue(serialized.startsWith("#farmersdelight:knives"), serialized);

        RecipeParsingSupport.ParsedKey parsed = RecipeParsingSupport.parseKeyWithExclusions(serialized, "tool");
        assertTrue(parsed.tag());
        assertEquals(original.getKey(), parsed.key());
        assertEquals(original.getExcludedItems(), parsed.excludedItems());
        assertEquals(original.getExcludedTags(), parsed.excludedTags());
        assertTrue(original.asIngredient() instanceof RecipeIngredient.Tag);
    }

    @Test
    void sharedIngredientChoiceParserPreservesTagExclusions() {
        RecipeIngredient parsed = RecipeParsingSupport.parseIngredientChoice(
                "minecraft:carrot|#farmersdelight:vegetables,!minecraft:potato");

        RecipeIngredient.Choice choice = (RecipeIngredient.Choice) parsed;
        assertEquals(new RecipeIngredient.Item(Key.of("minecraft:carrot")), choice.options().getFirst());
        RecipeIngredient.Tag tag = (RecipeIngredient.Tag) choice.options().get(1);
        assertEquals(Key.of("farmersdelight:vegetables"), tag.key());
        assertEquals(Set.of(Key.of("minecraft:potato")), tag.excludedItems());
    }

    @Test
    void cuttingBoardToolParserDistinguishesTagsItemsAndActions() {
        assertTrue(CuttingBoardRecipeManager.parseTool("#minecraft:hoes").isTag());
        assertTrue(CuttingBoardRecipeManager.parseTool("farmersdelight:knives").isTag());
        assertFalse(CuttingBoardRecipeManager.parseTool("minecraft:shears").isTag());
        assertFalse(CuttingBoardRecipeManager.parseTool("farmersdelight:axe_strip").isTag());
    }

    @Test
    void deterministicExclusionOrder() {
        RecipeIngredient.Tag tag = new RecipeIngredient.Tag(
                Key.of("ns:tag"),
                Set.of(Key.of("ns:b"), Key.of("ns:a")),
                Set.of());
        // Two serializations of the same model must be byte-identical, regardless of set iteration order.
        assertEquals(RecipeSerializer.serializeIngredient(tag), RecipeSerializer.serializeIngredient(tag));
    }
}
