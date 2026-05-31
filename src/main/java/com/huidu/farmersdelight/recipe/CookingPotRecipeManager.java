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
    private final Map<String, CookingPotRecipe> recipes = new HashMap<>();
    private final Map<String, Map<String, CookingPotRecipe>> customRecipes = new HashMap<>();
    private final Map<String, Set<String>> ingredientToRecipes = new HashMap<>();
    private final Map<String, Map<String, Set<String>>> customIngredientToRecipes = new HashMap<>();
    private List<CookingPotRecipe> sortedRecipes = List.of();
    private final Map<String, List<CookingPotRecipe>> sortedCustomRecipes = new HashMap<>();
    private final Map<Key, Set<String>> vanillaItemIdsByTagCache = new ConcurrentHashMap<>();
    private final Map<String, CookingPotRecipe> recipeCache = new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CookingPotRecipe> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };
    private static final int MAX_CACHE_SIZE = 100;
    private final Set<String> validContainerKeys = new HashSet<>();

    public CookingPotRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadRecipes() {
        recipes.clear();
        customRecipes.clear();
        ingredientToRecipes.clear();
        customIngredientToRecipes.clear();
        sortedRecipes = List.of();
        sortedCustomRecipes.clear();
        vanillaItemIdsByTagCache.clear();
        synchronized (recipeCache) {
            recipeCache.clear();
        }
        validContainerKeys.clear();
        YamlConfiguration config = RecipeFileLoader.loadRecipeFile(plugin, "recipes/cooking_pot_recipes.yml");
        RecipeFileLoader.loadRecipeSections(plugin, config, "cooking_pot_recipes", "cooking pot",
                (recipeId, section) -> {
                    CookingPotRecipe recipe = parseRecipe(recipeId, section, 6);
                    recipes.put(recipeId, recipe);

                    indexDefaultRecipe(recipeId, recipe);
                    indexContainer(recipe);
                });
        loadCustomRecipes(config);
        rebuildSortedRecipeLists();
    }

    private void loadCustomRecipes(YamlConfiguration config) {
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
            Map<String, CookingPotRecipe> groupRecipes = customRecipes.computeIfAbsent(groupId, key -> new LinkedHashMap<>());
            for (String recipeId : groupSection.getKeys(false)) {
                ConfigurationSection section = groupSection.getConfigurationSection(recipeId);
                if (section == null) {
                    continue;
                }
                try {
                    CookingPotRecipe recipe = parseRecipe(recipeId, section, 54);
                    groupRecipes.put(recipeId, recipe);
                    indexCustomRecipe(groupId, recipeId, recipe);
                    indexContainer(recipe);
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

    private void rebuildSortedRecipeLists() {
        sortedRecipes = sortedRecipeList(recipes);
        sortedCustomRecipes.clear();
        for (Map.Entry<String, Map<String, CookingPotRecipe>> entry : customRecipes.entrySet()) {
            Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(recipes);
            merged.putAll(entry.getValue());
            sortedCustomRecipes.put(entry.getKey(), sortedRecipeList(merged));
        }
    }

    private List<CookingPotRecipe> sortedRecipeList(Map<String, CookingPotRecipe> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<CookingPotRecipe> sorted = new ArrayList<>(source.values());
        sorted.sort(Comparator.comparing(CookingPotRecipe::getId));
        return Collections.unmodifiableList(sorted);
    }

    private void indexDefaultRecipe(String recipeId, CookingPotRecipe recipe) {
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                ingredientToRecipes.computeIfAbsent(ingredientKey, k -> new HashSet<>()).add(recipeId);
            }
        }
    }

    private void indexCustomRecipe(String groupId, String recipeId, CookingPotRecipe recipe) {
        Map<String, Set<String>> groupIndex = customIngredientToRecipes.computeIfAbsent(groupId, key -> new HashMap<>());
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                groupIndex.computeIfAbsent(ingredientKey, key -> new HashSet<>()).add(recipeId);
            }
        }
    }

    private void indexContainer(CookingPotRecipe recipe) {
        ItemStack container = recipe.getContainer();
        if (container != null && !container.getType().isAir()) {
            String customId = ItemUtils.getCustomItemId(container);
            if (customId != null) {
                validContainerKeys.add(customId);
            }
            validContainerKeys.add("minecraft:" + container.getType().name().toLowerCase());
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
        int cookTime = Math.max(20, Math.min(6000, section.getInt("cook-time", Constants.DEFAULT_COOKING_TIME_COOKING_POT)));
        String category = section.getString("category", "misc");

        return new CookingPotRecipe(id, ingredients, container, needsContainer, result, experience, cookTime, category);
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
        if (candidateRecipes != null && !candidateRecipes.isEmpty()) {
            for (String recipeId : candidateRecipes) {
                CookingPotRecipe recipe = groupRecipes.get(recipeId);
                if (recipe != null && matchesContainer(recipe, container) && matchRecipe(recipe, nonEmptyInputs)) {
                    return recipe;
                }
            }
        }
        for (CookingPotRecipe recipe : groupRecipes.values()) {
            if (matchesContainer(recipe, container) && matchRecipe(recipe, nonEmptyInputs)) {
                return recipe;
            }
        }
        return null;
    }

    private CookingPotRecipe matchDefaultRecipe(List<ItemStack> nonEmptyInputs, ItemStack container) {
        Set<String> candidateRecipes = findCandidateRecipes(nonEmptyInputs, ingredientToRecipes);

        if (candidateRecipes != null && !candidateRecipes.isEmpty()) {
            for (String recipeId : candidateRecipes) {
                CookingPotRecipe recipe = recipes.get(recipeId);
                if (recipe != null && matchesContainer(recipe, container) && matchRecipe(recipe, nonEmptyInputs)) {
                    return recipe;
                }
            }
        }
        for (CookingPotRecipe recipe : recipes.values()) {
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
