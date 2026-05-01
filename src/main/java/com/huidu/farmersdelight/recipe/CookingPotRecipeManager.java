package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CookingPotRecipeManager {

    private final FarmersDelightPlugin plugin;
    private final Map<String, CookingPotRecipe> recipes = new HashMap<>();
    private final Map<String, Set<String>> ingredientToRecipes = new HashMap<>();
    private final Map<String, CookingPotRecipe> recipeCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 100;

    public CookingPotRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadRecipes() {
        recipes.clear();
        ingredientToRecipes.clear();
        recipeCache.clear();
        RecipeFileLoader.loadRecipeSections(plugin, "recipes/cooking_pot_recipes.yml", "cooking_pot_recipes", "cooking pot",
                (recipeId, section) -> {
                    CookingPotRecipe recipe = parseRecipe(recipeId, section);
                    recipes.put(recipeId, recipe);

                    for (RecipeIngredient ingredient : recipe.getIngredients()) {
                        for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                            ingredientToRecipes.computeIfAbsent(ingredientKey, k -> new HashSet<>()).add(recipeId);
                        }
                    }
                });
    }

    private CookingPotRecipe parseRecipe(String id, ConfigurationSection section) {
        List<String> ingredientStrings = section.getStringList("ingredients");
        if (ingredientStrings.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (ingredientStrings.size() > 6) {
            throw new IllegalArgumentException("Recipe can have at most 6 ingredients");
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
        int cookTime = Math.max(20, Math.min(6000, section.getInt("cook-time", 200)));
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

        String cacheKey = buildCacheKey(nonEmptyInputs);
        CookingPotRecipe cached = recipeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Set<String> candidateRecipes = findCandidateRecipes(nonEmptyInputs);
        
        CookingPotRecipe result = null;
        
        if (candidateRecipes != null && !candidateRecipes.isEmpty()) {
            for (String recipeId : candidateRecipes) {
                CookingPotRecipe recipe = recipes.get(recipeId);
                if (recipe != null && matchRecipe(recipe, nonEmptyInputs, container)) {
                    result = recipe;
                    break;
                }
            }
        } else {
            for (CookingPotRecipe recipe : recipes.values()) {
                if (matchRecipe(recipe, nonEmptyInputs, container)) {
                    result = recipe;
                    break;
                }
            }
        }

        if (result != null && recipeCache.size() < MAX_CACHE_SIZE) {
            recipeCache.put(cacheKey, result);
        }

        return result;
    }

    private String buildCacheKey(List<ItemStack> inputs) {
        List<String> keys = new ArrayList<>();
        for (ItemStack item : inputs) {
            keys.add(getItemKey(item) + ":" + item.getAmount());
        }
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(key).append(";");
        }
        return sb.toString();
    }

    private Set<String> findCandidateRecipes(List<ItemStack> inputs) {
        Set<String> candidates = null;
        
        for (ItemStack item : inputs) {
            String customId = ItemUtils.getCustomItemId(item);
            String vanillaId = ItemUtils.getVanillaMaterialItemId(item);
            Set<String> recipesForItem = null;
            
            if (customId != null) {
                recipesForItem = ingredientToRecipes.get(customId);
            }
            
            if (recipesForItem == null && vanillaId != null) {
                recipesForItem = ingredientToRecipes.get(vanillaId);
            } else if (recipesForItem != null && vanillaId != null) {
                Set<String> vanillaRecipes = ingredientToRecipes.get(vanillaId);
                if (vanillaRecipes != null && !vanillaRecipes.isEmpty()) {
                    recipesForItem = new HashSet<>(recipesForItem);
                    recipesForItem.addAll(vanillaRecipes);
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

    private boolean matchRecipe(CookingPotRecipe recipe, List<ItemStack> inputs, ItemStack container) {
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

        for (ItemStack input : inputs) {
            if (input == null || input.getType().isAir()) {
                continue;
            }

            Material type = input.getType();
            if (type == Material.BUCKET || type == Material.GLASS_BOTTLE || type == Material.BOWL) {
                continue;
            }

            boolean isRelevant = false;
            for (RecipeIngredient ingredient : requiredIngredients) {
                if (matchIngredient(input, ingredient)) {
                    isRelevant = true;
                    break;
                }
            }

            if (!isRelevant) {
                return false;
            }
        }

        return true;
    }

    private boolean matchIngredient(ItemStack item, RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            String customId = ItemUtils.getCustomItemId(item);
            if (customId != null) {
                if (customId.equals(itemIngredient.key().toString())) {
                    return true;
                }
                String vanillaId = ItemUtils.getVanillaMaterialItemId(item);
                return vanillaId != null && vanillaId.equals(itemIngredient.key().toString());
            }
            String vanillaId = ItemUtils.getVanillaMaterialItemId(item);
            return vanillaId != null && vanillaId.equals(itemIngredient.key().toString());
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
            Key itemKey = customId != null
                    ? Key.of(customId)
                    : Key.of(vanillaId);

            if (tagIngredient.excludedItems().contains(itemKey)) {
                return false;
            }

            if (customId != null) {
                var customItem = plugin.getCraftEngine().itemManager()
                        .getCustomItem(Key.of(customId)).orElse(null);
                if (customItem != null && customItem.settings().tags().contains(tagIngredient.key())) {
                    for (Key excludedTag : tagIngredient.excludedTags()) {
                        if (customItem.settings().tags().contains(excludedTag)) {
                            return false;
                        }
                    }
                    return true;
                }
            }

            var vanillaTags = plugin.getCraftEngine().itemManager()
                    .vanillaItemIdsByTag(tagIngredient.key());
            boolean matchesBase = vanillaId != null && (vanillaTags.stream()
                    .anyMatch(k -> k.toString().equals(vanillaId))
                    || ItemUtils.matchesVanillaItemTag(item, tagIngredient.key(),
                    tagIngredient.excludedItems(), tagIngredient.excludedTags()));
            if (!matchesBase) {
                return false;
            }
            for (Key excludedTag : tagIngredient.excludedTags()) {
                boolean blocked = vanillaId != null && plugin.getCraftEngine().itemManager()
                        .vanillaItemIdsByTag(excludedTag).stream()
                        .anyMatch(k -> k.toString().equals(vanillaId));
                if (blocked) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private String getItemKey(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "none";
        }

        String customId = ItemUtils.getCustomItemId(item);
        return customId != null ? customId : ItemUtils.getVanillaMaterialItemId(item);
    }

    private boolean sameItem(ItemStack first, ItemStack second) {
        if (first == null || second == null || first.getType().isAir() || second.getType().isAir()) {
            return false;
        }

        String firstCustomId = ItemUtils.getCustomItemId(first);
        String secondCustomId = ItemUtils.getCustomItemId(second);
        if (firstCustomId != null || secondCustomId != null) {
            return firstCustomId != null && firstCustomId.equals(secondCustomId);
        }

        return first.getType() == second.getType();
    }

    public Map<String, CookingPotRecipe> getRecipes() {
        return Collections.unmodifiableMap(recipes);
    }

    public int getRecipeCount() {
        return recipes.size();
    }

    public CookingPotRecipe getRecipe(String id) {
        return recipes.get(id);
    }

    public void reload() {
        loadRecipes();
    }
    
    public void clearCache() {
        recipeCache.clear();
    }
}
