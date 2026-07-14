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

/**
 * Writes edited recipes back to the recipes/*.yml files and reloads them.
 *
 * Saves are synchronous: they are triggered by an admin action (a GUI button), the files are small, and
 * FarmersDelightPlugin#reloadRecipeFiles() must run on the main/region thread anyway.
 * The files themselves are written atomically (temp file + move), so a crash mid-write does not
 * corrupt the recipe files.
 */
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

    /**
     * @param customGroupId when null or blank, writes to the default cooking-pot recipe group; otherwise
     *                      writes to the custom cooking-pot recipe group id.
     */
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
            body.put("tool", tools.get(0));
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
            YamlConfiguration yaml = RecipeFileLoader.loadRecipeFile(plugin, relativePath);
            mutation.apply(yaml);
            writeAtomically(new File(plugin.getDataFolder(), relativePath), yaml.saveToString());
            plugin.reloadRecipeFiles();
            return true;
        } catch (Exception e) {
            I18n.logWarning("recipe_save_failed", "file", relativePath, "error", e.getMessage());
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
        Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
