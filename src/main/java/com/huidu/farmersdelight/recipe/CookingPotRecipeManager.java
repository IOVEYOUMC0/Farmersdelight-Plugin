package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
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
    private final VanillaTagItemIdCache vanillaItemIdsByTagCache;
    // LRU access-order LinkedHashMap mutates internal state on get(), so concurrent reads from
    // multiple region threads (Folia) would corrupt the doubly-linked list. Wrap in synchronizedMap;
    // callers MUST synchronize externally when iterating (currently no iteration happens).
    private final Map<String, CookingPotRecipe> recipeCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CookingPotRecipe> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });
    private static final int MAX_CACHE_SIZE = 100;
    // Negative-result cache: input+container multisets known to match nothing, so an unchanged incomplete
    // pot (mid-fill, hopper-fed, or junk) does not re-scan every recipe each tick. Bounded LRU like
    // recipeCache, only touched under the recipeCache monitor, cleared + generation-bumped alongside it.
    private final Set<String> recipeMisses = java.util.Collections.newSetFromMap(
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
        // Build everything into fresh local collections first, then publish atomically (below), so readers
        // never see a half-cleared map. Do not clear()/refill the live fields in place.
        Map<String, CookingPotRecipe> newRecipes = new LinkedHashMap<>();
        Map<String, Map<String, CookingPotRecipe>> newCustomRecipes = new HashMap<>();
        Map<String, Set<String>> newIngredientToRecipes = new HashMap<>();
        Map<String, Map<String, Set<String>>> newCustomIngredientToRecipes = new HashMap<>();
        Set<String> newValidContainerKeys = new HashSet<>();

        YamlConfiguration config = RecipeFileLoader.loadRecipeFile(plugin, "recipes/cooking_pot_recipes.yml");
        RecipeFileLoader.loadRecipeSections(plugin, config, "cooking_pot_recipes", "cooking pot",
                (recipeId, section) -> {
                    CookingPotRecipe recipe = parseRecipe(recipeId, section, 6);
                    newRecipes.put(recipeId, recipe);

                    indexDefaultRecipe(newIngredientToRecipes, recipeId, recipe);
                    indexContainer(newValidContainerKeys, recipe);
                });
        loadCustomRecipes(config, newCustomRecipes, newCustomIngredientToRecipes, newValidContainerKeys);

        // Recipes an addon ships inside a CraftEngine pack (<pack>/farmersdelight/*.yml). Loaded after the
        // plugin's own file so a pack can never silently replace a built-in recipe, and before the API merge
        // below so an explicit runtime registration still wins on an id clash. See PackRecipeSource.
        for (PackRecipeSource.Loaded loaded : PackRecipeSource.load(plugin)) {
            RecipeFileLoader.loadRecipeSections(plugin, loaded.config(), "cooking_pot_recipes",
                    "cooking pot [" + loaded.source() + "]",
                    (recipeId, section) -> {
                        if (newRecipes.containsKey(recipeId)) {
                            I18n.logWarning("recipe.pack_duplicate_skipped", "id", recipeId, "source", loaded.source());
                            return;
                        }
                        CookingPotRecipe recipe = parseRecipe(recipeId, section, 6);
                        newRecipes.put(recipeId, recipe);
                        indexDefaultRecipe(newIngredientToRecipes, recipeId, recipe);
                        indexContainer(newValidContainerKeys, recipe);
                    });
            loadCustomRecipes(loaded.config(), newCustomRecipes, newCustomIngredientToRecipes, newValidContainerKeys);
        }

        // Merge addon-registered recipes last so they survive reloads (and override file ids on clash).
        for (CookingPotRecipe recipe : externalRecipes.values()) {
            newRecipes.put(recipe.getId(), recipe);
            indexDefaultRecipe(newIngredientToRecipes, recipe.getId(), recipe);
            indexContainer(newValidContainerKeys, recipe);
        }

        List<CookingPotRecipe> newSortedRecipes = sortedRecipeList(newRecipes);
        Map<String, List<CookingPotRecipe>> newSortedCustomRecipes = new HashMap<>();
        Map<String, List<CookingPotRecipe>> newSortedCustomOnlyRecipes = new HashMap<>();
        for (Map.Entry<String, Map<String, CookingPotRecipe>> entry : newCustomRecipes.entrySet()) {
            newSortedCustomOnlyRecipes.put(entry.getKey(), sortedRecipeList(entry.getValue()));
            Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(newRecipes);
            merged.putAll(entry.getValue());
            newSortedCustomRecipes.put(entry.getKey(), sortedRecipeList(merged));
        }

        // Publish the freshly built structures (each a single volatile write).
        this.recipes = newRecipes;
        this.customRecipes = newCustomRecipes;
        this.ingredientToRecipes = newIngredientToRecipes;
        this.customIngredientToRecipes = newCustomIngredientToRecipes;
        this.sortedRecipes = newSortedRecipes;
        this.sortedCustomRecipes = newSortedCustomRecipes;
        this.sortedCustomOnlyRecipes = newSortedCustomOnlyRecipes;
        this.validContainerKeys = Collections.unmodifiableSet(newValidContainerKeys);

        vanillaItemIdsByTagCache.clear();
        synchronized (recipeCache) {
            recipeCache.clear();
            recipeMisses.clear();
            recipeGeneration++;
        }
        // Invalidate the recipe-list GUI display cache: this republish path (incl. addon register/
        // unregister) bypasses RecipeViewGui.clearConfigCache.
        com.huidu.farmersdelight.gui.RecipeViewGui.clearRecipeDisplayCache();
    }

    private void loadCustomRecipes(YamlConfiguration config,
                                   Map<String, Map<String, CookingPotRecipe>> targetCustomRecipes,
                                   Map<String, Map<String, Set<String>>> targetCustomIndex,
                                   Set<String> targetContainerKeys) {
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
                    loadedCount++;
                } catch (Exception e) {
                    I18n.logWarning("recipe.custom_cooking_pot_load_failed",
                            "id", groupId + "." + recipeId,
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
            containerKeys.add("minecraft:" + container.getType().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private CookingPotRecipe parseRecipe(String id, ConfigurationSection section, int maxIngredients) {
        List<String> ingredientStrings = section.getStringList("ingredients");
        if (ingredientStrings.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (ingredientStrings.size() > maxIngredients) {
            throw new IllegalArgumentException("Recipe can have at most " + maxIngredients + " ingredients");
        }

        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (String ingredientStr : ingredientStrings) {
            ingredients.add(parseIngredient(ingredientStr));
        }

        String containerStr = section.getString("container");
        ItemStack container = containerStr != null ? createItem(containerStr) : null;
        boolean needsContainer = container != null;

        String resultStr = section.getString("result");
        if (resultStr == null) {
            throw new IllegalArgumentException("Recipe must have a result");
        }
        ItemStack result = createItem(resultStr);
        if (result == null) {
            throw new IllegalArgumentException("Invalid result item: " + resultStr);
        }
        result.setAmount(Math.max(1, section.getInt("result-count", 1)));

        float experience = Math.max(0, (float) section.getDouble("experience", 0.0));
        int defaultCookTime = Math.max(1, plugin.getConfigInt(Constants.DEFAULT_COOKING_TIME_COOKING_POT,
                "cooking-pot.cooking.default-cook-time",
                "cooking-pot.default-cook-time"));
        int minCookTime = Math.max(1, plugin.getConfigInt(20,
                "cooking-pot.cooking.min-cook-time",
                "cooking-pot.min-cook-time"));
        int maxCookTime = Math.max(minCookTime, plugin.getConfigInt(6000,
                "cooking-pot.cooking.max-cook-time",
                "cooking-pot.max-cook-time"));
        int cookTime = Math.max(minCookTime, Math.min(maxCookTime, getInt(section,
                defaultCookTime,
                "cooking_time",
                "cooking-time",
                "cook-time")));
        String category = section.getString("category", "misc");
        int priority = section.getInt("priority", 0);

        return new CookingPotRecipe(id, ingredients, container, needsContainer, result, experience, cookTime, category, priority);
    }

    private int getInt(ConfigurationSection section, int defaultValue, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                return section.getInt(key, defaultValue);
            }
        }
        return defaultValue;
    }

    private RecipeIngredient parseIngredient(String str) {
        return RecipeParsingSupport.parseChoice(str, option -> parseSingleIngredient(option.trim()));
    }

    private RecipeIngredient parseSingleIngredient(String str) {
        if (!str.startsWith("#")) {
            return RecipeParsingSupport.parseSimpleItemOrTag(str);
        }
        return RecipeParsingSupport.parseTagIngredientWithExclusions(str, "ingredient");
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
        String cacheKey = buildCacheKey(nonEmptyInputs, container, normalizedGroupId);
        // Snapshot the publish generation BEFORE reading the volatile maps below. Two volatile reads keep
        // program order, so this pairs the match we are about to compute with the map version it saw.
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
        if (cached != null && matchesContainer(cached, container)) {
            return cached;
        }

        CookingPotRecipe result = null;
        if (normalizedGroupId != null) {
            result = matchCustomRecipe(nonEmptyInputs, container, normalizedGroupId);
        }

        if (result == null) {
            result = matchDefaultRecipe(nonEmptyInputs, container);
        }

        synchronized (recipeCache) {
            // Skip caching if a (re)publish cleared the cache and bumped the generation while we were
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
        CookingPotRecipe exact = matchPass(orderedRecipes, candidateRecipeIds, container, nonEmptyInputs, true);
        if (exact != null) {
            return exact;
        }
        return matchPass(orderedRecipes, candidateRecipeIds, container, nonEmptyInputs, false);
    }

    private CookingPotRecipe matchPass(List<CookingPotRecipe> orderedRecipes, Set<String> candidateRecipeIds,
                                       ItemStack container, List<ItemStack> nonEmptyInputs, boolean exactSlots) {
        for (CookingPotRecipe recipe : orderedRecipes) {
            if (candidateRecipeIds != null && !candidateRecipeIds.contains(recipe.getId())) {
                continue;
            }
            // matchRecipePrefiltered skips the per-call ArrayList alloc that matchRecipe's defensive
            // filter does — caller (matchRecipe public) has already stripped nulls/airs into the list,
            // and matchPass runs this in a tight loop over every recipe in orderedRecipes.
            if (matchesContainer(recipe, container) && matchRecipePrefiltered(recipe, nonEmptyInputs, exactSlots)) {
                return recipe;
            }
        }
        return null;
    }

    private String buildCacheKey(List<ItemStack> inputs, ItemStack container, String customRecipeGroupId) {
        List<String> keys = new ArrayList<>();
        // Clamp the per-slot amount that goes into the key. IngredientMatching caps each slot at
        // min(amount, ingredientCount) interchangeable units, and only recipes with
        // ingredientCount <= inputs.size() can pass its slot-count gate, so any amount above
        // inputs.size() is indistinguishable to the matcher. Collapsing it keeps a hopper-fed pot
        // whose stacks keep growing on one cache entry instead of evicting the whole LRU each tick.
        int amountCap = inputs.size();
        for (ItemStack item : inputs) {
            keys.add(getItemKey(item) + ":" + Math.min(item.getAmount(), amountCap));
        }
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(key).append(";");
        }
        sb.append("|container=").append(getItemKey(container));
        if (customRecipeGroupId != null) {
            sb.append("|group=").append(customRecipeGroupId);
        }
        return sb.toString();
    }

    private boolean matchesContainer(CookingPotRecipe recipe, ItemStack container) {
        // The C slot is a batch-output channel, not a cook gate: cooking proceeds regardless of what's in it
        // (empty, wrong, right). The downstream storeCookedResult / tryMovePendingToOutput only count slots
        // that hold the recipe's required container before moving pending->output, so a wrong/missing
        // container just keeps the result in the pending slot until the player swaps in the right one
        // (no dedicated container slot — it comes from the player's hand at extraction time).
        return true;
    }

    private Set<String> findCandidateRecipes(List<ItemStack> inputs, Map<String, Set<String>> recipeIndex) {
        if (recipeIndex == null || recipeIndex.isEmpty()) {
            return null;
        }
        // Copy-on-write to avoid the per-call HashSet allocations the old version did even when only
        // one source set per item / per call needed merging.
        // candidates / recipesForItem start as shared references to an unmodified index entry; we
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

    /** Internal variant for callers that have already filtered out nulls/airs (e.g. the matchPass
     *  loop, which only ever sees the nonEmptyInputs list built once at the top of
     *  ItemStack, String)). Skips the per-call ArrayList allocation the
     *  public matchRecipe does for safety. */
    private boolean matchRecipePrefiltered(CookingPotRecipe recipe, List<ItemStack> nonEmptyInputs, boolean exactSlots) {
        // Unit budget per filled slot. The exact pass mirrors the mod's CookingPotRecipe.matches, which pairs
        // filled input STACKS against ingredients (RecipeMatcher.findMatches over the stack list) and then
        // shrinks every filled slot by exactly one on cook. Feeding the slot's amount as its unit budget lets
        // one stacked slot cover several ingredients, so with slotCount == ingredientCount the matcher can
        // report a match while another filled slot participates in nothing and is never consumed. Capping the
        // exact pass at one unit per slot forces the perfect matching the mod requires: every filled slot must
        // carry exactly one ingredient. The lenient pass keeps the amount budget on purpose — that pass exists
        // for the same ingredient spread over several slots, and it separately requires every filled slot to
        // hold an item the recipe can use.
        ToIntFunction<ItemStack> unitBudget = exactSlots ? slot -> 1 : ItemStack::getAmount;
        return IngredientMatching.matchesIngredients(
                recipe.getIngredients(), nonEmptyInputs, exactSlots,
                this::matchIngredient, unitBudget);
    }

    public boolean canCraft(CookingPotRecipe recipe, List<ItemStack> inputs, ItemStack container) {
        return recipe != null && matchesContainer(recipe, container) && matchRecipe(recipe, inputs);
    }

    /**
     * Containment test for the recipe-list "craftable only" filter: does the supplied item pool (typically the
     * player's whole inventory plus the pot's current inputs) hold enough of every required ingredient,
     * ignoring unrelated items? This is deliberately NOT the real cook question. The real cook feeds the pot's
     * own &lt;=6 input slots into #matchRecipe / #canCraft, whose lenient pass rejects any
     * filled slot the recipe cannot use; a real inventory always has such slots, so that path always answers
     * "no". Here the unit budget stays each stack's amount so a single stack covers several units, but the
     * slot-count and foreign-slot gates are dropped. Container presence is checked by the caller.
     */
    public boolean containsIngredientsFor(CookingPotRecipe recipe, List<ItemStack> available) {
        if (recipe == null) {
            return false;
        }
        List<ItemStack> nonEmpty = new ArrayList<>();
        for (ItemStack item : available) {
            if (item != null && !item.getType().isAir()) {
                nonEmpty.add(item);
            }
        }
        return IngredientMatching.containsIngredients(
                recipe.getIngredients(), nonEmpty, this::matchIngredient, ItemStack::getAmount);
    }

    public boolean matchesIngredient(ItemStack item, RecipeIngredient ingredient) {
        return matchIngredient(item, ingredient);
    }

    private boolean matchIngredient(ItemStack item, RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return ItemUtils.matchesItemId(item, itemIngredient.key());
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
        return customId != null ? customId : ItemUtils.getVanillaMaterialItemId(item);
    }

    public Map<String, CookingPotRecipe> getRecipes() {
        return Collections.unmodifiableMap(recipes);
    }

    /**
     * Every cooking-pot recipe the manager knows: the default set plus the own recipes of each custom
     * group. Callers that must reason about all displayable recipes (recipe discovery, audits) need this
     * because a group's recipes never appear in the default map, only in the group-merged views.
     *
     * A recipe id declared by several groups yields one entry per declaring group, so consumers that key
     * by id should de-duplicate. Read-only: each volatile field is snapshotted once into a local and the
     * published maps are never mutated in place, so the walk sees one consistent generation.
     */
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

    /** Total recipes across every custom pot group. A recipe id declared by several groups counts once per
     *  declaring group, matching how the groups are stored and how many recipes were actually parsed. */
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

    /**
     * Registers (or replaces) an addon-supplied cooking pot recipe at runtime and republishes the recipe
     * maps. The recipe is retained across /fd reload. Ingredient specs use the same syntax as the
     * recipe files ("ns:id", "#ns:tag", "a|b" choices); result carries its own amount.
     */
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
        for (String spec : ingredientSpecs) {
            ingredients.add(parseIngredient(spec));
        }
        boolean needsContainer = container != null && !container.getType().isAir();
        CookingPotRecipe recipe = new CookingPotRecipe(id, ingredients, needsContainer ? container : null,
                needsContainer, result, Math.max(0f, experience), Math.max(1, cookTime),
                category == null ? "misc" : category, 0);
        externalRecipes.put(id, recipe);
        scheduleExternalRepublish();
    }

    /** Removes a previously registered addon recipe and republishes. */
    public void unregisterExternalRecipe(String id) {
        if (id != null && externalRecipes.remove(id) != null) {
            scheduleExternalRepublish();
        }
    }

    /** Coalesces external-recipe republishing to the next tick (one loadRecipes() per batch). */
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
