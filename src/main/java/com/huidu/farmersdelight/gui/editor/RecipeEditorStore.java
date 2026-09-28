package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.recipe.RecipeFileLoader;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles.RecipeOwner;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
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
    private static final String EXTERNAL_OVERRIDES_ROOT = "external-overrides";
    /** Written when a recipe must keep no container although its result declares one (see the recipe loader). */
    static final String CONTAINER_OPT_OUT = "none";

    private final FarmersDelightPlugin plugin;

    public RecipeEditorStore(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean saveCookingPotRecipe(CookingPotRecipe recipe, String customGroupId) {
        if (customGroupId == null || customGroupId.isBlank()) {
            RecipeOwner owner =
                    AddonRecipeFiles.ownerOf(
                            "cooking_pot", recipe.getId());
            if (owner != null) {
                return mutateExternal(owner, yaml -> yaml.set(owner.yamlPath(), buildCookingPotBody(recipe)));
            }
            if (plugin.getCookingPotRecipes().isExternalRecipe(recipe.getId())) {
                return mutate(COOKING_POT_FILE, yaml -> {
                    putRecipe(yaml, COOKING_POT_ROOT, recipe.getId(), buildCookingPotBody(recipe));
                    setExternalOverride(yaml, "cooking_pot", recipe.getId(), true);
                });
            }
        }
        String path = cookingPotPath(recipe.getId(), customGroupId);
        return mutate(COOKING_POT_FILE, yaml -> yaml.set(path, buildCookingPotBody(recipe)));
    }

    public boolean deleteCookingPotRecipe(String recipeId, String customGroupId) {
        if (customGroupId == null || customGroupId.isBlank()) {
            RecipeOwner owner =
                    AddonRecipeFiles.ownerOf("cooking_pot", recipeId);
            if (owner != null) {
                return mutateExternal(owner, yaml -> yaml.set(owner.yamlPath(), null));
            }
            if (plugin.getCookingPotRecipes().isExternalRecipe(recipeId)) {
                return mutate(COOKING_POT_FILE, yaml -> {
                    putRecipe(yaml, COOKING_POT_ROOT, recipeId, null);
                    setExternalOverride(yaml, "cooking_pot", recipeId, false);
                });
            }
        }
        String path = cookingPotPath(recipeId, customGroupId);
        return mutate(COOKING_POT_FILE, yaml -> yaml.set(path, null));
    }

    public boolean saveCuttingBoardRecipe(CuttingBoardRecipe recipe) {
        RecipeOwner owner =
                AddonRecipeFiles.ownerOf(
                        "cutting_board", recipe.getId());
        if (owner != null) {
            return mutateExternal(owner, yaml -> yaml.set(owner.yamlPath(), buildCuttingBoardBody(recipe)));
        }
        if (plugin.getCuttingBoardRecipes().isExternalRecipe(recipe.getId())) {
            return mutate(CUTTING_BOARD_FILE, yaml -> {
                putRecipe(yaml, CUTTING_BOARD_ROOT, recipe.getId(), buildCuttingBoardBody(recipe));
                setExternalOverride(yaml, "cutting_board", recipe.getId(), true);
            });
        }
        String path = CUTTING_BOARD_ROOT + "." + recipe.getId();
        return mutate(CUTTING_BOARD_FILE, yaml -> yaml.set(path, buildCuttingBoardBody(recipe)));
    }

    public boolean deleteCuttingBoardRecipe(String recipeId) {
        RecipeOwner owner =
                AddonRecipeFiles.ownerOf("cutting_board", recipeId);
        if (owner != null) {
            return mutateExternal(owner, yaml -> yaml.set(owner.yamlPath(), null));
        }
        if (plugin.getCuttingBoardRecipes().isExternalRecipe(recipeId)) {
            return mutate(CUTTING_BOARD_FILE, yaml -> {
                putRecipe(yaml, CUTTING_BOARD_ROOT, recipeId, null);
                setExternalOverride(yaml, "cutting_board", recipeId, false);
            });
        }
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

        List<Object> ingredients = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            ingredients.add(RecipeSerializer.serializeIngredientValue(ingredient));
        }
        body.put("ingredients", ingredients);

        ItemStack container = recipe.getContainer();
        if (container != null && !container.getType().isAir()) {
            Map<String, Object> snapshot = RecipeItemCodec.snapshotIfCustom(container);
            body.put("container", snapshot != null ? snapshot : RecipeSerializer.itemIdString(container));
        } else if (recipe.getResult() != null && !recipe.getResult().getType().isAir()
                && ItemUtils.craftingRemainderOf(recipe.getResult(), recipe.getId()) != null) {
            // Saved without a container while the result declares one: write the explicit opt-out, otherwise
            // loading the file would infer that container right back.
            body.put("container", CONTAINER_OPT_OUT);
        }

        ItemStack result = recipe.getResult();
        Map<String, Object> resultSnapshot = RecipeItemCodec.snapshotIfCustom(result);
        if (resultSnapshot != null) {
            body.put("result", resultSnapshot);
        } else {
            body.put("result", RecipeSerializer.itemIdString(result));
            if (result != null && result.getAmount() > 1) {
                body.put("result-count", result.getAmount());
            }
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
        body.put("input", RecipeSerializer.serializeIngredientValue(recipe.getInput()));

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
            Map<String, Object> snapshot = RecipeItemCodec.snapshotIfCustom(item);
            if (snapshot != null) {
                resultMap.putAll(snapshot);
            } else {
                resultMap.put("item", RecipeSerializer.itemIdString(item));
                if (item.getAmount() > 1) {
                    resultMap.put("count", item.getAmount());
                }
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
            if (yaml == null) {
                // The file is unreadable: writing the mutation would replace every remaining entry with the
                // mutated empty configuration, so the edit is refused and the operator keeps the file.
                I18n.logWarning("plugin.recipe_save_failed", "file", relativePath,
                        "error", I18n.formatConsole("plugin.recipe_unreadable"));
                return false;
            }
            mutation.apply(yaml);
            writeAtomically(new File(plugin.getDataFolder(), relativePath), yaml.saveToString());
            plugin.reloadRecipeFiles();
            return true;
        } catch (Exception e) {
            I18n.logWarning("plugin.recipe_save_failed", "file", relativePath, "error", e.getMessage());
            return false;
        }
    }

    private boolean mutateExternal(RecipeOwner owner,
                                   YamlMutation mutation) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(owner.file());
            mutation.apply(yaml);
            writeAtomically(owner.file(), yaml.saveToString());
            plugin.reloadRecipeFiles();
            return true;
        } catch (Exception e) {
            I18n.logWarning("plugin.recipe_save_failed", "file", owner.file().getPath(), "error", e.getMessage());
            return false;
        }
    }

    private static void putRecipe(YamlConfiguration yaml, String rootName, String id, Object value) {
        ConfigurationSection root = yaml.getConfigurationSection(rootName);
        Map<String, Object> entries = root == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(root.getValues(false));
        if (value == null) {
            entries.remove(id);
        } else {
            entries.put(id, value);
        }
        yaml.set(rootName, entries.isEmpty() ? null : entries);
    }

    private static void setExternalOverride(YamlConfiguration yaml, String station, String id, boolean enabled) {
        String path = EXTERNAL_OVERRIDES_ROOT + "." + station;
        List<String> ids = new ArrayList<>(yaml.getStringList(path));
        ids.removeIf(id::equals);
        if (enabled) {
            ids.add(id);
        }
        yaml.set(path, ids.isEmpty() ? null : ids);
    }

    // Package-private and static so RecipeDiscoveryManager flushes the same way: a torn write there loses
    // every player's unlocks at once.
    static void writeAtomically(File target, String content) throws IOException {
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
