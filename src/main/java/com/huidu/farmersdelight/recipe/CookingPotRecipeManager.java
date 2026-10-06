package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.MMOItemsCompat;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.util.CommonTagResolver;

public class CookingPotRecipeManager {

    private final FarmersDelightPlugin plugin;
    // These lookup structures are rebuilt on /fd reload. They are published as whole, freshly built,
    // post-publish-immutable maps via a single volatile write, so concurrent readers (cooking-pot
    // tick / GUI, which run on Folia region threads while reload runs on the global thread) never
    // observe a half-cleared map. Never mutate them in place after publishing.
    private volatile Map<String, CookingPotRecipe> recipes = Map.of();
    private volatile Map<String, Map<String, CookingPotRecipe>> customRecipes = Map.of();
    private volatile Map<String, Set<String>> ingredientToRecipes = Map.of();
    private volatile Map<String, Map<String, Set<String>>> customIngredientToRecipes = Map.of();
    private volatile List<CookingPotRecipe> sortedRecipes = List.of();
    private volatile Map<String, List<CookingPotRecipe>> sortedCustomRecipes = Map.of();
    private volatile Map<String, List<CookingPotRecipe>> sortedCustomOnlyRecipes = Map.of();
    // Reverse index result item id -> producing recipes, built at load time and published as a whole
    // (single volatile write). Lets API cross-reference / GUI "which recipes produce X" answer in O(1)
    // instead of scanning every recipe. Keyed the same way as getItemKey (custom id, else vanilla id).
    private volatile Map<String, List<CookingPotRecipe>> resultToRecipes = Map.of();
    // Recipes whose winning definition came from a CraftEngine pack section; published with the maps above
    // so the startup summary can tell the plugin's own file, pack content and runtime registrations apart.
    private volatile int packRecipeCount;
    // Published with the maps above: true when at least one loaded recipe matches on an ingredient's item data
    // (a RecipeIngredient.Item carrying an nbt snapshot). Matching then depends on more than the item id, so
    // buildCacheKey has to carry the inputs' own data projection; while no loaded recipe constrains it, the key
    // stays the id + clamped amount it has always been.
    private volatile boolean anyIngredientConstrainsNbt;
    private final VanillaTagItemIdCache vanillaItemIdsByTagCache;
    // LRU access-order LinkedHashMap mutates internal state on get(), so concurrent reads from
    // multiple region threads (Folia) would corrupt the doubly-linked list. Wrap in synchronizedMap;
    // callers MUST synchronize externally when iterating (currently no iteration happens).
    private final Map<String, CookingPotRecipe> recipeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CookingPotRecipe> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });
    // Both caches are server-wide, so 100 entries thrash as soon as a few dozen pots hold distinct
    // input combinations: every eviction turns the next tick of that pot back into a full recipe
    // scan, which is exactly what the negative cache exists to avoid. Entries are a string key and
    // a reference, so a four-figure bound is a few hundred kB at worst.
    private static final int MAX_CACHE_SIZE = 2048;
    // Negative-result cache: input+container multisets known to match nothing, so an unchanged incomplete
    // pot (mid-fill, hopper-fed, or junk) does not re-scan every recipe each tick. Bounded LRU like
    // recipeCache, only touched under the recipeCache monitor, cleared + generation-bumped alongside it.
    private final Set<String> recipeMisses = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(MAX_CACHE_SIZE + 1, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });
    // Bumped inside the same synchronized(recipeCache) block that clears the cache on every (re)publish.
    // matchRecipe snapshots it before reading the volatile maps and only stores a computed match if it is
    // still current, so a match computed against pre-reload maps can't repopulate the just-cleared cache.
    // volatile so the unsynchronized snapshot read is ordered before the volatile map reads and is visible.
    private volatile long recipeGeneration = 0;

    /**
     * The publish generation of the currently loaded recipe set, bumped on every republish. A caller that
     * caches a match result stores this alongside it so the cache is discarded when recipes change.
     */
    public long recipeGeneration() {
        return recipeGeneration;
    }
    private volatile Set<String> validContainerKeys = Set.of();
    // Recipes registered at runtime by addons via the public API. Kept separate so they survive a
    // /fd reload (which rebuilds the file-backed maps); merged into the published maps in loadRecipes().
    private final Map<String, CookingPotRecipe> externalRecipes = new ConcurrentHashMap<>();
    // Republishing after an external (un)register is coalesced to the next tick, so registering a batch
    // of addon recipes triggers a single loadRecipes() instead of one full file reload per recipe.
    private volatile boolean externalRepublishScheduled = false;

    public CookingPotRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.vanillaItemIdsByTagCache = new VanillaTagItemIdCache(plugin);
    }

    public void loadRecipes() {
        // Recompile the packs' advanced tag groups up front: recipe parsing resolves an advtag: ingredient
        // against this snapshot, and every load path (startup, /fd reload, external republish) runs through
        // here, so a recipe can never be read against the groups of an earlier load.
        RecipeParsingSupport.setAdvancedTagGroups(plugin.advancedTagGroups());
        YamlConfiguration config = RecipeFileLoader.loadRecipeFile(plugin, "recipes/cooking_pot_recipes.yml");
        if (config == null) {
            // Unreadable file (the loader already warned with the parse error): keep the recipes published
            // last instead of rebuilding from an empty file. Pack and API recipes stay as they are too.
            return;
        }

        // Every source of this reload is queued into one registration round, and the round is what publishes:
        // entries reach the buffers only as it advances, so the derived indexes and the published maps are
        // built after the last entry rather than from the first slice of the first file.
        PendingLoad pending = new PendingLoad(config);
        List<RecipeRegistrationRound.Segment> segments = new ArrayList<>();
        RecipeRegistrationRound.Segment ownFile = RecipeFileLoader.recipeSectionSegment(plugin, config,
                "cooking_pot_recipes", "cooking pot", "recipes/cooking_pot_recipes.yml", pending::putOwnFile);
        if (ownFile != null) {
            segments.add(ownFile);
        }
        pending.loadCustom(config);

        // Recipes a CraftEngine pack declares under cooking_recipes. Queued after the plugin's own file so a
        // pack can never silently replace a built-in recipe; the runtime registrations are merged in the publish
        // below, so an explicit registration still wins on an id clash. CraftEngine read the files; see
        // PackSections.
        for (PackSections.Section packSection : plugin.packSectionsOf(PackSection.COOKING_POT)) {
            RecipeRegistrationRound.Segment pack = RecipeFileLoader.recipeSectionSegment(plugin,
                    packSection.yaml(), PackSection.COOKING_POT.rootKey(),
                    "cooking pot [" + packSection.source() + "]",
                    packSection.source(),
                    (recipeId, section) -> pending.putPackFile(recipeId, section, packSection.source()));
            if (pack != null) {
                segments.add(pack);
            }
            pending.loadCustom(packSection.yaml());
        }

        plugin.recipeRegistrations().start(this, segments, plugin.recipeRegistrationBudget(),
                () -> publishLoadedSet(pending));
    }

    /**
     * The buffers one reload round fills before anything is published. A replaced round is dropped together
     * with its buffers, so a superseded reload can never publish a half-registered set over the live one.
     */
    private final class PendingLoad {

        private final Map<String, CookingPotRecipe> recipes = new LinkedHashMap<>();
        private final Map<String, Map<String, CookingPotRecipe>> customRecipes = new HashMap<>();
        private final Map<String, Set<String>> ingredientToRecipes = new HashMap<>();
        private final Map<String, Map<String, Set<String>>> customIngredientToRecipes = new HashMap<>();
        private final Set<String> validContainerKeys = new HashSet<>();
        // Ids whose winning definition came from a CraftEngine pack section; the registry buckets in the
        // startup summary read this, so it may only count entries that survived the merges in publish.
        private final Set<String> packIds = new HashSet<>();
        // Recipes that got their container from their own result instead of the file; reported by publish so an
        // operator can see which entries rely on the inference.
        private final int[] inferredContainers = {0};
        private final Set<String> overriddenExternalIds;

        private PendingLoad(YamlConfiguration config) {
            this.overriddenExternalIds = externalOverrideIds(config, "cooking_pot");
        }

        private void putOwnFile(String recipeId, ConfigurationSection section) {
            CookingPotRecipe recipe = parseRecipe(recipeId, section, 6);
            recipes.put(recipeId, recipe);
            indexDefaultRecipe(ingredientToRecipes, recipeId, recipe);
            indexContainer(validContainerKeys, recipe);
            if (section.get("container") == null && recipe.getContainer() != null) {
                inferredContainers[0]++;
            }
        }

        private void putPackFile(String recipeId, ConfigurationSection section, String source) {
            if (recipes.containsKey(recipeId)) {
                I18n.logWarning("recipe.pack_duplicate_skipped", "id", recipeId, "source", source);
                return;
            }
            CookingPotRecipe recipe = parseRecipe(recipeId, section, 6);
            recipes.put(recipeId, recipe);
            packIds.add(recipeId);
            indexDefaultRecipe(ingredientToRecipes, recipeId, recipe);
            indexContainer(validContainerKeys, recipe);
            if (section.get("container") == null && recipe.getContainer() != null) {
                inferredContainers[0]++;
            }
        }

        private void loadCustom(YamlConfiguration yaml) {
            loadCustomRecipes(yaml, customRecipes, customIngredientToRecipes, validContainerKeys, inferredContainers);
        }
    }

    /**
     * Builds the derived indexes from a finished round and publishes them together as immutable snapshots, so
     * a reader on any Folia region thread sees either the previous set or the complete new one.
     */
    private void publishLoadedSet(PendingLoad pending) {
        Map<String, CookingPotRecipe> newRecipes = pending.recipes;
        Map<String, Map<String, CookingPotRecipe>> newCustomRecipes = pending.customRecipes;
        Map<String, Set<String>> newIngredientToRecipes = pending.ingredientToRecipes;
        Map<String, Map<String, Set<String>>> newCustomIngredientToRecipes = pending.customIngredientToRecipes;
        Set<String> newValidContainerKeys = pending.validContainerKeys;
        Set<String> packIds = pending.packIds;
        int[] inferredContainers = pending.inferredContainers;
        // Merge addon-registered recipes last so they survive reloads; an editor override is explicit and wins.
        for (CookingPotRecipe recipe : externalRecipes.values()) {
            if (!pending.overriddenExternalIds.contains(recipe.getId()) || !newRecipes.containsKey(recipe.getId())
                    || AddonRecipeFiles.ownerOf("cooking_pot", recipe.getId()) != null) {
                newRecipes.put(recipe.getId(), recipe);
                packIds.remove(recipe.getId());
                indexDefaultRecipe(newIngredientToRecipes, recipe.getId(), recipe);
                indexContainer(newValidContainerKeys, recipe);
            }
        }
        int newPackRecipeCount = packIds.size();
        // Derived from the set being published, so a republish is the only thing that can change it.
        boolean newAnyIngredientConstrainsNbt = anyRecipeConstrainsNbt(newRecipes, newCustomRecipes);

        List<CookingPotRecipe> newSortedRecipes = sortedRecipeList(newRecipes);
        // Only reported when it actually happens: a pack that declares every container keeps the boot log quiet.
        if (inferredContainers[0] > 0) {
            I18n.logDetail("recipe", "recipe.cooking_pot_inferred_containers", "count", inferredContainers[0]);
        }
        Map<String, List<CookingPotRecipe>> newSortedCustomRecipes = new HashMap<>();
        Map<String, List<CookingPotRecipe>> newSortedCustomOnlyRecipes = new HashMap<>();
        for (Map.Entry<String, Map<String, CookingPotRecipe>> entry : newCustomRecipes.entrySet()) {
            newSortedCustomOnlyRecipes.put(entry.getKey(), sortedRecipeList(entry.getValue()));
            Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(newRecipes);
            merged.putAll(entry.getValue());
            newSortedCustomRecipes.put(entry.getKey(), sortedRecipeList(merged));
        }

        Map<String, List<CookingPotRecipe>> newResultToRecipes = buildResultIndex(newSortedRecipes);
        for (List<CookingPotRecipe> groupRecipes : newSortedCustomOnlyRecipes.values()) {
            for (CookingPotRecipe recipe : groupRecipes) {
                newResultToRecipes.computeIfAbsent(getItemKey(recipe.getResult()), k -> new ArrayList<>(1)).add(recipe);
            }
        }

        // Publish the freshly built structures (each a single volatile write). The recipe map is a snapshot
        // because its buffer is done here and no later round may write into a set a region thread is matching.
        this.recipes = Collections.unmodifiableMap(new LinkedHashMap<>(newRecipes));
        this.customRecipes = newCustomRecipes;
        this.ingredientToRecipes = newIngredientToRecipes;
        this.customIngredientToRecipes = newCustomIngredientToRecipes;
        this.sortedRecipes = newSortedRecipes;
        this.sortedCustomRecipes = newSortedCustomRecipes;
        this.sortedCustomOnlyRecipes = newSortedCustomOnlyRecipes;
        this.resultToRecipes = freezeResultIndex(newResultToRecipes);
        this.validContainerKeys = Collections.unmodifiableSet(newValidContainerKeys);
        this.packRecipeCount = newPackRecipeCount;
        this.anyIngredientConstrainsNbt = newAnyIngredientConstrainsNbt;

        vanillaItemIdsByTagCache.clear();
        synchronized (recipeCache) {
            recipeCache.clear();
            recipeMisses.clear();
            recipeGeneration++;
        }
        // Invalidate the recipe-list GUI display cache: this republish path (incl. addon register/
        // unregister) bypasses RecipeViewGui.clearConfigCache.
        RecipeViewGui.clearRecipeDisplayCache();
        // The decoded-snapshot cache is keyed by strings owned by the recipes being replaced, so it is
        // dropped with them rather than being left to hold entries no recipe references any more.
        RecipeItemCodec.clearDecodeCache();
    }

    private void loadCustomRecipes(YamlConfiguration config,
                                   Map<String, Map<String, CookingPotRecipe>> targetCustomRecipes,
                                   Map<String, Map<String, Set<String>>> targetCustomIndex,
                                   Set<String> targetContainerKeys,
                                   int[] inferredContainers) {
        ConfigurationSection root = config.getConfigurationSection("custom_cooking_pot_recipes");
        if (root == null) {
            return;
        }

        int loadedCount = 0;
        for (String groupId : root.getKeys(false)) {
            ConfigurationSection groupSection = root.getConfigurationSection(groupId);
            if (groupSection == null) {
                continue;
            }
            Map<String, CookingPotRecipe> groupRecipes = targetCustomRecipes.computeIfAbsent(groupId, key -> new LinkedHashMap<>());
            for (String recipeId : groupSection.getKeys(false)) {
                ConfigurationSection section = groupSection.getConfigurationSection(recipeId);
                if (section == null) {
                    continue;
                }
                // A recipe already present in this group was contributed by the plugin's own file or an
                // earlier pack; a later pack must not replace it silently.
                if (groupRecipes.containsKey(recipeId)) {
                    I18n.logWarning("recipe.pack_duplicate_skipped", "id", groupId + "." + recipeId, "source", "custom cooking pot group");
                    continue;
                }
                try {
                    CookingPotRecipe recipe = parseRecipe(recipeId, section, 54);
                    groupRecipes.put(recipeId, recipe);
                    indexCustomRecipe(targetCustomIndex, groupId, recipeId, recipe);
                    indexContainer(targetContainerKeys, recipe);
                    if (section.get("container") == null && recipe.getContainer() != null) {
                        inferredContainers[0]++;
                    }
                    loadedCount++;
                } catch (Exception e) {
                    I18n.logWarning("recipe.custom_cooking_pot_load_failed",
                            "id", groupId + "." + recipeId,
                            "path", groupSection.getCurrentPath() + "." + recipeId,
                            "error", e.getMessage());
                }
            }
        }
        I18n.logDetail("recipe", "recipe.custom_cooking_pot_loaded", "count", loadedCount);
    }

    private List<CookingPotRecipe> sortedRecipeList(Map<String, CookingPotRecipe> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<CookingPotRecipe> sorted = new ArrayList<>(source.values());
        sorted.sort(Comparator.comparingInt(CookingPotRecipe::getPriority).reversed()
                .thenComparing(CookingPotRecipe::getId));
        return Collections.unmodifiableList(sorted);
    }

    private void indexDefaultRecipe(Map<String, Set<String>> ingredientIndex, String recipeId, CookingPotRecipe recipe) {
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                ingredientIndex.computeIfAbsent(ingredientKey, k -> new HashSet<>()).add(recipeId);
            }
        }
    }

    private void indexCustomRecipe(Map<String, Map<String, Set<String>>> customIndex, String groupId, String recipeId, CookingPotRecipe recipe) {
        Map<String, Set<String>> groupIndex = customIndex.computeIfAbsent(groupId, key -> new HashMap<>());
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                groupIndex.computeIfAbsent(ingredientKey, key -> new HashSet<>()).add(recipeId);
            }
        }
    }

    private void indexContainer(Set<String> containerKeys, CookingPotRecipe recipe) {
        ItemStack container = recipe.getContainer();
        if (container != null && !container.getType().isAir()) {
            String customId = ItemUtils.getCustomItemId(container);
            if (customId != null) {
                containerKeys.add(customId);
            }
            containerKeys.add("minecraft:" + container.getType().name().toLowerCase(Locale.ROOT));
        }
    }

    private Map<String, List<CookingPotRecipe>> buildResultIndex(List<CookingPotRecipe> recipesToIndex) {
        Map<String, List<CookingPotRecipe>> index = new HashMap<>();
        for (CookingPotRecipe recipe : recipesToIndex) {
            if (recipe.getResult() == null) {
                continue;
            }
            index.computeIfAbsent(getItemKey(recipe.getResult()), k -> new ArrayList<>(1)).add(recipe);
        }
        return index;
    }

    // Deep-freeze a mutable result index into an immutable publish snapshot (unmodifiable map + lists).
    private static Map<String, List<CookingPotRecipe>> freezeResultIndex(Map<String, List<CookingPotRecipe>> source) {
        Map<String, List<CookingPotRecipe>> frozen = new HashMap<>(source.size());
        for (Map.Entry<String, List<CookingPotRecipe>> entry : source.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /** Recipes (default + external, excluding custom-group duplicates) that produce this item. */
    public List<CookingPotRecipe> getRecipesProducing(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return List.of();
        }
        List<CookingPotRecipe> matches = resultToRecipes.get(getItemKey(item));
        return matches == null ? List.of() : matches;
    }

    private CookingPotRecipe parseRecipe(String id, ConfigurationSection section, int maxIngredients) {
        Object rawIngredients = section.get("ingredients");
        if (!(rawIngredients instanceof List<?> ingredientValues) || ingredientValues.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (ingredientValues.size() > maxIngredients) {
            throw new IllegalArgumentException("Recipe can have at most " + maxIngredients + " ingredients");
        }

        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int ingredientIndex = 0; ingredientIndex < ingredientValues.size(); ingredientIndex++) {
            Object rawIngredient = ingredientValues.get(ingredientIndex);
            if (rawIngredient instanceof ConfigurationSection nested) {
                rawIngredient = sectionToMap(nested);
            }
            try {
                RecipeIngredient ingredient = RecipeParsingSupport.parseIngredientValue(rawIngredient);
                if (!ingredientHasMembers(ingredient)) {
                    throw new IllegalArgumentException("Ingredient item or tag has no loaded items at ingredients[" + ingredientIndex + "]: " + rawIngredient);
                }
                ingredients.add(ingredient);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid ingredient at ingredients[" + ingredientIndex + "]: " + e.getMessage(), e);
            }
        }

        Object containerValue = section.get("container");
        // "container: none" (also "air" or an empty string) is the explicit opt-out: this recipe needs no
        // container even though its result declares a remainder. Without the field at all the container is
        // inferred from the result below, which is what lets a hand-written or addon recipe behave like the
        // mod's own container-carrying recipes without repeating the container on every entry.
        ItemStack container = null;
        if (containerValue != null && !isContainerOptOut(containerValue)) {
            container = parseItemValue(containerValue);
            if (container == null || container.getType().isAir()) {
                throw new IllegalArgumentException("Invalid container item: " + containerValue);
            }
        }

        Object resultValue = section.get("result");
        if (resultValue == null) {
            throw new IllegalArgumentException("Recipe must have a result");
        }
        ItemStack result = parseItemValue(resultValue);
        if (result == null) {
            throw new IllegalArgumentException("Invalid result item: " + resultValue);
        }
        if (!(resultValue instanceof Map)) {
            result.setAmount(Math.max(1, ConfigSectionReader.optionalInt(section, "result-count", 1)));
        }

        if (container == null && containerValue == null) {
            ItemStack inferred = inferContainer(result, id);
            if (inferred != null) {
                container = inferred;
            }
        }
        boolean needsContainer = container != null && !container.getType().isAir();

        float experience = Math.max(0, (float) ConfigSectionReader.optionalDouble(section, "experience", 0.0));
        int defaultCookTime = Math.max(1, plugin.getConfigInt(Constants.DEFAULT_COOKING_TIME_COOKING_POT,
                "cooking-pot.cooking.default-cook-time",
                "cooking-pot.default-cook-time"));
        int minCookTime = Math.max(1, plugin.getConfigInt(20,
                "cooking-pot.cooking.min-cook-time",
                "cooking-pot.min-cook-time"));
        int maxCookTime = Math.max(minCookTime, plugin.getConfigInt(6000,
                "cooking-pot.cooking.max-cook-time",
                "cooking-pot.max-cook-time"));
        int cookTime = Math.max(minCookTime, Math.min(maxCookTime,
                ConfigSectionReader.optionalInt(section, "cooking_time", defaultCookTime,
                        "cooking-time", "cook-time")));
        String category = ConfigSectionReader.optionalString(section, "category", "misc");
        int priority = ConfigSectionReader.optionalInt(section, "priority", 0);

        return new CookingPotRecipe(id, ingredients, container, needsContainer, result, experience, cookTime, category, priority);
    }

    /**
     * Values of the container field that explicitly declare "this recipe needs no container", so a
     * result whose own remainder would otherwise be inferred (a soup's bowl, a drink's bottle) can still be
     * cooked without one. Written as a string, because a map value is always a real item snapshot.
     */
    static boolean isContainerOptOut(Object containerValue) {
        if (!(containerValue instanceof String text)) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("none") || normalized.equals("air");
    }

    /**
     * The container a recipe needs, taken from its result item's own remainder (a soup's bowl, a drink's
     * bottle): what a meal leaves behind when eaten is the container it is served in. Null when the inference
     * is switched off, when the result declares nothing, or when the remainder is a configured tool remainder.
     */
    private ItemStack inferContainer(ItemStack result, String recipeId) {
        if (!plugin.getConfigBoolean(true, "cooking-pot.container-inference.enabled",
                "container-inference.enabled")) {
            return null;
        }
        Set<String> excluded = excludedContainerRemainders();
        ItemStack inferred = ItemUtils.craftingRemainderOf(result, recipeId);
        if (inferred == null || inferred.getType().isAir()) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(inferred);
        String vanillaId = ItemUtils.getVanillaMaterialItemId(inferred);
        return isExcludedRemainder(excluded, customId, vanillaId) ? null : inferred;
    }

    private Set<String> excludedContainerRemainders() {
        List<String> configured = plugin.getConfigStringList("cooking-pot.container-inference.excluded-remainders",
                "container-inference.excluded-remainders");
        if (configured.isEmpty()) {
            return Set.of();
        }
        Set<String> excluded = new HashSet<>(configured.size());
        for (String id : configured) {
            if (id != null && !id.isBlank()) {
                excluded.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return excluded;
    }

    /**
     * True when the inferred container must be dropped because it is a configured tool remainder. Ids are
     * compared case-insensitively, and either id form matches, so one entry covers a vanilla item and a
     * CraftEngine item built on it.
     */
    static boolean isExcludedRemainder(Set<String> excluded, String customId, String vanillaId) {
        if (excluded.isEmpty()) {
            return false;
        }
        return (customId != null && excluded.contains(customId.toLowerCase(Locale.ROOT)))
                || (vanillaId != null && excluded.contains(vanillaId.toLowerCase(Locale.ROOT)));
    }

    private boolean ingredientHasMembers(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            ItemStack stack = item.createStack();
            return stack != null && !stack.getType().isAir();
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return !plugin.getCraftEngine().itemManager().itemIdsByTag(tag.key()).isEmpty()
                    || !getVanillaItemIdsByTag(tag.key()).isEmpty()
                    || !CommonTagResolver.getMembers(tag.key()).isEmpty();
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            return choice.options().stream().anyMatch(this::ingredientHasMembers);
        }
        return false;
    }

    private RecipeIngredient parseIngredient(String str) {
        return RecipeParsingSupport.parseIngredientChoice(str);
    }

    private List<String> flattenIngredientKeys(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return List.of(itemIngredient.key().toString());
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return List.of("#" + tagIngredient.key());
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            List<String> keys = new ArrayList<>();
            for (RecipeIngredient option : choiceIngredient.options()) {
                keys.addAll(flattenIngredientKeys(option));
            }
            return keys;
        }
        return List.of();
    }

    private ItemStack createItem(String itemId) {
        return ItemUtils.createItem(itemId);
    }

    private ItemStack parseItemValue(Object value) {
        // Bukkit deserializes nested YAML maps into ConfigurationSection, not java.util.Map, so item
        // objects written in recipes must be converted to a plain map first.
        if (value instanceof ConfigurationSection section) {
            return RecipeItemCodec.deserializeItem(sectionToMap(section));
        }
        if (value instanceof Map<?, ?> map) {
            return RecipeItemCodec.deserializeItem(RecipeItemCodec.coerceStringMap(map));
        }
        return createItem(value.toString());
    }

    // Recursively flattens a configuration section into a plain map so nested sections (e.g. the
    // components map) survive the conversion and reach the item deserializer unchanged.
    private static Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                map.put(key, sectionToMap(nested));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    public CookingPotRecipe matchRecipe(List<ItemStack> inputItems, ItemStack container) {
        return matchRecipe(inputItems, container, null);
    }

    public CookingPotRecipe matchRecipe(List<ItemStack> inputItems, ItemStack container, String customRecipeGroupId) {
        if (inputItems == null || inputItems.isEmpty()) {
            return null;
        }
        
        List<ItemStack> nonEmptyInputs = new ArrayList<>();
        for (ItemStack item : inputItems) {
            if (item != null && item.getType() != Material.AIR) {
                nonEmptyInputs.add(item);
            }
        }
        
        if (nonEmptyInputs.isEmpty()) {
            return null;
        }

        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        return matchWithCaches(buildCacheKey(nonEmptyInputs, container, normalizedGroupId),
                nonEmptyInputs, container, normalizedGroupId);
    }

    /**
     * Matches one input combination around the two caches.
     *
     * A null key means the combination must not be cached (see buildCacheKey): the answer still comes from a
     * live match, but neither cache is read or written for it, because the key could not tell it apart from a
     * different combination.
     */
    CookingPotRecipe matchWithCaches(String cacheKey, List<ItemStack> nonEmptyInputs, ItemStack container,
                                     String normalizedGroupId) {
        if (cacheKey == null) {
            return matchUncached(nonEmptyInputs, container, normalizedGroupId);
        }

        // Snapshot the publish generation BEFORE reading the volatile maps below. Two volatile reads keep
        // program order, so this pairs the match about to be computed with the map version it saw.
        long generationAtStart = recipeGeneration;
        CookingPotRecipe cached;
        boolean cachedMiss;
        synchronized (recipeCache) {
            cached = recipeCache.get(cacheKey);
            cachedMiss = cached == null && recipeMisses.contains(cacheKey);
        }
        if (cachedMiss) {
            return null;
        }
        if (cached != null) {
            return cached;
        }

        CookingPotRecipe result = matchUncached(nonEmptyInputs, container, normalizedGroupId);

        synchronized (recipeCache) {
            // Skip caching if a (re)publish cleared the cache and bumped the generation while
            // matching: this result may be against now-stale maps and would poison the fresh cache.
            if (recipeGeneration == generationAtStart) {
                if (result != null) {
                    recipeCache.put(cacheKey, result);
                } else {
                    // Negative cache so an unchanged incomplete pot won't re-scan every recipe next tick.
                    recipeMisses.add(cacheKey);
                }
            }
        }

        return result;
    }

    private CookingPotRecipe matchUncached(List<ItemStack> nonEmptyInputs, ItemStack container,
                                           String normalizedGroupId) {
        CookingPotRecipe result = null;
        if (normalizedGroupId != null) {
            result = matchCustomRecipe(nonEmptyInputs, container, normalizedGroupId);
        }

        if (result == null) {
            result = matchDefaultRecipe(nonEmptyInputs, container);
        }

        return result;
    }

    private CookingPotRecipe matchCustomRecipe(List<ItemStack> nonEmptyInputs, ItemStack container, String customRecipeGroupId) {
        Map<String, CookingPotRecipe> groupRecipes = customRecipes.get(customRecipeGroupId);
        if (groupRecipes == null || groupRecipes.isEmpty()) {
            return null;
        }
        Set<String> candidateRecipes = findCandidateRecipes(nonEmptyInputs, customIngredientToRecipes.get(customRecipeGroupId));
        List<CookingPotRecipe> orderedRecipes = sortedCustomOnlyRecipes.getOrDefault(customRecipeGroupId, List.of());
        CookingPotRecipe matched = matchFirstRecipe(orderedRecipes, candidateRecipes, container, nonEmptyInputs);
        if (matched != null) {
            return matched;
        }
        if (candidateRecipes != null) {
            return matchFirstRecipe(orderedRecipes, null, container, nonEmptyInputs);
        }
        return null;
    }

    private CookingPotRecipe matchDefaultRecipe(List<ItemStack> nonEmptyInputs, ItemStack container) {
        Set<String> candidateRecipes = findCandidateRecipes(nonEmptyInputs, ingredientToRecipes);

        CookingPotRecipe matched = matchFirstRecipe(sortedRecipes, candidateRecipes, container, nonEmptyInputs);
        if (matched != null) {
            return matched;
        }
        if (candidateRecipes != null) {
            return matchFirstRecipe(sortedRecipes, null, container, nonEmptyInputs);
        }
        return null;
    }

    private CookingPotRecipe matchFirstRecipe(List<CookingPotRecipe> orderedRecipes, Set<String> candidateRecipeIds,
                                              ItemStack container, List<ItemStack> nonEmptyInputs) {
        if (orderedRecipes == null || orderedRecipes.isEmpty()) {
            return null;
        }
        // Prefer a recipe that consumes exactly the filled slots; only when none does, allow a lenient match
        // (extra slots of an ingredient the recipe already uses), so exact recipes are never shadowed.
        // The C slot is a batch-output channel, not a cook gate: the recipe's required container is
        // checked at extraction time (storeCookedResult / tryMovePendingToOutput), never here.
        CookingPotRecipe exact = matchPass(orderedRecipes, candidateRecipeIds, nonEmptyInputs, true);
        if (exact != null) {
            return exact;
        }
        return matchPass(orderedRecipes, candidateRecipeIds, nonEmptyInputs, false);
    }

    private CookingPotRecipe matchPass(List<CookingPotRecipe> orderedRecipes, Set<String> candidateRecipeIds,
                                       List<ItemStack> nonEmptyInputs, boolean exactSlots) {
        for (CookingPotRecipe recipe : orderedRecipes) {
            if (candidateRecipeIds != null && !candidateRecipeIds.contains(recipe.getId())) {
                continue;
            }
            // matchRecipePrefiltered skips the per-call ArrayList alloc that matchRecipe's defensive
            // filter does — caller (matchRecipe public) has already stripped nulls/airs into the list,
            // and matchPass runs this in a tight loop over every recipe in orderedRecipes.
            if (matchRecipePrefiltered(recipe, nonEmptyInputs, exactSlots)) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * The key for one input combination, or null when it must not be cached.
     *
     * Null is returned when a loaded recipe constrains item data but an input's data cannot be projected: the
     * remaining id-only part could not separate that stack from a different stack of the same id, so caching the
     * answer would answer one of them with the other's recipe.
     */
    private String buildCacheKey(List<ItemStack> inputs, ItemStack container, String customRecipeGroupId) {
        // Read once: one key must not mix the formats of two published recipe sets.
        boolean withComponentFingerprint = anyIngredientConstrainsNbt;
        List<String> keys = new ArrayList<>();
        // Clamp the per-slot amount that goes into the key. IngredientMatching caps each slot at
        // min(amount, ingredientCount) interchangeable units, and only recipes with
        // ingredientCount <= inputs.size() can pass its slot-count gate, so any amount above
        // inputs.size() is indistinguishable to the matcher. Collapsing it keeps a hopper-fed pot
        // whose stacks keep growing on one cache entry instead of evicting the whole LRU each tick.
        int amountCap = inputs.size();
        for (ItemStack item : inputs) {
            String fingerprint = null;
            if (withComponentFingerprint) {
                fingerprint = componentFingerprint(item);
                if (fingerprint == null) {
                    return null;
                }
            }
            keys.add(slotKey(getItemKey(item), item.getAmount(), amountCap, fingerprint));
        }
        return assembleCacheKey(keys, getItemKey(container), customRecipeGroupId);
    }

    /**
     * One input's part of the cache key. A null fingerprint (no loaded recipe constrains item data) yields the
     * id + clamped amount this key has always been made of.
     */
    static String slotKey(String itemKey, int amount, int amountCap, String fingerprint) {
        String key = itemKey + ":" + Math.min(amount, amountCap);
        return fingerprint == null ? key : key + '#' + fingerprint;
    }

    /** The full key: the sorted per-input parts (see slotKey) plus the container and recipe-group suffixes. */
    static String assembleCacheKey(List<String> slotKeys, String containerKey, String customRecipeGroupId) {
        List<String> sorted = new ArrayList<>(slotKeys);
        Collections.sort(sorted);

        StringBuilder sb = new StringBuilder();
        for (String key : sorted) {
            sb.append(key).append(';');
        }
        sb.append("|container=").append(containerKey);
        if (customRecipeGroupId != null) {
            sb.append("|group=").append(customRecipeGroupId);
        }
        return sb.toString();
    }

    /**
     * The item's data projection, reached only while a loaded recipe constrains item data. It is the same full
     * byte snapshot recipes store their constraint in and is finer than the matcher's comparison, so two inputs
     * share a key only when they share data too. The amount is normalized away: slotKey already carries the
     * clamped amount, which is the amount policy the matcher is documented to see.
     *
     * @return null when a non-air item's data cannot be projected, which the caller turns into "not cacheable";
     *         air is equivalent in every slot and is projected as a constant
     */
    private static String componentFingerprint(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "none";
        }
        if (item.getAmount() == 1) {
            return RecipeItemCodec.itemToBase64(item);
        }
        ItemStack unit = item.clone();
        unit.setAmount(1);
        return RecipeItemCodec.itemToBase64(unit);
    }

    /**
     * Whether any recipe in the two published buckets matches on an ingredient's item data. Custom groups are
     * scanned as well because a pot cooking from one uses the same two caches.
     */
    static boolean anyRecipeConstrainsNbt(Map<String, CookingPotRecipe> defaultRecipes,
                                          Map<String, Map<String, CookingPotRecipe>> customRecipes) {
        for (CookingPotRecipe recipe : defaultRecipes.values()) {
            if (recipeConstrainsNbt(recipe)) {
                return true;
            }
        }
        for (Map<String, CookingPotRecipe> groupRecipes : customRecipes.values()) {
            for (CookingPotRecipe recipe : groupRecipes.values()) {
                if (recipeConstrainsNbt(recipe)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean recipeConstrainsNbt(CookingPotRecipe recipe) {
        if (recipe == null || recipe.getIngredients() == null) {
            return false;
        }
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            if (ingredientConstrainsNbt(ingredient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An Item ingredient with an nbt snapshot matches on the item's data; a Choice counts when any option does.
     * Tag and plain Item ingredients match on ids and tags only.
     */
    static boolean ingredientConstrainsNbt(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            return item.nbt() != null;
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            for (RecipeIngredient option : choice.options()) {
                if (ingredientConstrainsNbt(option)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> findCandidateRecipes(List<ItemStack> inputs, Map<String, Set<String>> recipeIndex) {
        if (recipeIndex == null || recipeIndex.isEmpty()) {
            return null;
        }
        // Copy-on-write avoids per-call HashSet allocations when only one source set needs merging.
        // candidates / recipesForItem start as shared references to an unmodified index entry; the
        // allocate a real HashSet copy only when a second source forces a union or intersection.
        Set<String> candidates = null;
        boolean candidatesShared = false;

        for (ItemStack item : inputs) {
            Set<String> recipesForItem = null;
            boolean recipesForItemShared = false;

            for (String itemId : ItemUtils.getItemIds(item)) {
                Set<String> indexed = recipeIndex.get(itemId);
                if (indexed == null || indexed.isEmpty()) {
                    continue;
                }
                if (recipesForItem == null) {
                    recipesForItem = indexed;
                    recipesForItemShared = true;
                } else {
                    if (recipesForItemShared) {
                        recipesForItem = new HashSet<>(recipesForItem);
                        recipesForItemShared = false;
                    }
                    recipesForItem.addAll(indexed);
                }
            }
            for (String tagId : ItemUtils.getItemTagIds(item)) {
                Set<String> indexed = recipeIndex.get("#" + tagId);
                if (indexed == null || indexed.isEmpty()) {
                    continue;
                }
                if (recipesForItem == null) {
                    recipesForItem = indexed;
                    recipesForItemShared = true;
                } else {
                    if (recipesForItemShared) {
                        recipesForItem = new HashSet<>(recipesForItem);
                        recipesForItemShared = false;
                    }
                    recipesForItem.addAll(indexed);
                }
            }

            if (recipesForItem != null) {
                if (candidates == null) {
                    candidates = recipesForItem;
                    candidatesShared = recipesForItemShared;
                } else {
                    if (candidatesShared) {
                        candidates = new HashSet<>(candidates);
                        candidatesShared = false;
                    }
                    candidates.retainAll(recipesForItem);
                }
            }
        }

        return candidates;
    }

    private boolean matchRecipe(CookingPotRecipe recipe, List<ItemStack> inputs) {
        // Lenient acceptance: an exact-slot match also satisfies this, so canCraft uses it.
        return matchRecipe(recipe, inputs, false);
    }

    private boolean matchRecipe(CookingPotRecipe recipe, List<ItemStack> inputs, boolean exactSlots) {
        List<ItemStack> nonEmpty = new ArrayList<>();
        for (ItemStack input : inputs) {
            if (input != null && !input.getType().isAir()) {
                nonEmpty.add(input);
            }
        }
        return matchRecipePrefiltered(recipe, nonEmpty, exactSlots);
    }

    private boolean matchRecipePrefiltered(CookingPotRecipe recipe, List<ItemStack> nonEmptyInputs, boolean exactSlots) {
        // Exact matching gives each filled slot a budget of one unit so every slot matches one ingredient.
        // Using stack amounts here could satisfy several ingredients from one slot and leave another unused.
        // Lenient matching uses stack amounts to support ingredients spread across slots,
        // while separately requiring every filled slot to contain a usable ingredient.
        ToIntFunction<ItemStack> unitBudget = exactSlots ? slot -> 1 : ItemStack::getAmount;
        return IngredientMatching.matchesIngredients(
                recipe.getIngredients(), nonEmptyInputs, exactSlots,
                this::matchIngredient, unitBudget);
    }

    public boolean canCraft(CookingPotRecipe recipe, List<ItemStack> inputs) {
        return recipe != null && matchRecipe(recipe, inputs);
    }

    public boolean matchesIngredient(ItemStack item, RecipeIngredient ingredient) {
        return matchIngredient(item, ingredient);
    }

    private boolean matchIngredient(ItemStack item, RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            if (!ItemUtils.matchesItemId(item, itemIngredient.key())) {
                return false;
            }
            if (itemIngredient.nbt() == null) {
                return true;
            }
            ItemStack expected = RecipeItemCodec.itemFromBase64(itemIngredient.nbt());
            return expected != null && expected.isSimilar(item);
        } else if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            for (RecipeIngredient option : choiceIngredient.options()) {
                if (matchIngredient(item, option)) {
                    return true;
                }
            }
            return false;
        } else if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            String vanillaId = ItemUtils.getVanillaMaterialItemId(item);

            if (tagIngredient.excludedItems().stream().anyMatch(excluded -> ItemUtils.matchesItemId(item, excluded))) {
                return false;
            }

            Set<String> itemTags = ItemUtils.getItemTagIds(item);
            if (itemTags.contains(tagIngredient.key().toString())) {
                for (Key excludedTag : tagIngredient.excludedTags()) {
                    if (itemTags.contains(excludedTag.toString())) {
                        return false;
                    }
                }
                return true;
            }

            Set<String> vanillaTags = getVanillaItemIdsByTag(tagIngredient.key());
            boolean matchesBase = vanillaId != null && (vanillaTags.contains(vanillaId)
                    || ItemUtils.matchesVanillaItemTag(item, tagIngredient.key(),
                    tagIngredient.excludedItems(), tagIngredient.excludedTags()));
            if (!matchesBase) {
                return false;
            }
            for (Key excludedTag : tagIngredient.excludedTags()) {
                boolean blocked = vanillaId != null && getVanillaItemIdsByTag(excludedTag).contains(vanillaId);
                if (blocked) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public Set<String> getVanillaItemIdsByTag(Key tagKey) {
        return vanillaItemIdsByTagCache.getIds(tagKey);
    }

    private String getItemKey(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "none";
        }

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        // Distinct MMOItems items can share a base material; key on their identity so different
        // mmoitems:<TYPE>:<ID> items never collide in the recipe cache.
        String mmoId = MMOItemsCompat.getItemId(item);
        if (mmoId != null) {
            return mmoId;
        }
        return ItemUtils.getVanillaMaterialItemId(item);
    }

    public Map<String, CookingPotRecipe> getRecipes() {
        return Collections.unmodifiableMap(recipes);
    }

    public List<CookingPotRecipe> getAllRecipes() {
        Map<String, CookingPotRecipe> defaultRecipes = this.recipes;
        Map<String, Map<String, CookingPotRecipe>> groupedRecipes = this.customRecipes;
        List<CookingPotRecipe> all = new ArrayList<>(defaultRecipes.values());
        for (Map<String, CookingPotRecipe> groupRecipes : groupedRecipes.values()) {
            all.addAll(groupRecipes.values());
        }
        return Collections.unmodifiableList(all);
    }

    public Map<String, CookingPotRecipe> getRecipes(String customRecipeGroupId) {
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return getRecipes();
        }
        Map<String, CookingPotRecipe> groupRecipes = customRecipes.get(normalizedGroupId);
        if (groupRecipes == null || groupRecipes.isEmpty()) {
            return getRecipes();
        }
        Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(recipes);
        merged.putAll(groupRecipes);
        return Collections.unmodifiableMap(merged);
    }

    public List<CookingPotRecipe> getSortedRecipes(String customRecipeGroupId) {
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return sortedRecipes;
        }
        return sortedCustomRecipes.getOrDefault(normalizedGroupId, sortedRecipes);
    }

    public Set<String> getValidContainerKeys() {
        return validContainerKeys;
    }

    public int getRecipeCount() {
        return recipes.size();
    }

    public int getExternalRecipeCount() {
        return externalRecipes.size();
    }

    /** Recipes that reached this manager through a CraftEngine pack section, not the plugin's own file. */
    public int getPackRecipeCount() {
        return packRecipeCount;
    }

    public boolean isExternalRecipe(String id) {
        return id != null && externalRecipes.containsKey(id);
    }

    private static Set<String> externalOverrideIds(YamlConfiguration config, String station) {
        return Set.copyOf(config.getStringList("external-overrides." + station));
    }

    public int getCustomRecipeCount() {
        int count = 0;
        for (Map<String, CookingPotRecipe> groupRecipes : customRecipes.values()) {
            count += groupRecipes.size();
        }
        return count;
    }

    public CookingPotRecipe getRecipe(String id) {
        return recipes.get(id);
    }

    public CookingPotRecipe getRecipe(String customRecipeGroupId, String id) {
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return getRecipe(id);
        }
        Map<String, CookingPotRecipe> groupRecipes = customRecipes.get(normalizedGroupId);
        CookingPotRecipe customRecipe = groupRecipes == null ? null : groupRecipes.get(id);
        return customRecipe != null ? customRecipe : getRecipe(id);
    }

    public void reload() {
        loadRecipes();
    }

    public void registerExternalRecipe(String id, List<String> ingredientSpecs, ItemStack container,
                                       ItemStack result, float experience, int cookTime, String category) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Recipe id is required");
        }
        if (ingredientSpecs == null || ingredientSpecs.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (result == null || result.getType().isAir()) {
            throw new IllegalArgumentException("Recipe must have a result");
        }
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int i = 0; i < ingredientSpecs.size(); i++) {
            String spec = ingredientSpecs.get(i);
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException("Invalid ingredient at ingredients[" + i + "]: empty");
            }
            ingredients.add(parseIngredient(spec));
        }
        boolean needsContainer = container != null && !container.getType().isAir();
        CookingPotRecipe recipe = new CookingPotRecipe(id, ingredients, needsContainer ? container : null,
                needsContainer, result, Math.max(0f, experience), Math.max(1, cookTime),
                category == null ? "misc" : category, 0);
        externalRecipes.put(id, recipe);
        scheduleExternalRepublish();
    }

    public void unregisterExternalRecipe(String id) {
        if (id != null && externalRecipes.remove(id) != null) {
            scheduleExternalRepublish();
        }
    }

    private void scheduleExternalRepublish() {
        if (externalRepublishScheduled) {
            return;
        }
        externalRepublishScheduled = true;
        plugin.scheduler().runLater(() -> {
            externalRepublishScheduled = false;
            loadRecipes();
            // This republish runs after the CraftEngine readiness pass already printed the summary, so
            // without re-reporting the cooking pot count on the console stays one batch behind whatever
            // addons registered. The summary dedupes on its counts digest, making this a no-op when the
            // batch did not move a count.
            plugin.requestContentSummary();
        }, 1L);
    }
    
    public void clearCache() {
        synchronized (recipeCache) {
            recipeCache.clear();
            recipeMisses.clear();
        }
    }

    private String normalizeRecipeGroupId(String customRecipeGroupId) {
        if (customRecipeGroupId == null || customRecipeGroupId.isBlank()) {
            return null;
        }
        return customRecipeGroupId.trim();
    }
}
