package com.huidu.farmersdelight.api;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionHandler;
import com.huidu.farmersdelight.api.block.HeatSources;
import com.huidu.farmersdelight.api.advancement.FarmersDelightAdvancements;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.api.recipe.ChanceResult;
import com.huidu.farmersdelight.api.recipe.FarmersDelightRecipes;
import com.huidu.farmersdelight.api.recipe.JumpTarget;
import com.huidu.farmersdelight.api.recipe.RecipeFiller;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.api.item.FarmersDelightItems;
import com.huidu.farmersdelight.api.scheduler.ApiTask;
import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.recipe.SpecialRecipeLoader;
import com.huidu.farmersdelight.recipe.SpecialRecipeRegistry;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import com.huidu.farmersdelight.visual.ItemDisplayManager.DisplaySpec;
import com.huidu.farmersdelight.visual.ItemDisplayManager.TextDisplaySpec;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Color;
import org.bukkit.block.Block;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@ApiStatus.NonExtendable
public final class FarmersDelightApi {

    private static final int API_VERSION = 4;

    private static final Set<String> FEATURES = Set.of(
            // Runtime recipe registration + the generic recipe book / editor (registerRecipeType,
            // registerCookingPotRecipe, registerCuttingBoardRecipe, openRecipeBook, openRecipeEditor).
            "recipes",
            // Packet-only item displays (createItemDisplay / updateItemDisplay / removeItemDisplay).
            "item-displays",
            // Folia-safe scheduling helpers (runAtLocation / runLaterAtLocation / runRepeating).
            "scheduler",
            // Custom buff registry + the buff bossbar render channels.
            "buffs",
            // com.huidu.farmersdelight.api.block: station identification and read-only snapshots.
            "station-query",
            // FarmersDelightHarvestEvent for the Java-side harvest handlers.
            "harvest-event",
            // FarmersDelightCookStartEvent on the cooking pot's idle-to-cooking transition.
            "cook-start-event",
            // FarmersDelightBuffChangeEvent on real custom-buff level transitions.
            "buff-change-event",
            // ProfessionCookingExperienceEvent carries the station location.
            "cooking-experience-location",
            // Knife extra-drop rule registration (FarmersDelightKnifeDrops).
            "knife-drop-rules",
            // Runtime villager / wandering-trader trade registration (FarmersDelightVillagerTrades).
            "villager-trades",
            // Durability capability decoupled from the sword: the farmersdelight:durable item setting +
            // FarmersDelightItems.damage(...).
            "durable-items",
            // com.huidu.farmersdelight.api.util cross-version compatibility helpers.
            "compat-util",
            // Debug tool extension hooks for /fd debugtools.
            "debug-tools",
            // Programmatic special recipe registration (registerSpecialRecipe / unregisterSpecialRecipe /
            // specialRecipes) with per-recipe display types (recipe / item_description).
            "special-recipes",
            // CraftEngine content existence checks (FarmersDelightContent).
            "content-check",
            // Central tag registry: addons register their tag→item mappings here from their own config
            // so the whole family resolves the same tags (registerCommonTags / unregisterCommonTags).
            "common-tags",
            "advancement-triggers"
    );

    private static final FarmersDelightApi INSTANCE = new FarmersDelightApi();
    private static final Set<String> REPORTED_API_RECIPE_ITEMS = ConcurrentHashMap.newKeySet();

    private final Map<String, RecipeType> recipeTypes = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile Map<String, List<JumpTarget>> recipeResultIndex = Map.of();
    // Block namespaces of registered addons (e.g. "brewinandchewin"), used by the CraftEngine block-state
    // usage report and the shared land-protection listener.
    private final Set<String> addonBlockNamespaces = ConcurrentHashMap.newKeySet();
    private record AdvancementTrigger(String source, String tab, String advancement, String criterion) {}
    private final Map<String, List<AdvancementTrigger>> advancementTriggers = new ConcurrentHashMap<>();
    private final Map<String, List<AdvancementTrigger>> consumeAdvancementTriggers = new ConcurrentHashMap<>();

    private FarmersDelightApi() {
    }

    public static FarmersDelightApi get() {
        return INSTANCE;
    }

    public int apiVersion() {
        return API_VERSION;
    }

    /**
     * Every feature id this build answers true for. Useful for logging what an addon is running against,
     * and for keeping the published capability table honest (see ApiDocsDriftTest).
     */
    public Set<String> features() {
        return FEATURES;
    }

    public boolean hasFeature(String feature) {
        return feature != null && FEATURES.contains(feature.trim().toLowerCase(Locale.ROOT));
    }

    public void registerAddonBlockNamespace(String namespace) {
        if (namespace == null) {
            return;
        }
        String trimmed = namespace.trim().toLowerCase(Locale.ROOT);
        if (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.isEmpty()) {
            addonBlockNamespaces.add(trimmed);
        }
    }

    public Set<String> addonBlockNamespaces() {
        return Set.copyOf(addonBlockNamespaces);
    }

    /** Fast membership check for block event listeners; unlike addonBlockNamespaces(), this does not copy. */
    public boolean isAddonBlockNamespace(String namespace) {
        return namespace != null && addonBlockNamespaces.contains(namespace);
    }

    /**
     * Registers (or replaces) an addon's tag→item mapping into the family-wide tag registry. Members
     * are merged across sources, so multiple addons may contribute to the same tag. Call this at addon
     * enable with a mapping read from the addon's own config, and unregister on disable / reload.
     *
     *
     * Members in the minecraft: namespace are also exported as a server-side tag data pack.
     * That export runs again once the whole server has loaded, so registering during addon enable is in
     * time; CraftEngine absorbs the written tags on the next server start. Members in other namespaces
     * take effect immediately through FD's own matching and need no restart.
     * When content is already loaded, registration also rebuilds tag-dependent recipes and GUI caches.
     */
    public void registerCommonTags(String source, Map<String, List<String>> tagToMemberItems) {
        CommonTagResolver.registerSource(source, tagToMemberItems);
        refreshTagDependentRecipes();
    }

    /** Removes a previously registered addon tag source (idempotent). */
    public void unregisterCommonTags(String source) {
        CommonTagResolver.unregisterSource(source);
        refreshTagDependentRecipes();
    }

    private void refreshTagDependentRecipes() {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin != null && isContentLoaded()) {
            plugin.refreshTagDependentRecipes();
        }
    }

    public void registerRecipeType(RecipeType type) {
        if (type != null && type.id() != null) {
            recipeTypes.put(type.id(), type);
            if (isContentLoaded()) {
                rebuildRecipeResultIndex();
            }
            invalidateRecipeDiscoveryIndex();
        }
    }

    public void unregisterRecipeType(String typeId) {
        if (typeId != null) {
            recipeTypes.remove(typeId);
            if (isContentLoaded()) {
                rebuildRecipeResultIndex();
            } else {
                recipeResultIndex = Map.of();
            }
            invalidateRecipeDiscoveryIndex();
        }
    }

    /** Rebuilds linked-recipe results after a registered type replaces its recipe collection. */
    public void refreshRecipeType(String typeId) {
        if (typeId != null && recipeType(typeId) != null) {
            refreshRecipeIndex();
        }
    }

    /** Rebuilds all linked-recipe results after CraftEngine content or several recipe types reload. */
    public void refreshRecipeIndex() {
        rebuildRecipeResultIndex();
        invalidateRecipeDiscoveryIndex();
    }

    private synchronized void rebuildRecipeResultIndex() {
        recipeResultIndex = buildRecipeResultIndex(recipeTypes(),
                recipe -> FarmersDelightItems.idOf(recipe.result()));
    }

    static Map<String, List<JumpTarget>> buildRecipeResultIndex(
            List<RecipeType> types, Function<ViewableRecipe, String> itemIdResolver) {
        Map<String, List<JumpTarget>> next = new LinkedHashMap<>();
        for (RecipeType type : types) {
            for (ViewableRecipe recipe : type.recipes()) {
                if (recipe == null || recipe.id() == null) {
                    continue;
                }
                String itemId = itemIdResolver.apply(recipe);
                if (itemId != null) {
                    next.computeIfAbsent(itemId, ignored -> new ArrayList<>(1))
                            .add(new JumpTarget(type.id(), recipe.id()));
                }
            }
        }
        next.replaceAll((itemId, targets) -> List.copyOf(targets));
        return Collections.unmodifiableMap(next);
    }

    /** Returns all registered recipes producing the item without scanning recipe collections. */
    public List<JumpTarget> findRecipesProducing(ItemStack item) {
        String itemId = FarmersDelightItems.idOf(item);
        if (itemId == null) {
            return List.of();
        }
        LinkedHashSet<JumpTarget> targets =
                new LinkedHashSet<>(FarmersDelightRecipes.findRecipesProducing(item));
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin != null) {
            if (plugin.getSpecialRecipeRegistry() != null) {
                String specialId = plugin.getSpecialRecipeRegistry().findProducingRecipe(item);
                if (specialId != null) {
                    targets.add(new JumpTarget(
                            SpecialRecipeRegistry.TYPE_ID, specialId));
                }
            }
        }
        targets.addAll(recipeResultIndex.getOrDefault(itemId, List.of()));
        return List.copyOf(targets);
    }

    private void invalidateRecipeDiscoveryIndex() {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin != null && plugin.getRecipeDiscoveryManager() != null) {
            plugin.getRecipeDiscoveryManager().invalidateIndex();
        }
    }

    // Shared rule for the runtime-mutating register/unregister methods below: get the plugin and let it pass
    // through only if it is available. Returning null makes the caller's null-guard double as the
    // availability check, so the availability test is not repeated in every method.
    private static FarmersDelightPlugin availablePlugin() {
        return PluginAccess.pluginOrNull();
    }

    public void registerCookingPotRecipe(String id, List<String> ingredients, ItemStack container,
                                         ItemStack result, double experience, int cookTime, String category) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin == null) {
            return;
        }
        if (id == null || id.isBlank() || ingredients == null) {
            I18n.logWarning("plugin.recipe_api_registration_failed", "id", String.valueOf(id), "type", "cooking pot",
                    "error", "recipe id and ingredients are required");
            return;
        }
        if (result == null || result.getType().isAir()) {
            if (isContentLoaded()) {
                I18n.logWarning("plugin.recipe_api_registration_failed", "id", id, "type", "cooking pot",
                        "error", "result item is null or air");
            }
            return;
        }
        if (isContentLoaded()) {
            validateApiIngredients(id, ingredients);
        }
        try {
            plugin.getCookingPotRecipes().registerExternalRecipe(id, ingredients,
                    container == null ? null : container.clone(), result.clone(),
                    (float) experience, cookTime, category);
        } catch (IllegalArgumentException e) {
            if (isContentLoaded()) {
                I18n.logWarning("plugin.recipe_api_registration_failed", "id", id, "type", "cooking pot",
                        "error", e.getMessage());
            }
        }
    }

    public void unregisterCookingPotRecipe(String id) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && id != null) {
            plugin.getCookingPotRecipes().unregisterExternalRecipe(id);
        }
    }

    /**
     * Registers a cutting board recipe whose results are all guaranteed.
     *
     * @deprecated every result is registered at chance 1.0. A recipe with any result that is not
     *     guaranteed must use registerCuttingBoardRecipeWithChances, which is what the
     *     configuration loader and FarmersDelight's own recipes use. This overload exists so callers
     *     written against it keep working.
     */
    @Deprecated
    public void registerCuttingBoardRecipe(String id, String input, String tool,
                                           List<ItemStack> results, String sound) {
        if (results == null) {
            registerCuttingBoardRecipeWithChances(id, input, tool, null, sound);
            return;
        }
        List<ChanceResult> guaranteed = new ArrayList<>(results.size());
        for (ItemStack result : results) {
            if (result != null) {
                guaranteed.add(new ChanceResult(result, 1.0f));
            }
        }
        registerCuttingBoardRecipeWithChances(id, input, tool, guaranteed, sound);
    }

    /**
     * Registers cutting-board results with individual drop chances.
     * A chance of 1.0 always drops the result; 0.5 gives it a 50% chance.
     */
    public void registerCuttingBoardRecipeWithChances(String id, String input, String tool,
                                                      List<ChanceResult> results, String sound) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin == null) {
            return;
        }
        if (id == null || id.isBlank() || input == null || tool == null || results == null) {
            I18n.logWarning("plugin.recipe_api_registration_failed", "id", String.valueOf(id), "type", "cutting board",
                    "error", "recipe id, input, tool and results are required");
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        List<Double> chances = new ArrayList<>();
        for (ChanceResult result : results) {
            if (result != null && result.item() != null) {
                items.add(result.item());
                chances.add((double) result.chance());
            }
        }
        try {
            plugin.getCuttingBoardRecipes().registerExternalRecipe(id, input, tool, items, chances, sound);
        } catch (IllegalArgumentException e) {
            if (isContentLoaded()) {
                I18n.logWarning("plugin.recipe_api_registration_failed", "id", id, "type", "cutting board",
                        "error", e.getMessage());
            }
        }
    }

    public void unregisterCuttingBoardRecipe(String id) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && id != null) {
            plugin.getCuttingBoardRecipes().unregisterExternalRecipe(id);
        }
    }

    /**
     * Register a special recipe (composting, sunlight/water conditions, catalysts...). The recipe's
     * displayType selects how it is shown: SpecialRecipeInfo#DISPLAY_RECIPE keeps the
     * input → output → condition layout, SpecialRecipeInfo#DISPLAY_ITEM_DESCRIPTION renders a
     * description-only info page.
     */
    public void registerSpecialRecipe(SpecialRecipeInfo info) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && info != null) {
            plugin.getSpecialRecipeRegistry().register(info);
        }
    }

    /**
     * Config-driven registration: parses one special-recipe entry from a YAML section and registers it.
     * Same format as FarmersDelight's own special_recipes.yml; lets addons drive their special
     * recipes from a released config file like their other recipes. Throws on a malformed section so the
     * addon loader can fail the specific entry and keep going (matching FD's per-entry isolation).
     */
    public void registerSpecialRecipeFromSection(String id, ConfigurationSection section) {
        if (id == null || section == null) {
            return;
        }
        try {
            SpecialRecipeInfo info = SpecialRecipeLoader.parseRecipe(id, section);
            if (isContentLoaded()) {
                validateSpecialRecipeItems(id, info);
            }
            registerSpecialRecipe(info);
        } catch (IllegalArgumentException e) {
            I18n.logWarning("plugin.recipe_api_registration_failed", "id", id, "type", "special",
                    "error", e.getMessage());
        }
    }

    private void validateSpecialRecipeItems(String recipeId,
                                             SpecialRecipeInfo info) {
        if (info == null) {
            return;
        }
        validateSpecialItem(recipeId, "icon", info.iconItemId());
        for (int i = 0; i < info.inputSlots().size(); i++) {
            validateSpecialItem(recipeId, "inputs[" + i + "]", info.inputSlots().get(i).itemId());
        }
        for (int i = 0; i < info.outputSlots().size(); i++) {
            validateSpecialItem(recipeId, "outputs[" + i + "]", info.outputSlots().get(i).itemId());
        }
        for (int i = 0; i < info.catalystSlots().size(); i++) {
            validateSpecialItem(recipeId, "catalysts[" + i + "]", info.catalystSlots().get(i).itemId());
        }
    }

    private void validateApiIngredients(String recipeId, List<String> ingredients) {
        for (int i = 0; i < ingredients.size(); i++) {
            String spec = ingredients.get(i);
            if (spec == null || spec.isBlank()) {
                continue;
            }
            boolean resolved = false;
            for (String alternative : spec.split("\\|")) {
                String token = alternative.trim();
                int comma = token.indexOf(',');
                if (comma >= 0) {
                    token = token.substring(0, comma).trim();
                }
                if (token.startsWith("#")) {
                    resolved |= !ItemUtils.createSlotItems(token).isEmpty();
                } else {
                    resolved |= ItemUtils.createItem(token) != null;
                }
            }
            String reportKey = recipeId + ".ingredients[" + i + "]=" + spec;
            if (!resolved && REPORTED_API_RECIPE_ITEMS.add(reportKey)) {
                I18n.logWarning("plugin.item_not_found", "path", "API recipe " + recipeId
                        + ".ingredients[" + i + "]", "id", spec);
            }
        }
    }

    private void validateSpecialItem(String recipeId, String path, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        boolean resolved = itemId.startsWith("#")
                ? !ItemUtils.createSlotItems(itemId).isEmpty()
                : ItemUtils.createItem(itemId) != null;
        String reportKey = recipeId + "." + path + "=" + itemId;
        if (!resolved && REPORTED_API_RECIPE_ITEMS.add(reportKey)) {
            I18n.logWarning("plugin.item_not_found", "path", "special recipe " + recipeId + "." + path,
                    "id", itemId);
        }
    }

    public void unregisterSpecialRecipe(String id) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && id != null) {
            plugin.getSpecialRecipeRegistry().unregister(id);
        }
    }

    public List<SpecialRecipeInfo> specialRecipes() {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin == null) {
            return List.of();
        }
        return plugin.getSpecialRecipeRegistry().getAll();
    }

    /** Add a right-click handler for FarmersDelight cutting boards; first to consume wins. */
    public void registerCuttingBoardInteractionHandler(CuttingBoardInteractionHandler handler) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && handler != null) {
            plugin.registerCuttingBoardInteractionHandler(handler);
        }
    }

    public void unregisterCuttingBoardInteractionHandler(CuttingBoardInteractionHandler handler) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && handler != null) {
            plugin.unregisterCuttingBoardInteractionHandler(handler);
        }
    }

    public List<RecipeType> recipeTypes() {
        synchronized (recipeTypes) {
            return new ArrayList<>(recipeTypes.values());
        }
    }

    public RecipeType recipeType(String typeId) {
        return typeId == null ? null : recipeTypes.get(typeId);
    }

    public void openRecipeBook(Player player) {
        openRecipeBook(player, null);
    }

    public void openRecipeBook(Player player, RecipeFiller filler) {
        if (player != null) {
            RecipeBookGui.openMenu(player, filler);
        }
    }

    public void openRecipeBook(Player player, String typeId, RecipeFiller filler) {
        if (player == null) {
            return;
        }
        RecipeType type = recipeType(typeId);
        if (type == null) {
            RecipeBookGui.openMenu(player, filler);
        } else {
            RecipeBookGui.openType(player, type, filler);
        }
    }

    public void openRecipeEditor(Player player, String typeId, String recipeId) {
        RecipeType type = recipeType(typeId);
        if (player != null && type != null && type.editor() != null) {
            RecipeBookGui.openEditor(player, type, recipeId);
        }
    }

    public boolean isAvailable() {
        return PluginAccess.isAvailable();
    }

    /**
     * Whether CraftEngine has finished its deferred item-load pass, so recipe results and advancement
     * icons resolve to real custom items. False during an addon's own onEnable on a normal server start
     * (CE items load later, announced by FarmersDelightWarmupEvent); an addon that registers
     * content both eagerly at enable and again on warmup should gate the eager path on this to avoid
     * registering — and logging — twice.
     */
    public boolean isContentLoaded() {
        return ItemUtils.isAnyCustomItemLoaded();
    }

    public boolean isFolia() {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        return plugin != null && plugin.scheduler().isFolia();
    }

    public String resolveTranslations(String text, Player player) {
        return ItemUtils.resolveTranslationTags(text, player);
    }

    public static String consoleMessage(String key, Object... args) {
        return I18n.formatConsole(key, args);
    }

    public static boolean isDebugEnabled(String category) {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        return plugin != null && plugin.isDebugEnabled(category);
    }

    /**
     * The server's primary (overworld) world, resolved from server.properties level-name. Registry
     * data packs (tags, damage types, enchantments, advancements) are server-global and loaded only
     * from this world's datapacks folder, so addons installing their own registry data packs should
     * target exactly this world instead of copying the pack into every world.
     */
    public World primaryWorld() {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        return plugin != null ? plugin.getPrimaryWorld() : null;
    }

    public boolean isHeatSource(Block block) {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        return plugin != null && block != null && plugin.getHeatSourceConfig().isHeatSource(block);
    }

    public boolean isConductor(Block block) {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        return plugin != null && block != null && plugin.getHeatSourceConfig().isConductor(block);
    }

    // FarmersDelight rebuilds the whole heat-source table on every config reload. Registrations made
    // through the API are remembered here so the rebuild replays them, the same way registerCommonTags
    // survives a reload. Without this an addon that registers in onEnable (the natural place) would
    // silently lose its heat source on the first /fd reload, with no log line.
    // Heat-source declarations live in api.block.HeatSources, which remembers them per plugin and
    // replays them whenever the table is rebuilt. The methods below are the older flat spelling; each
    // one writes into the same shared record, so mixing the two styles is safe.

    private static HeatSources legacyHeatSources() {
        return HeatSources.legacy();
    }

    /** @deprecated use HeatSources.of(plugin).addVanillaBlock(id). */
    @Deprecated
    public void registerHeatSource(String vanillaBlockId) {
        legacyHeatSources().addVanillaBlock(vanillaBlockId);
    }

    /** @deprecated use HeatSources.of(plugin).addConductor(id). */
    @Deprecated
    public void registerConductor(String vanillaBlockId) {
        legacyHeatSources().addConductor(vanillaBlockId);
    }

    /** @deprecated use HeatSources.of(plugin).remove(id). */
    @Deprecated
    public void unregisterHeatSource(String vanillaBlockId) {
        legacyHeatSources().remove(vanillaBlockId);
    }

    /** @deprecated use HeatSources.of(plugin).remove(id). */
    @Deprecated
    public void unregisterConductor(String vanillaBlockId) {
        legacyHeatSources().remove(vanillaBlockId);
    }

    /** @deprecated use HeatSources.of(plugin).remove(id). */
    @Deprecated
    public void unregisterCustomHeatSource(String blockIdWithOptionalState) {
        legacyHeatSources().remove(blockIdWithOptionalState);
    }

    /** @deprecated use HeatSources.of(plugin).remove(tagId). */
    @Deprecated
    public void unregisterCustomHeatSourceTag(String tagId) {
        legacyHeatSources().remove(tagId);
    }

    /** @deprecated use HeatSources.of(plugin).addCustomBlock(id). */
    @Deprecated
    public boolean registerCustomHeatSource(String blockIdWithOptionalState) {
        return legacyHeatSources().addCustomBlock(blockIdWithOptionalState);
    }

    /** @deprecated use HeatSources.of(plugin).addCustomTag(tagId). */
    @Deprecated
    public void registerCustomHeatSourceTag(String tagId) {
        legacyHeatSources().addCustomTag(tagId);
    }

    /**
     * Replays every addon heat-source registration into a freshly built table. Called by FarmersDelight
     * right after it reloads its own heat-source config; addons never call this.
     */
    @ApiStatus.Internal
    public void replayAddonHeatSources(HeatSourceConfig config) {
        HeatSources.replayAll(config);
    }

    /** Registers an obtain/craft/produce trigger for an advancement. Repeated registration replaces the same entry. */
    public void registerAdvancementItemTrigger(String source, String itemId, String tabId, String advancementId) {
        registerAdvancementTrigger(advancementTriggers, source, itemId, tabId, advancementId, null);
    }

    public void registerAdvancementItemCriterionTrigger(String source, String itemId, String tabId,
                                                        String advancementId, String criterion) {
        registerAdvancementTrigger(advancementTriggers, source, itemId, tabId, advancementId, criterion);
    }

    /** Registers a consume trigger for an advancement. */
    public void registerAdvancementConsumeTrigger(String source, String itemId, String tabId, String advancementId) {
        registerAdvancementTrigger(consumeAdvancementTriggers, source, itemId, tabId, advancementId, null);
    }

    public void registerAdvancementConsumeCriterionTrigger(String source, String itemId, String tabId,
                                                           String advancementId, String criterion) {
        registerAdvancementTrigger(consumeAdvancementTriggers, source, itemId, tabId, advancementId, criterion);
    }

    public void clearAdvancementTriggers(String source) {
        if (source == null) return;
        clearAdvancementTriggerMap(advancementTriggers, source);
        clearAdvancementTriggerMap(consumeAdvancementTriggers, source);
    }

    private static void clearAdvancementTriggerMap(Map<String, List<AdvancementTrigger>> triggers, String source) {
        // Registered lists are immutable snapshots (List.copyOf), so mutate the map entries instead.
        triggers.entrySet().removeIf(entry -> {
            List<AdvancementTrigger> remaining = entry.getValue().stream()
                    .filter(trigger -> !source.equals(trigger.source()))
                    .toList();
            if (remaining.isEmpty()) {
                return true;
            }
            if (remaining.size() != entry.getValue().size()) {
                entry.setValue(List.copyOf(remaining));
            }
            return false;
        });
    }

    public void awardItemAdvancements(Player player, String itemId) {
        awardItemAdvancements(player, itemId, advancementTriggers);
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin != null) plugin.getAddonAdvancementRegistry().awardForItem(player, itemId);
    }

    public void awardConsumedItemAdvancements(Player player, String itemId) {
        awardItemAdvancements(player, itemId, consumeAdvancementTriggers);
    }

    private static void registerAdvancementTrigger(Map<String, List<AdvancementTrigger>> target, String source,
                                                   String itemId, String tabId, String advancementId,
                                                   String criterion) {
        if (source == null || itemId == null || tabId == null || advancementId == null) return;
        target.compute(itemId, (ignored, current) -> {
            List<AdvancementTrigger> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
            next.removeIf(t -> source.equals(t.source()) && t.tab().equals(tabId)
                    && t.advancement().equals(advancementId) && Objects.equals(t.criterion(), criterion));
            next.add(new AdvancementTrigger(source, tabId, advancementId, criterion));
            return List.copyOf(next);
        });
    }

    private static void awardItemAdvancements(Player player, String itemId,
                                              Map<String, List<AdvancementTrigger>> triggers) {
        if (player == null || itemId == null) return;
        for (AdvancementTrigger trigger : triggers.getOrDefault(itemId, List.of())) {
            if (trigger.criterion() == null) {
                FarmersDelightAdvancements.award(trigger.tab(), player, trigger.advancement());
            } else {
                FarmersDelightAdvancements.awardCriteria(trigger.tab(), player, trigger.advancement(), trigger.criterion());
            }
        }
    }

    // Shared gate + manager lookup for the packet display/text methods below. Returns null when the plugin
    // is not available so each caller's single null-check doubles as the availability guard.
    private ItemDisplayManager displayManager() {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        return plugin == null ? null : plugin.getItemDisplayManager();
    }

    // Packet item displays
    // Server-side, packet-only ItemDisplay proxies (no real entity is spawned): FarmersDelight tracks them,
    // syncs them to nearby players (join / chunk-load / teleport) and cleans them up on world unload. Use
    // these instead of world.spawn(ItemDisplay) so an addon's decoration displays don't persist to disk,
    // never become orphans, and share FarmersDelight's Folia-safe visibility handling. The returned int is a
    // handle for updateItemDisplay / removeItemDisplay; a return of -1 means the display was not created.

    public int createItemDisplay(Location location, ItemStack item,
                                 ItemDisplayTransform itemTransform,
                                 Transformation transformation) {
        ItemDisplayManager manager = displayManager();
        if (manager == null || location == null || item == null) {
            return -1;
        }
        return manager.createDisplay(new DisplaySpec(
                location, item, itemTransform, transformation));
    }

    public boolean updateItemDisplay(int handle, Location location, ItemStack item,
                                     ItemDisplayTransform itemTransform,
                                     Transformation transformation) {
        return updateItemDisplay(handle, location, item, itemTransform, transformation, 0);
    }

    /**
     * As updateItemDisplay, but animate the transform change: the client smoothly interpolates from the
     * display's current transform to the given one over interpolationDurationTicks ticks (0 = snap, the
     * default overload). Use this for animated station displays such as flipping a skewer on the grill.
     */
    public boolean updateItemDisplay(int handle, Location location, ItemStack item,
                                     ItemDisplayTransform itemTransform,
                                     Transformation transformation, int interpolationDurationTicks) {
        ItemDisplayManager manager = displayManager();
        if (manager == null || location == null || item == null) {
            return false;
        }
        return manager.updateDisplay(handle, new DisplaySpec(
                location, item, itemTransform, transformation, Math.max(0, interpolationDurationTicks), 0));
    }

    public void removeItemDisplay(int handle) {
        ItemDisplayManager manager = displayManager();
        if (manager != null) {
            manager.destroyDisplay(handle);
        }
    }

    /**
     * Whether a handle previously returned by createItemDisplay still refers to a managed display.
     * FD removes displays on chunk unload / world unload / /fd cleanup without notifying the owner,
     * so callers that cache handles should probe this periodically and recreate the display when it
     * turns false. Pure map lookup, no packets, safe to call from a region thread.
     */
    public boolean isItemDisplayActive(int handle) {
        ItemDisplayManager manager = displayManager();
        return manager != null && manager.isActive(handle);
    }

    // Packet text displays
    // Same packet-only lifecycle as the item displays above, but renders text (TextDisplay). Handles
    // returned here are only valid for the text-* methods below.

    public int createTextDisplay(Location location, Component text,
                                 Transformation transformation,
                                 Color backgroundColor, boolean shadowed, boolean seeThrough) {
        ItemDisplayManager manager = displayManager();
        if (manager == null || location == null || text == null) {
            return -1;
        }
        return manager.createTextDisplay(new TextDisplaySpec(
                location, text, transformation, backgroundColor, shadowed, seeThrough));
    }

    public boolean updateTextDisplay(int handle, Component text) {
        ItemDisplayManager manager = displayManager();
        return manager != null && text != null && manager.updateText(handle, text);
    }

    public void removeTextDisplay(int handle) {
        ItemDisplayManager manager = displayManager();
        if (manager != null) {
            manager.destroyDisplay(handle);
        }
    }

    public boolean isTextDisplayActive(int handle) {
        ItemDisplayManager manager = displayManager();
        return manager != null && manager.isActive(handle);
    }

    public void runAtLocation(Location location, Runnable task) {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin == null || task == null) {
            return;
        }
        plugin.scheduler().runAt(location, task);
    }

    public void runLaterAtLocation(Location location, Runnable task, long delayTicks) {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin == null || task == null) {
            return;
        }
        plugin.scheduler().runLaterAt(location, task, delayTicks);
    }

    public ApiTask runRepeating(Runnable task, long delayTicks, long periodTicks) {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin == null || task == null) {
            return ApiTask.NOOP;
        }
        PluginTask pluginTask = plugin.scheduler().runRepeating(task, delayTicks, periodTicks);
        return new ApiTask() {
            @Override
            public void cancel() {
                pluginTask.cancel();
            }

            @Override
            public boolean isCancelled() {
                return pluginTask.isCancelled();
            }
        };
    }

    public void awardCraftingExperience(Player player, Location location, ItemStack result,
                                        double baseExperience, String source) {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin == null || player == null) {
            return;
        }
        ItemStack resultCopy = result == null ? null : result.clone();
        Location locationCopy = location == null ? null : location.clone();
        if (baseExperience > 0.0D && plugin.shouldDropCookingPotVanillaExperience()
                && locationCopy != null && locationCopy.getWorld() != null) {
            plugin.scheduler().runAt(locationCopy, () ->
                    dropExperienceOrbs(locationCopy.getWorld(), locationCopy, baseExperience));
        }
        // The player may be in a different Folia region than the crafting station, or teleport before
        // execution. Keep all player-owned integrations and the player event on the entity scheduler.
        plugin.scheduler().runForEntity(player, () -> {
            plugin.awardCookingPotAuraSkillsExperience(player, baseExperience);
            Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                    player.getUniqueId(), player.getName(), source, resultCopy, (float) baseExperience,
                    locationCopy));
        });
    }

    private static void dropExperienceOrbs(World world, Location location, double totalExp) {
        // Probabilistic rounding so sub-1.0 exp isn't floored away; expected total holds over many takes.
        int expValue = (int) Math.floor(totalExp);
        double fraction = totalExp - expValue;
        if (fraction > 0.0D && ThreadLocalRandom.current().nextDouble() < fraction) {
            expValue += 1;
        }
        if (expValue > 0) {
            int amount = expValue;
            world.spawn(location, ExperienceOrb.class, orb -> orb.setExperience(amount));
        }
    }
}
