package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookingPotRecipeManagerTest {

    // "container: none" (also "air" or an empty string) is how a recipe declares that it needs no container even
    // though its result declares a remainder, so the loader must not infer one for those entries. A real item id,
    // a custom-item snapshot map and a missing value all keep the inference active.
    @Test
    void recognisesTheExplicitNoContainerValues() {
        assertTrue(CookingPotRecipeManager.isContainerOptOut("none"));
        assertTrue(CookingPotRecipeManager.isContainerOptOut("None"));
        assertTrue(CookingPotRecipeManager.isContainerOptOut("  NONE  "));
        assertTrue(CookingPotRecipeManager.isContainerOptOut("air"));
        assertTrue(CookingPotRecipeManager.isContainerOptOut(""));
        assertTrue(CookingPotRecipeManager.isContainerOptOut("   "));

        assertFalse(CookingPotRecipeManager.isContainerOptOut("minecraft:bowl"));
        assertFalse(CookingPotRecipeManager.isContainerOptOut("farmersdelight:milk_bottle"));
        assertFalse(CookingPotRecipeManager.isContainerOptOut(Map.of("item", "minecraft:bowl")));
        assertFalse(CookingPotRecipeManager.isContainerOptOut(null));
    }

    // A corn dog's remainder is a stick and a ham's is a bone: those are tools, not the container the meal is
    // served in, so the configured exclusions keep them from becoming a recipe's required container. Either id
    // form matches and the comparison ignores case, while bowls/bottles/buckets stay usable.
    @Test
    void toolRemaindersAreNeverUsedAsAContainer() {
        var excluded = Set.of("minecraft:stick", "minecraft:bone");

        assertTrue(CookingPotRecipeManager.isExcludedRemainder(excluded, null, "minecraft:stick"));
        assertTrue(CookingPotRecipeManager.isExcludedRemainder(excluded, "corndelight:corn_dog", "minecraft:stick"));
        assertTrue(CookingPotRecipeManager.isExcludedRemainder(excluded, "endsdelight:ham", "Minecraft:Bone"));

        assertFalse(CookingPotRecipeManager.isExcludedRemainder(excluded, null, "minecraft:bowl"));
        assertFalse(CookingPotRecipeManager.isExcludedRemainder(excluded, "farmersdelight:milk_bottle", "minecraft:honey_bottle"));
        assertFalse(CookingPotRecipeManager.isExcludedRemainder(Set.of(), null, "minecraft:stick"));
        assertFalse(CookingPotRecipeManager.isExcludedRemainder(excluded, null, null));
    }
}
