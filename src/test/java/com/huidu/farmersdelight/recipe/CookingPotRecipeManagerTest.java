package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // Only an Item ingredient with an nbt snapshot makes matching depend on the item's data. A tag and a plain
    // item match on ids, and a choice is as data-sensitive as any of its options.
    @Test
    void nbtConstrainedIngredientsAreDetected() {
        Key diamond = Key.of("minecraft:diamond");

        assertFalse(CookingPotRecipeManager.ingredientConstrainsNbt(new RecipeIngredient.Item(diamond)));
        assertTrue(CookingPotRecipeManager.ingredientConstrainsNbt(new RecipeIngredient.Item(diamond, "AQID")));
        assertFalse(CookingPotRecipeManager.ingredientConstrainsNbt(new RecipeIngredient.Tag(Key.of("c:foods"))));
        assertFalse(CookingPotRecipeManager.ingredientConstrainsNbt(new RecipeIngredient.Choice(
                List.of(new RecipeIngredient.Item(diamond), new RecipeIngredient.Item(Key.of("minecraft:coal"))))));
        assertTrue(CookingPotRecipeManager.ingredientConstrainsNbt(new RecipeIngredient.Choice(
                List.of(new RecipeIngredient.Item(diamond), new RecipeIngredient.Item(diamond, "AQID")))));
    }

    // One such ingredient anywhere in the loaded set is enough to make the cache key carry item data, including
    // when the recipe only lives in a custom group, since a pot cooking from one shares both caches.
    @Test
    void anyLoadedRecipeCanFlipTheNbtFlag() {
        Key diamond = Key.of("minecraft:diamond");
        CookingPotRecipe plain = recipe("plain", new RecipeIngredient.Item(diamond));
        CookingPotRecipe constrained = recipe("constrained", new RecipeIngredient.Item(diamond, "AQID"));

        assertFalse(CookingPotRecipeManager.anyRecipeConstrainsNbt(Map.of("plain", plain), Map.of()));
        assertTrue(CookingPotRecipeManager.anyRecipeConstrainsNbt(Map.of("plain", plain),
                Map.of("drinks", Map.of("constrained", constrained))));
        assertTrue(CookingPotRecipeManager.anyRecipeConstrainsNbt(Map.of("constrained", constrained), Map.of()));
    }

    // With no data-constrained recipe the per-input part is exactly the id and the amount clamped to the
    // matcher's unit cap, which is the string the cache was always keyed on.
    @Test
    void slotKeyKeepsTheOriginalIdAndAmountWithoutAFingerprint() {
        assertEquals("minecraft:diamond:1", CookingPotRecipeManager.slotKey("minecraft:diamond", 1, 4, null));
        assertEquals("minecraft:diamond:4", CookingPotRecipeManager.slotKey("minecraft:diamond", 9, 4, null));
        assertEquals("none:1", CookingPotRecipeManager.slotKey("none", 1, 4, null));
    }

    // Two inputs that share an id and an amount but differ in item data must not share a key: the matcher
    // compares the data, so a shared entry would answer one of them with the other's recipe (or with a miss).
    @Test
    void aFingerprintSeparatesInputsThatShareAnId() {
        String withoutData = CookingPotRecipeManager.slotKey("minecraft:diamond", 1, 4, null);
        String named = CookingPotRecipeManager.slotKey("minecraft:diamond", 1, 4, "AQID");
        String otherName = CookingPotRecipeManager.slotKey("minecraft:diamond", 1, 4, "BAUG");

        assertNotEquals(withoutData, named);
        assertNotEquals(named, otherName);
        assertEquals(named, CookingPotRecipeManager.slotKey("minecraft:diamond", 1, 4, "AQID"));
        assertEquals("minecraft:diamond:1#AQID", named);
    }

    // The assembled key keeps the pre-existing shape at every position, so a server whose recipes constrain no
    // item data uses byte-identical keys to before; with a fingerprint present the data still reaches the key.
    @Test
    void assembledKeyKeepsTheOriginalFormat() {
        assertEquals("a:1;b:2;|container=minecraft:bowl",
                CookingPotRecipeManager.assembleCacheKey(List.of("b:2", "a:1"), "minecraft:bowl", null));
        assertEquals("a:1;|container=none|group=drinks",
                CookingPotRecipeManager.assembleCacheKey(List.of("a:1"), "none", "drinks"));

        String first = CookingPotRecipeManager.assembleCacheKey(
                List.of(CookingPotRecipeManager.slotKey("minecraft:diamond", 1, 4, "AQID")), "none", null);
        String second = CookingPotRecipeManager.assembleCacheKey(
                List.of(CookingPotRecipeManager.slotKey("minecraft:diamond", 1, 4, "BAUG")), "none", null);
        assertEquals("minecraft:diamond:1#AQID;|container=none", first);
        assertNotEquals(first, second);
    }

    // A null key is the "this combination must not be cached" signal: the lookup still matches live, and it must
    // leave both tables exactly as they were. A cacheable lookup is run first so a write would be visible.
    @Test
    void anUncacheableLookupLeavesBothCachesUntouched() throws Exception {
        CookingPotRecipeManager manager = new CookingPotRecipeManager(null);

        assertNull(manager.matchWithCaches("seed", List.of(), null, null));
        assertEquals(Set.of("seed"), Set.copyOf(missesOf(manager)));

        assertNull(manager.matchWithCaches(null, List.of(), null, null));

        assertEquals(Set.of("seed"), Set.copyOf(missesOf(manager)));
        assertTrue(cacheOf(manager).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CookingPotRecipe> cacheOf(CookingPotRecipeManager manager) throws Exception {
        return (Map<String, CookingPotRecipe>) privateField(manager, "recipeCache");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> missesOf(CookingPotRecipeManager manager) throws Exception {
        return (Set<String>) privateField(manager, "recipeMisses");
    }

    private static Object privateField(CookingPotRecipeManager manager, String name) throws Exception {
        Field field = CookingPotRecipeManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(manager);
    }

    private static CookingPotRecipe recipe(String id, RecipeIngredient... ingredients) {
        return new CookingPotRecipe(id, List.of(ingredients), null, false, null, 0.0F, 100, null, 0);
    }
}
