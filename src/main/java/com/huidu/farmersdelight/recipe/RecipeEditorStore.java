package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecipeEditorStore {

    private static final String COOKING_POT_FILE = "recipes/cooking_pot_recipes.yml";
    private static final String CUTTING_BOARD_FILE = "recipes/cutting_board_recipes.yml";

    private static final String COOKING_POT_ROOT = "cooking_pot_recipes";
    private static final String CUSTOM_COOKING_POT_ROOT = "custom_cooking_pot_recipes";
    private static final String CUTTING_BOARD_ROOT = "cutting_board_recipes";

    private final FarmersDelightPlugin plugin;

    public RecipeEditorStore(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean saveCookingPotRecipe(CookingPotRecipe recipe, String customGroupId) {
        String path = cookingPotPath(recipe.getId(), customGroupId);
        return mutate(COOKING_POT_FILE, yaml -> yaml.set(path, buildCookingPotBody(recipe)));
    }

    public boolean deleteCookingPotRecipe(String recipeId, String customGroupId) {
        String path = cookingPotPath(recipeId, customGroupId);
        return mutate(COOKING_POT_FILE, yaml -> yaml.set(path, null));
    }

    public boolean saveCuttingBoardRecipe(CuttingBoardRecipe recipe) {
        String path = CUTTING_BOARD_ROOT + "." + recipe.getId();
        return mutate(CUTTING_BOARD_FILE, yaml -> yaml.set(path, buildCuttingBoardBody(recipe)));
    }

    public boolean deleteCuttingBoardRecipe(String recipeId) {
        String path = CUTTING_BOARD_ROOT + "." + recipeId;
        return mutate(CUTTING_BOARD_FILE, yaml -> yaml.set(path, null));
    }

    private String cookingPotPath(String recipeId, String customGroupId) {
        if (customGroupId == null || customGroupId.isBlank()) {
            return COOKING_POT_ROOT + "." + recipeId;
        }
        return CUSTOM_COOKING_POT_ROOT + "." + customGroupId + "." + recipeId;
    }

    private Map<String, Object> buildCookingPotBody(CookingPotRecipe recipe) {
        Map<String, Object> body = new LinkedHashMap<>();

        List<String> ingredients = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            ingredients.add(RecipeSerializer.serializeIngredient(ingredient));
        }
        body.put("ingredients", ingredients);

        ItemStack container = recipe.getContainer();
        if (container != null && !container.getType().isAir()) {
            body.put("container", RecipeSerializer.itemIdString(container));
        }

        ItemStack result = recipe.getResult();
        body.put("result", RecipeSerializer.itemIdString(result));
        if (result != null && result.getAmount() > 1) {
            body.put("result-count", result.getAmount());
        }
        if (recipe.getExperience() > 0.0f) {
            body.put("experience", (double) recipe.getExperience());
        }
        body.put("cook-time", recipe.getCookTime());
        if (recipe.getCategory() != null && !recipe.getCategory().isBlank()) {
            body.put("category", recipe.getCategory());
        }
        if (recipe.getPriority() != 0) {
            body.put("priority", recipe.getPriority());
        }
        return body;
    }

    private Map<String, Object> buildCuttingBoardBody(CuttingBoardRecipe recipe) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", RecipeSerializer.serializeIngredient(recipe.getInput()));

        List<String> tools = new ArrayList<>();
        for (CuttingBoardRecipe.ToolRequirement tool : recipe.getTools()) {
            tools.add(RecipeSerializer.serializeTool(tool));
        }
        if (tools.size() == 1) {
            body.put("tool", tools.getFirst());
        } else if (!tools.isEmpty()) {
            body.put("tools", tools);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (CuttingBoardRecipe.ResultEntry entry : recipe.getResults()) {
            ItemStack item = entry.getItem();
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("item", RecipeSerializer.itemIdString(item));
            if (item.getAmount() > 1) {
                resultMap.put("count", item.getAmount());
            }
            if (entry.getChance() < 1.0d) {
                resultMap.put("chance", entry.getChance());
            }
            results.add(resultMap);
        }
        body.put("results", results);

        if (recipe.getSound() != null && !recipe.getSound().isBlank()
                && !recipe.getSound().equals(Constants.SOUND_CUTTING_BOARD_KNIFE)) {
            body.put("sound", recipe.getSound());
        }
        if (recipe.getPriority() != 0) {
            body.put("priority", recipe.getPriority());
        }
        return body;
    }

    private interface YamlMutation {
        void apply(YamlConfiguration yaml);
    }

    private boolean mutate(String relativePath, YamlMutation mutation) {
        try {
            // Load without bundled-recipe reconciliation: this path reads the file only to write it straight
            // back, so a merge here would re-add in the same operation the very recipe an admin just deleted
            // in the editor.
            YamlConfiguration yaml = RecipeFileLoader.loadRecipeFile(plugin, relativePath, false);
            mutation.apply(yaml);
            writeAtomically(new File(plugin.getDataFolder(), relativePath), yaml.saveToString());
            plugin.reloadRecipeFiles();
            return true;
        } catch (Exception e) {
            I18n.logWarning("plugin.recipe_save_failed", "file", relativePath, "error", e.getMessage());
            return false;
        }
    }

    private void writeAtomically(File target, String content) throws IOException {
        Path targetPath = target.toPath();
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = targetPath.resolveSibling(target.getName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
