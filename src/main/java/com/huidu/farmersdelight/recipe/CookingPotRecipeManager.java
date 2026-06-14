package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CookingPotRecipeManager {

    private final FarmersDelightPlugin plugin;
    // 这些查找结构会在 /fd reload 时重建。它们以整体、全新构建的、发布后不可变的 map 形式，
    // 通过单次 volatile 写入进行发布，从而保证并发读取者（cooking-pot 的 tick / GUI，它们在 Folia
    // 的 region 线程上运行，而 reload 在 global 线程上运行）永远不会观察到一个被清空一半的 map。
    // 发布之后绝不要对它们进行原地修改。
    private volatile Map<String, CookingPotRecipe> recipes = Map.of();
    private volatile Map<String, Map<String, CookingPotRecipe>> customRecipes = Map.of();
    private volatile Map<String, Set<String>> ingredientToRecipes = Map.of();
    private volatile Map<String, Map<String, Set<String>>> customIngredientToRecipes = Map.of();
    private volatile List<CookingPotRecipe> sortedRecipes = List.of();
    private volatile Map<String, List<CookingPotRecipe>> sortedCustomRecipes = Map.of();
    private volatile Map<String, List<CookingPotRecipe>> sortedCustomOnlyRecipes = Map.of();
    private final Map<Key, Set<String>> vanillaItemIdsByTagCache = new ConcurrentHashMap<>();
    private final Map<String, CookingPotRecipe> recipeCache = new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CookingPotRecipe> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };
    private static final int MAX_CACHE_SIZE = 100;
    private volatile Set<String> validContainerKeys = Set.of();

    public CookingPotRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadRecipes() {
        // 先将所有内容构建到全新的本地集合中，然后（在下方）原子地发布，从而保证读取者
        // 永远不会看到一个被清空一半的 map。不要对正在使用的字段进行原地 clear()/重新填充。
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

        List<CookingPotRecipe> newSortedRecipes = sortedRecipeList(newRecipes);
        Map<String, List<CookingPotRecipe>> newSortedCustomRecipes = new HashMap<>();
        Map<String, List<CookingPotRecipe>> newSortedCustomOnlyRecipes = new HashMap<>();
        for (Map.Entry<String, Map<String, CookingPotRecipe>> entry : newCustomRecipes.entrySet()) {
            newSortedCustomOnlyRecipes.put(entry.getKey(), sortedRecipeList(entry.getValue()));
            Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(newRecipes);
            merged.putAll(entry.getValue());
            newSortedCustomRecipes.put(entry.getKey(), sortedRecipeList(merged));
        }

        // 发布全新构建的结构（每个都是单次 volatile 写入）。
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
        }
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
        if (loadedCount > 0 || plugin.isDebugEnabled()) {
            I18n.logInfo("recipe.custom_cooking_pot_loaded", "count", loadedCount);
        }
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
        String[] choiceParts = str.split("\\|");
        if (choiceParts.length > 1) {
            List<RecipeIngredient> options = new ArrayList<>();
            for (String choicePart : choiceParts) {
                String trimmed = choicePart.trim();
                if (!trimmed.isEmpty()) {
                    options.add(parseSingleIngredient(trimmed));
                }
            }
            if (options.isEmpty()) {
                throw new IllegalArgumentException("Choice ingredient must contain at least one option");
            }
            if (options.size() == 1) {
                return options.get(0);
            }
            return new RecipeIngredient.Choice(options);
        }

        return parseSingleIngredient(str.trim());
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
        CookingPotRecipe cached;
        synchronized (recipeCache) {
            cached = recipeCache.get(cacheKey);
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

        if (result != null) {
            synchronized (recipeCache) {
                recipeCache.put(cacheKey, result);
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
        for (CookingPotRecipe recipe : orderedRecipes) {
            if (candidateRecipeIds != null && !candidateRecipeIds.contains(recipe.getId())) {
                continue;
            }
            if (matchesContainer(recipe, container) && matchRecipe(recipe, nonEmptyInputs)) {
                return recipe;
            }
        }
        return null;
    }

    private String buildCacheKey(List<ItemStack> inputs, ItemStack container, String customRecipeGroupId) {
        List<String> keys = new ArrayList<>();
        for (ItemStack item : inputs) {
            keys.add(getItemKey(item) + ":" + item.getAmount());
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
        if (!recipe.needsContainer()) {
            return true;
        }
        if (container == null || container.getType().isAir()) {
            return true;
        }
        ItemStack required = recipe.getContainer();
        if (required == null || required.getType().isAir()) {
            return true;
        }
        String requiredCustomId = ItemUtils.getCustomItemId(required);
        String providedCustomId = ItemUtils.getCustomItemId(container);
        if (requiredCustomId != null || providedCustomId != null) {
            return requiredCustomId != null && requiredCustomId.equals(providedCustomId);
        }
        return required.isSimilar(container);
    }

    private Set<String> findCandidateRecipes(List<ItemStack> inputs, Map<String, Set<String>> recipeIndex) {
        if (recipeIndex == null || recipeIndex.isEmpty()) {
            return null;
        }
        Set<String> candidates = null;
        
        for (ItemStack item : inputs) {
            Set<String> recipesForItem = null;

            for (String itemId : ItemUtils.getItemIds(item)) {
                Set<String> indexed = recipeIndex.get(itemId);
                if (indexed == null || indexed.isEmpty()) {
                    continue;
                }
                if (recipesForItem == null) {
                    recipesForItem = new HashSet<>(indexed);
                } else {
                    recipesForItem.addAll(indexed);
                }
            }
            for (String tagId : ItemUtils.getItemTagIds(item)) {
                Set<String> indexed = recipeIndex.get("#" + tagId);
                if (indexed == null || indexed.isEmpty()) {
                    continue;
                }
                if (recipesForItem == null) {
                    recipesForItem = new HashSet<>(indexed);
                } else {
                    recipesForItem.addAll(indexed);
                }
            }
            
            if (recipesForItem != null) {
                if (candidates == null) {
                    candidates = new HashSet<>(recipesForItem);
                } else {
                    candidates.retainAll(recipesForItem);
                }
            }
        }
        
        return candidates;
    }

    private boolean matchRecipe(CookingPotRecipe recipe, List<ItemStack> inputs) {
        List<RecipeIngredient> requiredIngredients = recipe.getIngredients();

        List<ItemStack> availableInputs = new ArrayList<>();
        for (ItemStack input : inputs) {
            if (input != null && !input.getType().isAir()) {
                availableInputs.add(input.clone());
            }
        }

        for (RecipeIngredient ingredient : requiredIngredients) {
            boolean found = false;
            for (int i = 0; i < availableInputs.size(); i++) {
                ItemStack available = availableInputs.get(i);
                if (available != null && available.getAmount() > 0 && matchIngredient(available, ingredient)) {
                    available.setAmount(available.getAmount() - 1);
                    if (available.getAmount() <= 0) {
                        availableInputs.set(i, null);
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }

        return true;
    }

    public boolean canCraft(CookingPotRecipe recipe, List<ItemStack> inputs, ItemStack container) {
        return recipe != null && matchesContainer(recipe, container) && matchRecipe(recipe, inputs);
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
            String customId = ItemUtils.getCustomItemId(item);
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

    private Set<String> getVanillaItemIdsByTag(Key tagKey) {
        if (tagKey == null) {
            return Set.of();
        }
        return vanillaItemIdsByTagCache.computeIfAbsent(tagKey, key -> {
            var craftEngine = plugin.getCraftEngine();
            if (craftEngine == null || craftEngine.itemManager() == null) {
                return Set.of();
            }
            Set<String> itemIds = new HashSet<>();
            for (var itemId : craftEngine.itemManager().vanillaItemIdsByTag(key)) {
                itemIds.add(itemId.toString());
            }
            return itemIds.isEmpty() ? Set.of() : Collections.unmodifiableSet(itemIds);
        });
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
    
    public void clearCache() {
        synchronized (recipeCache) {
            recipeCache.clear();
        }
    }

    private String normalizeRecipeGroupId(String customRecipeGroupId) {
        if (customRecipeGroupId == null || customRecipeGroupId.isBlank()) {
            return null;
        }
        return customRecipeGroupId.trim();
    }
}
