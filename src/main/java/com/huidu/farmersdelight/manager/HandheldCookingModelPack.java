package com.huidu.farmersdelight.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.NamespacedKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The pure half of the handheld cooking model generation: naming a generated model, reading the item/model
 * JSON an addon or CraftEngine ships, flattening a model chain down to its texture, and turning that into the
 * composite definition the pack serves. None of it touches the plugin, the data folder or the pack event, so
 * it is separated from HandheldCookingModels, which owns the cache callback, the generated-file
 * cleanup and the "is this model available" lookup.
 */
final class HandheldCookingModelPack {

    private HandheldCookingModelPack() {
    }

    static void addPresetModels(Map<String, JsonObject> models, String directory, Map<Key, JsonObject> presets) {
        // CE indexes each preset directory by its relative name (beef), while model parents
        // and item definitions reference the full model path (minecraft:item/beef).
        presets.forEach((key, value) -> models.put(key.namespace() + ":" + directory + "/" + key.value(), value));
    }

    static NamespacedKey generatedKey(NamespacedKey cooking, NamespacedKey overlay, String source) {
        // Name the generated file after the ingredient id so the pack is readable instead of hashed.
        // A pan acts as a directory level so two pans with different shapes never share a composite,
        // and the ingredient namespace is kept as its own directory so a vanilla and a custom item
        // with the same value (e.g. beef) do not overwrite each other. The overlay template is common
        // to all foods of a pan, so it takes no part in the name.
        String food = source;
        int separator = food.indexOf(':');
        String foodNs = separator < 0 ? "unknown" : food.substring(0, separator);
        String foodId = separator < 0 ? food : food.substring(separator + 1);
        String safeFood = foodId.replace('.', '_').replace('/', '_');
        String pan = cooking.getNamespace() + "_" + cooking.getKey();
        return NamespacedKey.fromString(cooking.getNamespace() + ":generated/handheld/" + pan + "/" + foodNs + "/" + safeFood);
    }

    static JsonObject overlayModel(String parent, String texture) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", parent);
        JsonObject textures = new JsonObject();
        textures.addProperty("food", texture);
        model.add("textures", textures);
        return model;
    }

    static JsonObject composite(JsonObject base, String overlay) {
        JsonObject definition = base.deepCopy();
        JsonArray parts = new JsonArray();
        parts.add(base.get("model").deepCopy());
        JsonObject food = new JsonObject();
        food.addProperty("type", "minecraft:model");
        food.addProperty("model", overlay);
        parts.add(food);
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:composite");
        model.add("models", parts);
        definition.add("model", model);
        definition.addProperty("hand_animation_on_swap", false);
        return definition;
    }

    static String flatTexture(JsonObject item, Map<String, JsonObject> models) {
        if (!item.has("model") || !item.get("model").isJsonObject()) return null;
        JsonObject node = item.getAsJsonObject("model");
        if (!node.has("type") || !"minecraft:model".equals(qualified(node.get("type").getAsString()))
                || !node.has("model") || node.has("tints")) return null;
        String current = node.get("model").getAsString();
        Set<String> visited = new HashSet<>();
        Map<String, String> textures = new HashMap<>();
        while (visited.add(current) && visited.size() <= 32) {
            NamespacedKey key = NamespacedKey.fromString(current);
            if (key == null) return null;
            current = key.toString();
            if (current.equals("minecraft:item/generated") || current.equals("minecraft:item/handheld")) {
                if (textures.containsKey("layer1")) return null;
                String texture = textures.get("layer0");
                Set<String> aliases = new HashSet<>();
                while (texture != null && texture.startsWith("#") && aliases.add(texture)) {
                    texture = textures.get(texture.substring(1));
                }
                return texture != null && !texture.startsWith("#") ? texture : null;
            }
            JsonObject model = models.get(current);
            if (model == null || model.has("elements") || !model.has("parent")) return null;
            if (model.has("textures") && model.get("textures").isJsonObject()) {
                model.getAsJsonObject("textures").entrySet().forEach(e -> {
                    JsonElement value = e.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        textures.putIfAbsent(e.getKey(), value.getAsString());
                    }
                });
            }
            JsonElement parent = model.get("parent");
            if (!parent.isJsonPrimitive() || !parent.getAsJsonPrimitive().isString()) return null;
            current = parent.getAsString();
        }
        return null;
    }

    /**
     * Candidate texture sources for an ingredient, most specific first.
     *
     *
     * A custom item is a vanilla material carrying a model component, so its vanillaId is
     * only the material it was built on: a CraftEngine bacon is literally minecraft:dried_kelp.
     * The item model is the item's own identity and must therefore be tried before that material,
     * otherwise every custom food cooks with the texture of whatever base material it was built on.
     * The material stays last as the fallback for items that carry no model of their own.
     */
    static Set<String> sourceCandidates(String vanillaId, String itemModel,
                                        Map<Key, Key> obfuscationMappings) {
        return sourceCandidates(vanillaId, itemModel, null, obfuscationMappings);
    }

    /**
     * Candidate texture sources for an ingredient, most specific first, including the CraftEngine
     * item id the stack carries on the server side.
     *
     *
     * With item.client-bound-model: true CraftEngine keeps the item-model component off the
     * server-side stack, so itemModel is empty there even though the client is told to render
     * the CE model. The CE item id survives in the stack's persistent data and identifies the item's
     * generated definition, which is what the item/model lookup is keyed by. Without it the only
     * candidate left is the base material, and every custom food would cook with the vanilla texture
     * of whatever material it was built on.
     */
    static Set<String> sourceCandidates(String vanillaId, String itemModel, String customItemId,
                                        Map<Key, Key> obfuscationMappings) {
        Set<String> sources = new LinkedHashSet<>();
        boolean hasModel = itemModel != null && !itemModel.isEmpty();
        boolean hasCustomId = customItemId != null && !customItemId.isEmpty();
        if (!hasModel && !hasCustomId) {
            if (vanillaId != null && !vanillaId.isEmpty()) sources.add(vanillaId);
            return sources;
        }
        // Obfuscation maps an authored id to the id the client is sent, so reading it backwards
        // recovers the name the generated models were written under.
        if (hasModel) {
            addAuthoredNamesFor(sources, itemModel, obfuscationMappings);
            sources.add(itemModel);
        }
        if (hasCustomId) {
            addAuthoredNamesFor(sources, customItemId, obfuscationMappings);
            sources.add(customItemId);
        }
        if (hasModel) {
            int separator = itemModel.indexOf(':');
            String namespace = separator < 0 ? "minecraft" : itemModel.substring(0, separator);
            String path = separator < 0 ? itemModel : itemModel.substring(separator + 1);
            if (path.startsWith("item/") && path.length() > 5) {
                sources.add(namespace + ":" + path.substring(5));
            }
        }
        if (vanillaId != null && !vanillaId.isEmpty()) sources.add(vanillaId);
        return sources;
    }

    /** Every authored id the obfuscation mapping sends to clientId, also an available source. */
    private static void addAuthoredNamesFor(Set<String> sources, String clientId,
                                            Map<Key, Key> obfuscationMappings) {
        if (obfuscationMappings == null) return;
        for (var mapping : obfuscationMappings.entrySet()) {
            if (mapping.getValue().asString().equals(clientId)) {
                sources.add(mapping.getKey().asString());
            }
        }
    }

    private static String qualified(String id) {
        NamespacedKey key = NamespacedKey.fromString(id);
        return key == null ? null : key.toString();
    }

    /** Reads every items/ and models/ JSON under an assets folder into the two lookup maps. */
    static void readAssets(Path root, Map<String, JsonObject> items, Map<String, JsonObject> models) throws IOException {
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) return;
        try (var files = Files.walk(assets)) {
            for (Path file : files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json")).toList()) {
                Path relative = assets.relativize(file);
                if (relative.getNameCount() < 3) continue;
                String kind = relative.getName(1).toString();
                if (!kind.equals("items") && !kind.equals("models")) continue;
                String path = relative.subpath(2, relative.getNameCount()).toString().replace('\\', '/');
                String id = relative.getName(0) + ":" + path.substring(0, path.length() - 5);
                try (var reader = Files.newBufferedReader(file)) {
                    (kind.equals("items") ? items : models).put(id, JsonParser.parseReader(reader).getAsJsonObject());
                }
            }
        }
    }

    /** The generated model id a file under a generated/handheld folder corresponds to, or null. */
    static String generatedId(Path relative) {
        String path = relative.toString().replace('\\', '/');
        String marker = "/generated/handheld/";
        int markerIndex = path.indexOf(marker);
        if (markerIndex <= 0 || !path.endsWith(".json")) return null;
        int kindStart = path.lastIndexOf('/', markerIndex - 1) + 1;
        if (kindStart <= 0) return null;
        String kind = path.substring(kindStart, markerIndex);
        if (!kind.equals("items") && !kind.equals("models")) return null;
        return path.substring(0, kindStart - 1) + ":" + path.substring(markerIndex + 1, path.length() - 5);
    }
}
