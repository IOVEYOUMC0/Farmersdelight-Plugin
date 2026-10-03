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
