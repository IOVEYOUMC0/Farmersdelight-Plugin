package com.huidu.farmersdelight.manager;

import com.google.gson.GsonBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.item.behavior.SkilletItemBehavior;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineModelMappings;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.item.behavior.CompositeItemBehavior;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.pack.AbstractPackManager;
import net.momirealms.craftengine.core.util.MinecraftVersion;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Generates configured food overlays during pack caching, never during player ticks. */
public final class HandheldCookingModels implements Listener {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final FarmersDelightPlugin plugin;
    private final Path folder;
    private final BooleanSupplier enabled;
    // Ingredients the cooking recipes accept. Only their item models need a generated composite, because the
    // handheld interaction resolves a recipe for the held ingredient before it ever asks for a model.
    private final Supplier<List<ItemStack>> cookingIngredients;
    private final AtomicBoolean clientModelFallbackWarned = new AtomicBoolean();
    private volatile Set<String> available = Set.of();

    HandheldCookingModels(FarmersDelightPlugin plugin, Path folder, BooleanSupplier enabled,
                          Supplier<List<ItemStack>> cookingIngredients) {
        this.plugin = plugin;
        this.folder = folder;
        this.enabled = enabled;
        this.cookingIngredients = cookingIngredients;
        if (!enabled.getAsBoolean()) return;
        try {
            Path assets = folder.resolve("assets");
            if (Files.isDirectory(assets)) {
                Set<String> saved = new HashSet<>();
                try (var files = Files.walk(assets)) {
                    files.filter(p -> Files.isRegularFile(p) && p.toString().replace('\\', '/').contains("/items/generated/handheld/")
                                    && p.toString().endsWith(".json"))
                            .forEach(p -> {
                                Path relative = assets.relativize(p);
                                String id = relative.toString().replace('\\', '/');
                                saved.add(id.substring(0, id.length() - 5).replaceFirst("/items/", ":"));
                            });
                }
                available = Set.copyOf(saved);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, I18n.formatConsole("skillet.models_failed", "error", e.getMessage()), e);
        }
    }

    NamespacedKey resolve(Player player, NamespacedKey cooking, NamespacedKey overlay, ItemStack ingredient) {
        if (!enabled.getAsBoolean() || cooking == null || overlay == null || available.isEmpty()) return cooking;
        var serverItem = BukkitAdaptor.adapt(ingredient.clone());
        Item clientItem;
        try {
            clientItem = serverItem.toClientSide(BukkitAdaptor.adapt(player));
        } catch (LinkageError error) {
            // CraftEngine 26.9.1 can expose an ItemManager compiled against a processor removed
            // from the runtime jar. The server-side item still contains the model components it needs.
            clientItem = serverItem;
            if (clientModelFallbackWarned.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING,
                        I18n.formatConsole("skillet.models_client_fallback", "error", error.getClass().getSimpleName()));
            }
        }
        Set<String> sources = HandheldCookingModelPack.sourceCandidates(clientItem.vanillaId().asString(),
                clientItem.itemModel().orElse(null), customItemId(ingredient), CraftEngineModelMappings.get());
        for (String source : sources) {
            NamespacedKey result = HandheldCookingModelPack.generatedKey(cooking, overlay, source);
            if (available.contains(result.toString())) return result;
        }
        return cooking;
    }

    /**
     * The CraftEngine item id of a stack, read from its server-side identity. This is the one model
     * source that survives item.client-bound-model: true, which strips the item-model
     * component from the stack before the server ever sees it, and it is also the key CraftEngine's
     * generated item definitions are stored under.
     */
    private static String customItemId(ItemStack item) {
        try {
            return ItemUtils.getCustomItemId(item);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public synchronized void onPackCache(AsyncResourcePackCacheEvent event) {
        if (!enabled.getAsBoolean()) return;
        try {
            Files.createDirectories(folder);
            Map<String, JsonObject> items = new HashMap<>();
            Map<String, JsonObject> models = new HashMap<>();
            AbstractPackManager.PRESET_ITEMS.forEach((key, value) ->
                    items.put(key.asString(), value.toJson(MinecraftVersion.V1_21_4)));
            HandheldCookingModelPack.addPresetModels(models, "item", AbstractPackManager.PRESET_MODERN_MODELS_ITEM);
            HandheldCookingModelPack.addPresetModels(models, "block", AbstractPackManager.PRESET_MODELS_BLOCK);
            for (var pack : BukkitCraftEngine.instance().packManager().loadedPacks()) {
                for (Path root : pack.resourcePackFolders()) HandheldCookingModelPack.readAssets(root, items, models);
            }
            for (Path root : event.cacheData().externalFolders()) {
                if (!root.equals(folder)) HandheldCookingModelPack.readAssets(root, items, models);
            }
            for (Path zip : event.cacheData().externalZips()) {
                try (var fs = FileSystems.newFileSystem(zip)) {
                    HandheldCookingModelPack.readAssets(fs.getPath("/"), items, models);
                }
            }
            var manager = BukkitItemManager.instance();
            manager.modelsToGenerate().forEach((key, value) -> models.putIfAbsent(key.asString(), value.get()));
            // CraftEngine's own definitions win over the vanilla presets they may replace: a vanilla
            // item whose model CraftEngine overrides must cook with the override, because that is the
            // model the client renders for it. The preset stays as the fallback for every id the
            // server does not define itself, so this only changes ids CraftEngine actually overrode.
            manager.modernItemModels1_21_4().forEach((key, value) ->
                    items.put(key.asString(), value.toJson(MinecraftVersion.V1_21_4)));
            Set<String> generated = new HashSet<>();
            Set<String> wantedSources = wantedSources();
            for (var definition : manager.loadedItems().values()) {
                var behavior = definition.behavior();
                SkilletItemBehavior handheld = behavior instanceof SkilletItemBehavior skillet ? skillet
                        : behavior instanceof CompositeItemBehavior composite ? composite.getFirst(SkilletItemBehavior.class) : null;
                if (handheld == null || handheld.cookingModel() == null || handheld.ingredientOverlayModel() == null) continue;
                NamespacedKey cooking = handheld.cookingModel();
                NamespacedKey overlay = handheld.ingredientOverlayModel();
                JsonObject base = items.get(cooking.toString());
                if (base == null || !base.has("model") || !models.containsKey(overlay.toString())) {
                    I18n.logWarning("skillet.model_missing", "item", definition.id(), "model", cooking, "overlay", overlay);
                    continue;
                }
                for (var source : items.entrySet()) {
                    if (!wantedSources.contains(source.getKey())) continue;
                    String texture = HandheldCookingModelPack.flatTexture(source.getValue(), models);
                    if (texture == null) continue;
                    NamespacedKey target = HandheldCookingModelPack.generatedKey(cooking, overlay, source.getKey());
                    if (!generated.add(target.toString())) continue;
                    write(target, "models", HandheldCookingModelPack.overlayModel(overlay.toString(), texture));
                    write(target, "items", HandheldCookingModelPack.composite(base, target.toString()));
                }
            }
            // CE can fire the first cache callback before item parsing. An empty loaded-item map is
            // therefore "not ready"; once definitions exist, an empty generated set is authoritative
            // and must clear both the lookup cache and stale generated files.
            if (!generated.isEmpty() || !manager.loadedItems().isEmpty()) {
                deleteStaleGeneratedFiles(generated);
                available = Set.copyOf(generated);
                I18n.logDetail("startup", "skillet.models_generated",
                        "count", generated.size(), "ingredients", wantedSources.size());
            }
            event.registerExternalResourcePack(folder);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, I18n.formatConsole("skillet.models_failed", "error", e.getMessage()), e);
        }
    }

    /**
     * The item-model ids the generated composites are named after, derived exactly the way
     * resolve derives its lookup candidates: for every ingredient the cooking recipes accept, its own
     * item model, its CraftEngine item id, that model without the item/ prefix, its vanilla material id,
     * and any authored id an obfuscation mapping points at. The CraftEngine item id is what keeps a
     * client-bound model reachable here: with item.client-bound-model: true the stack itself
     * carries no item model, so without that id a custom food would only offer its base material, which
     * is exactly the vanilla texture the CE model was supposed to replace. Sources no ingredient needs
     * are never written, so the pack only carries the foods that can actually be cooked in hand instead
     * of every item the server knows.
     */
    private Set<String> wantedSources() {
        List<ItemStack> ingredients = cookingIngredients.get();
        if (ingredients == null || ingredients.isEmpty()) {
            return Set.of();
        }
        Map<Key, Key> mappings = CraftEngineModelMappings.get();
        Set<String> sources = new HashSet<>();
        for (ItemStack ingredient : ingredients) {
            if (ingredient == null || ingredient.getType().isAir()) continue;
            String itemModel = null;
            try {
                itemModel = BukkitAdaptor.adapt(ingredient.clone()).itemModel().orElse(null);
            } catch (RuntimeException | LinkageError ignored) {
                // A stack whose item model cannot be read falls back to its vanilla id below.
            }
            sources.addAll(HandheldCookingModelPack.sourceCandidates(
                    ItemUtils.getVanillaMaterialItemId(ingredient), itemModel,
                    customItemId(ingredient), mappings));
        }
        return sources;
    }

    /** Removes generated models no longer produced by the current item/model set. */
    synchronized void cleanup() {        available = Set.of();
        if (!Files.exists(folder)) return;
        try (var files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            I18n.formatConsole("skillet.models_failed", "error", e.getMessage()), e);
                }
            });
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    I18n.formatConsole("skillet.models_failed", "error", e.getMessage()), e);
        }
    }

    private void write(NamespacedKey key, String type, JsonObject json) throws IOException {
        Path file = folder.resolve("assets").resolve(key.getNamespace()).resolve(type).resolve(key.getKey() + ".json");
        Files.createDirectories(file.getParent());
        String content = JSON.toJson(json);
        if (!Files.exists(file) || !Files.readString(file).equals(content)) Files.writeString(file, content);
    }

    private void deleteStaleGeneratedFiles(Set<String> generated) throws IOException {
        Path assets = folder.resolve("assets");
        if (!Files.isDirectory(assets)) return;
        try (var files = Files.walk(assets)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = assets.relativize(file);
                String id = HandheldCookingModelPack.generatedId(relative);
                if (id != null && !generated.contains(id)) Files.deleteIfExists(file);
            }
        }
    }
}
