package com.huidu.farmersdelight.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.momirealms.craftengine.core.pack.model.definition.ItemModels;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SkilletHandheldModelTest {
    private static final Path PACK = Path.of("src/main/resources/craftengine/farmersdelight");

    @Test
    void vanillaPresetNamesResolveToFullModelPathsAndAllowPackOverrides() {
        var models = new HashMap<String, JsonObject>();
        // Shapes and keys from CE 26.8.2 internal/items and internal/models/item/_all.json.
        HandheldCookingModelPack.addPresetModels(models, "item", Map.of(Key.minecraft("beef"),
                json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/beef\"}}")));
        JsonObject beef = json("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/beef\"}}");
        assertEquals("minecraft:item/beef", HandheldCookingModelPack.flatTexture(beef, models));
        HandheldCookingModelPack.addPresetModels(models, "block", Map.of(Key.minecraft("beef"), json("{}")));
        assertEquals("minecraft:item/beef", HandheldCookingModelPack.flatTexture(beef, models), "Block and item names must not collide");
        models.put("minecraft:item/beef", json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"addon:item/beef\"}}"));
        assertEquals("addon:item/beef", HandheldCookingModelPack.flatTexture(beef, models));
    }

    @Test
    void disablingAtStartupOrReloadSkipsGenerationAndLeavesCachedAssetsAlone(@TempDir Path temp) throws Exception {
        for (boolean initiallyEnabled : new boolean[]{false, true}) {
            Path folder = temp.resolve(Boolean.toString(initiallyEnabled));
            Path cached = folder.resolve("assets/addon/items/generated/handheld/cached.json");
            Files.createDirectories(cached.getParent());
            Files.writeString(cached, "cached model");
            var modified = Files.getLastModifiedTime(cached);
            AtomicBoolean enabled = new AtomicBoolean(initiallyEnabled);
            var generator = new HandheldCookingModels(null, folder, enabled::get, List::of);
            enabled.set(false);
            // No CE or player access is permitted while disabled, including with an existing cache.
            assertDoesNotThrow(() -> generator.onPackCache(null));
            var base = NamespacedKey.fromString("addon:pan");
            assertEquals(base, generator.resolve(null, base, NamespacedKey.fromString("addon:overlay"), null));
            assertEquals("cached model", Files.readString(cached));
            assertEquals(modified, Files.getLastModifiedTime(cached));
        }
        Path absent = temp.resolve("absent");
        new HandheldCookingModels(null, absent, () -> false, List::of).onPackCache(null);
        assertFalse(Files.exists(absent));
    }

    @Test
    void configuredTemplatesResolveWithoutAFixedIngredientList() throws Exception {
        var config = new YamlConfiguration();
        config.load(PACK.resolve("configuration/blocks.yml").toFile());
        var behavior = config.getConfigurationSection("items.farmersdelight:skillet.behavior");
        assertNotNull(behavior);
        assertFalse(behavior.contains("ingredient-models"));
        JsonObject base = readAsset(behavior.getString("cooking-model"), "items");
        JsonObject mesh = readAsset(behavior.getString("ingredient-overlay-model"), "models");
        assertEquals(base.getAsJsonObject("model").get("model").getAsString(), mesh.get("parent").getAsString());
        assertEquals("#food", mesh.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("up").get("texture").getAsString());
        for (int i = 0; i < 64; i++) {
            String food = "addon:food_" + i;
            var cooking = NamespacedKey.fromString("addon:pan");
            var overlay = NamespacedKey.fromString("addon:item/overlay");
            var key = HandheldCookingModelPack.generatedKey(cooking, overlay, food);
            assertEquals("addon", key.getNamespace());
            JsonObject composite = HandheldCookingModelPack.composite(base, key.toString());
            assertDoesNotThrow(() -> ItemModels
                    .fromJson(composite.getAsJsonObject("model")));
            assertEquals(base.get("model"), composite.getAsJsonObject("model").getAsJsonArray("models").get(0));
            assertEquals("addon:item/overlay", HandheldCookingModelPack.overlayModel(overlay.toString(), food)
                    .get("parent").getAsString());
            assertNotEquals(key, HandheldCookingModelPack.generatedKey(NamespacedKey.fromString("other:pan"), overlay, food));
        }
    }

    @Test
    void resolvesInheritedTexturesAndDeclinesUnsupportedOrCyclicModels() {
        var models = new HashMap<String, JsonObject>();
        models.put("addon:item/food", json("{\"parent\":\"addon:item/base\",\"textures\":{\"layer0\":\"#food\",\"food\":\"addon:custom/food\",\"particle\":{\"source\":\"addon:item/particle\"}}}"));
        models.put("addon:item/base", json("{\"parent\":\"minecraft:item/generated\"}"));
        JsonObject item = json("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"addon:item/food\"}}");
        assertEquals("addon:custom/food", HandheldCookingModelPack.flatTexture(item, models));
        item.getAsJsonObject("model").addProperty("type", "model");
        assertEquals("addon:custom/food", HandheldCookingModelPack.flatTexture(item, models), "CE emits unqualified type names");
        models.get("addon:item/base").addProperty("parent", "addon:item/food");
        assertNull(HandheldCookingModelPack.flatTexture(item, models));
        models.get("addon:item/base").addProperty("parent", "minecraft:item/generated");
        models.get("addon:item/food").getAsJsonObject("textures").addProperty("layer1", "addon:extra");
        assertNull(HandheldCookingModelPack.flatTexture(item, models));
        assertNull(HandheldCookingModelPack.flatTexture(json("{\"model\":{\"type\":\"minecraft:special\"}}"), Map.of()));
    }

    @Test
    void resolvesVanillaItemModelToTheItemDefinitionKey() {
        var mappings = new LinkedHashMap<Key, Key>();
        mappings.put(Key.of("addon:beef"), Key.of("internal:obfuscated_beef"));
        var candidates = HandheldCookingModelPack.sourceCandidates(
                "minecraft:beef", "minecraft:item/beef", mappings);
        assertTrue(candidates.contains("minecraft:beef"));
        assertTrue(candidates.contains("minecraft:item/beef"));
        assertTrue(HandheldCookingModelPack.sourceCandidates(
                "paper", "internal:obfuscated_beef", mappings).contains("addon:beef"));
        // An obfuscated item model is the only identity the client item carries, so the authored name
        // recovered from the mappings has to be tried before the material the item was built on.
        assertEquals(List.of("addon:beef", "internal:obfuscated_beef", "paper"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates(
                        "paper", "internal:obfuscated_beef", mappings)));
    }

    @Test
    void customItemModelOutranksTheVanillaMaterialItWasBuiltOn() {
        // Every CraftEngine food is a vanilla material plus a model component: bacon is dried_kelp,
        // beef_patty is beef. Cooking one must show its own texture, not its base material's.
        assertEquals(List.of("farmersdelight:bacon", "minecraft:dried_kelp"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:dried_kelp", "farmersdelight:bacon", Map.of())));
        // Without a model of its own the material remains the only usable source.
        assertEquals(List.of("minecraft:dried_kelp"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:dried_kelp", null, Map.of())));
        // A vanilla item offers its model path, then the definition id stripped from it, then the
        // material. The last two coincide here, so the set collapses them.
        assertEquals(List.of("minecraft:item/paper", "minecraft:paper"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:paper", "minecraft:item/paper", Map.of())));
        assertEquals(List.of("minecraft:item/dried_kelp", "minecraft:dried_kelp"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:dried_kelp", "minecraft:item/dried_kelp", Map.of())),
                "The material must never be reached before the item's own model");
    }

    @Test
    void clientBoundIngredientsResolveThroughTheCraftEngineItemId() {
        // item.client-bound-model: true keeps the item-model component off the server-side stack, so a
        // client-bound food offers no item model at all and the item's server-side CraftEngine id is the
        // only thing identifying it. Without it the material would be the sole source and every custom
        // food would cook with the vanilla texture of whatever item it was built on.
        assertEquals(List.of("farmersdelight:bacon", "minecraft:dried_kelp"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:dried_kelp", null, "farmersdelight:bacon", Map.of())));
        // A stack whose model is still readable keeps that model first: it is the more specific id.
        assertEquals(List.of("farmersdelight:item/bacon", "farmersdelight:bacon", "minecraft:dried_kelp"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:dried_kelp", "farmersdelight:item/bacon", "farmersdelight:bacon", Map.of())));
        // The CraftEngine item definition and the model it names, exactly as the generated pack ships them.
        JsonObject bacon = json("{\"oversized_in_gui\":true,\"model\":{\"type\":\"model\",\"model\":\"farmersdelight:item/bacon\"}}");
        var models = Map.of("farmersdelight:item/bacon",
                json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"farmersdelight:item/bacon\"}}"));
        String texture = HandheldCookingModelPack.flatTexture(bacon, models);
        assertEquals("farmersdelight:item/bacon", texture);
        // The generated overlay must therefore name the CraftEngine model, never the vanilla one.
        assertEquals("farmersdelight:item/bacon", HandheldCookingModelPack
                .overlayModel("farmersdelight:item/skillet_food", texture)
                .getAsJsonObject("textures").get("food").getAsString());
    }

    @Test
    void ingredientWithoutACraftEngineModelKeepsTheVanillaTexture() {
        // The fallback lives in sourceCandidates, which appends the ingredient's own material last:
        // an id CraftEngine does not define keeps the preset model, so genuine vanilla foods still cook.
        assertEquals(List.of("minecraft:beef"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates("minecraft:beef", null, null, Map.of())));
        assertEquals(List.of("minecraft:item/beef", "minecraft:beef"),
                List.copyOf(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:beef", "minecraft:item/beef", null, Map.of())));
        var models = Map.of("minecraft:item/beef",
                json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/beef\"}}"));
        assertEquals("minecraft:item/beef", HandheldCookingModelPack.flatTexture(
                json("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/beef\"}}"), models));
    }

    @Test
    void craftEngineIngredientResolvesToItsOwnOverlayNotTheMaterialItWasBuiltOn() throws Exception {
        // Deployed data: farmersdelight:bacon is a CraftEngine item built on minecraft:dried_kelp
        // (configuration/items.yml -> farmersdelight:food_cut_template), and CraftEngine generates the
        // item definition below from the item's model. The model chain ends in the plugin's texture.
        var items = new HashMap<String, JsonObject>();
        items.put("farmersdelight:bacon", json(
                "{\"oversized_in_gui\":true,\"model\":{\"type\":\"model\",\"model\":\"farmersdelight:item/bacon\"}}"));
        items.put("minecraft:dried_kelp", json(
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/dried_kelp\"}}"));
        var models = new HashMap<String, JsonObject>();
        models.put("minecraft:item/dried_kelp", json(
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/dried_kelp\"}}"));
        HandheldCookingModelPack.readAssets(PACK.resolve("resourcepack"), items, models);
        var cooking = NamespacedKey.fromString("farmersdelight:skillet_cooking");
        var overlay = NamespacedKey.fromString("farmersdelight:item/skillet_food");

        // Generation is driven by sourceCandidates on the server-side stack, where
        // item.client-bound-model: true has left no item model, so the CraftEngine id is the only
        // identity and the material is the last resort.
        var generated = generatedOverlays(
                HandheldCookingModelPack.sourceCandidates("minecraft:dried_kelp", null, "farmersdelight:bacon", Map.of()),
                items, models, cooking, overlay);
        assertEquals("farmersdelight:item/bacon",
                generated.get("farmersdelight:generated/handheld/farmersdelight_skillet_cooking/farmersdelight/bacon"));
        assertEquals("minecraft:item/dried_kelp",
                generated.get("farmersdelight:generated/handheld/farmersdelight_skillet_cooking/minecraft/dried_kelp"));

        // resolve() runs on the client-bound copy, which still knows the model, and takes the first
        // candidate that exists. The CraftEngine overlay therefore wins over the material's vanilla one.
        var resolved = resolveSource(
                HandheldCookingModelPack.sourceCandidates(
                        "minecraft:dried_kelp", "farmersdelight:item/bacon", "farmersdelight:bacon", Map.of()),
                generated, cooking, overlay);
        assertEquals("farmersdelight:bacon", resolved);
        assertEquals("farmersdelight:item/bacon", generated.get(resolvedKey(resolved, cooking, overlay)));
        assertNotEquals("minecraft:dried_kelp", resolved);
        // Both call sites must derive the same candidate list, or generation writes a name resolve
        // never looks for: everything the server-side stack offers must also be offered by the
        // client-bound copy resolve() reads.
        assertTrue(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:dried_kelp", "farmersdelight:item/bacon", "farmersdelight:bacon", Map.of())
                .containsAll(HandheldCookingModelPack.sourceCandidates(
                        "minecraft:dried_kelp", null, "farmersdelight:bacon", Map.of())));
    }

    @Test
    void vanillaOnlyIngredientKeepsThePresetOverlay() {
        var items = new HashMap<String, JsonObject>();
        items.put("minecraft:beef", json("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/beef\"}}"));
        var models = new HashMap<String, JsonObject>();
        models.put("minecraft:item/beef", json(
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/beef\"}}"));
        var cooking = NamespacedKey.fromString("farmersdelight:skillet_cooking");
        var overlay = NamespacedKey.fromString("farmersdelight:item/skillet_food");
        // A genuine vanilla food carries no CraftEngine id and, on the server-side stack, no item model.
        var candidates = HandheldCookingModelPack.sourceCandidates("minecraft:beef", null, null, Map.of());
        assertEquals(List.of("minecraft:beef"), List.copyOf(candidates));
        var generated = generatedOverlays(candidates, items, models, cooking, overlay);
        assertEquals("minecraft:item/beef", generated.get(
                "farmersdelight:generated/handheld/farmersdelight_skillet_cooking/minecraft/beef"));
        assertEquals("minecraft:beef", resolveSource(candidates, generated, cooking, overlay));
    }

    /** The composites onPackCache would write for the given sources: name -> the food texture it names. */
    private static Map<String, String> generatedOverlays(Iterable<String> sources, Map<String, JsonObject> items,
                                                         Map<String, JsonObject> models,
                                                         NamespacedKey cooking, NamespacedKey overlay) {
        var generated = new LinkedHashMap<String, String>();
        for (String source : sources) {
            JsonObject item = items.get(source);
            if (item == null) continue;
            String texture = HandheldCookingModelPack.flatTexture(item, models);
            if (texture == null) continue;
            generated.put(resolvedKey(source, cooking, overlay), texture);
        }
        return generated;
    }

    /** The first candidate resolve() finds an available overlay for, or null when it falls back to the pan. */
    private static String resolveSource(Iterable<String> sources, Map<String, String> generated,
                                       NamespacedKey cooking, NamespacedKey overlay) {
        for (String source : sources) {
            if (generated.containsKey(resolvedKey(source, cooking, overlay))) return source;
        }
        return null;
    }

    private static String resolvedKey(String source, NamespacedKey cooking, NamespacedKey overlay) {
        return HandheldCookingModelPack.generatedKey(cooking, overlay, source).toString();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private JsonObject readAsset(String id, String type) throws Exception {
        NamespacedKey key = NamespacedKey.fromString(id);
        assertNotNull(key);
        return json(Files.readString(PACK.resolve("resourcepack/assets").resolve(key.getNamespace())
                .resolve(type).resolve(key.getKey() + ".json")));
    }
}
