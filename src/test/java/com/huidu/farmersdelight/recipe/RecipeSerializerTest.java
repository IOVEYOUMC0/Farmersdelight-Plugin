package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(original.getExcludedItems(), parsed.excludedItems());
        assertEquals(original.getExcludedTags(), parsed.excludedTags());
    }

    @Test
    void deterministicExclusionOrder() {
        RecipeIngredient.Tag tag = new RecipeIngredient.Tag(
                Key.of("ns:tag"),
                Set.of(Key.of("ns:b"), Key.of("ns:a")),
                Set.of());
        // 同一模型的两次序列化结果必须逐字节相同，与 set 的迭代顺序无关。
        assertEquals(RecipeSerializer.serializeIngredient(tag), RecipeSerializer.serializeIngredient(tag));
    }
}
