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
import com.huidu.farmersdelight.util.yaml.BukkitYamlValueWriter;
import com.huidu.farmersdelight.util.yaml.YamlFileTransaction;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
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
import java.util.function.Consumer;

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
    private final EditorWriteQueue writeQueue;

    public RecipeEditorStore(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.writeQueue = new EditorWriteQueue(plugin.scheduler()::tryRunAsync);
    }

    public boolean saveCookingPotRecipe(CookingPotRecipe recipe, String customGroupId) {
        return applyPlan(planSaveCookingPotRecipe(recipe, customGroupId));
    }

    /**
     * Saves a cooking pot recipe off the owner thread.
     *
     *
     * The plan is built here, on the thread that owns the player and CraftEngine's items; only the file write
     * runs elsewhere, and the completion runs back on the player's own thread before any menu changes. False
     * means the write was refused outright, so nothing ran and no completion will follow.
     */
    public boolean saveCookingPotRecipeAsync(CookingPotRecipe recipe, String customGroupId, Player player,
                                             Consumer<Boolean> completion) {
        return submitPlan(planSaveCookingPotRecipe(recipe, customGroupId), player, completion);
    }

    public boolean deleteCookingPotRecipe(String recipeId, String customGroupId) {
        return applyPlan(planDeleteCookingPotRecipe(recipeId, customGroupId));
    }

    /** Deletes a cooking pot recipe off the owner thread; see saveCookingPotRecipeAsync. */
    public boolean deleteCookingPotRecipeAsync(String recipeId, String customGroupId, Player player,
                                               Consumer<Boolean> completion) {
        return submitPlan(planDeleteCookingPotRecipe(recipeId, customGroupId), player, completion);
    }

    public boolean saveCuttingBoardRecipe(CuttingBoardRecipe recipe) {
        return applyPlan(planSaveCuttingBoardRecipe(recipe));
    }

    /** Saves a cutting board recipe off the owner thread; see saveCookingPotRecipeAsync. */
    public boolean saveCuttingBoardRecipeAsync(CuttingBoardRecipe recipe, Player player,
                                               Consumer<Boolean> completion) {
        return submitPlan(planSaveCuttingBoardRecipe(recipe), player, completion);
    }

    public boolean deleteCuttingBoardRecipe(String recipeId) {
        return applyPlan(planDeleteCuttingBoardRecipe(recipeId));
    }

    /** Deletes a cutting board recipe off the owner thread; see saveCookingPotRecipeAsync. */
    public boolean deleteCuttingBoardRecipeAsync(String recipeId, Player player, Consumer<Boolean> completion) {
        return submitPlan(planDeleteCuttingBoardRecipe(recipeId), player, completion);
    }

    /** The file and the entry-level edits one editor action writes, built on the calling thread. */
    private record RecipeEdit(File file, List<YamlFileTransaction.Edit> edits) {
    }

    private RecipeEdit planSaveCookingPotRecipe(CookingPotRecipe recipe, String customGroupId) {
        if (customGroupId == null || customGroupId.isBlank()) {
            RecipeOwner owner = AddonRecipeFiles.ownerOf("cooking_pot", recipe.getId());
            if (owner != null) {
                return new RecipeEdit(owner.file(), List.of(new YamlFileTransaction.SetValue(
                        List.of(owner.root(), owner.key()), buildCookingPotBody(recipe))));
            }
            if (plugin.getCookingPotRecipes().isExternalRecipe(recipe.getId())) {
                return new RecipeEdit(cookingPotFile(), List.of(
                        new YamlFileTransaction.SetValue(List.of(COOKING_POT_ROOT, recipe.getId()),
                                buildCookingPotBody(recipe)),
                        overrideEdit(cookingPotFile(), "cooking_pot", recipe.getId(), true)));
            }
        }
        return new RecipeEdit(cookingPotFile(), List.of(new YamlFileTransaction.SetValue(
                cookingPotPath(recipe.getId(), customGroupId), buildCookingPotBody(recipe))));
    }

    private RecipeEdit planDeleteCookingPotRecipe(String recipeId, String customGroupId) {
        if (customGroupId == null || customGroupId.isBlank()) {
            RecipeOwner owner = AddonRecipeFiles.ownerOf("cooking_pot", recipeId);
            if (owner != null) {
                return new RecipeEdit(owner.file(), List.of(new YamlFileTransaction.RemoveValue(
                        List.of(owner.root(), owner.key()))));
            }
            if (plugin.getCookingPotRecipes().isExternalRecipe(recipeId)) {
                return new RecipeEdit(cookingPotFile(), List.of(
                        new YamlFileTransaction.RemoveValue(List.of(COOKING_POT_ROOT, recipeId)),
                        overrideEdit(cookingPotFile(), "cooking_pot", recipeId, false)));
            }
        }
        return new RecipeEdit(cookingPotFile(), List.of(new YamlFileTransaction.RemoveValue(
                cookingPotPath(recipeId, customGroupId))));
    }

    private RecipeEdit planSaveCuttingBoardRecipe(CuttingBoardRecipe recipe) {
        RecipeOwner owner = AddonRecipeFiles.ownerOf("cutting_board", recipe.getId());
        if (owner != null) {
            return new RecipeEdit(owner.file(), List.of(new YamlFileTransaction.SetValue(
                    List.of(owner.root(), owner.key()), buildCuttingBoardBody(recipe))));
        }
        if (plugin.getCuttingBoardRecipes().isExternalRecipe(recipe.getId())) {
            return new RecipeEdit(cuttingBoardFile(), List.of(
                    new YamlFileTransaction.SetValue(List.of(CUTTING_BOARD_ROOT, recipe.getId()),
                            buildCuttingBoardBody(recipe)),
                    overrideEdit(cuttingBoardFile(), "cutting_board", recipe.getId(), true)));
        }
        return new RecipeEdit(cuttingBoardFile(), List.of(new YamlFileTransaction.SetValue(
                List.of(CUTTING_BOARD_ROOT, recipe.getId()), buildCuttingBoardBody(recipe))));
    }

    private RecipeEdit planDeleteCuttingBoardRecipe(String recipeId) {
        RecipeOwner owner = AddonRecipeFiles.ownerOf("cutting_board", recipeId);
        if (owner != null) {
            return new RecipeEdit(owner.file(), List.of(new YamlFileTransaction.RemoveValue(
                    List.of(owner.root(), owner.key()))));
        }
        if (plugin.getCuttingBoardRecipes().isExternalRecipe(recipeId)) {
            return new RecipeEdit(cuttingBoardFile(), List.of(
                    new YamlFileTransaction.RemoveValue(List.of(CUTTING_BOARD_ROOT, recipeId)),
                    overrideEdit(cuttingBoardFile(), "cutting_board", recipeId, false)));
        }
        return new RecipeEdit(cuttingBoardFile(), List.of(new YamlFileTransaction.RemoveValue(
                List.of(CUTTING_BOARD_ROOT, recipeId))));
    }

    /**
     * The overrides entry after adding or removing one id. An empty list removes the key, which is what a file
     * without overrides for that station looked like before.
     */
    private static YamlFileTransaction.Edit overrideEdit(File file, String station, String id, boolean enabled) {
        List<String> ids = new ArrayList<>(currentOverrides(file, station));
        ids.removeIf(id::equals);
        if (enabled) {
            ids.add(id);
        }
        List<String> path = List.of(EXTERNAL_OVERRIDES_ROOT, station);
        return ids.isEmpty() ? new YamlFileTransaction.RemoveValue(path)
                : new YamlFileTransaction.SetValue(path, List.copyOf(ids));
    }

    /** The ids the file lists as overrides for one station; only read for addon-registered recipes. */
    private static List<String> currentOverrides(File file, String station) {
        if (file == null || !file.isFile()) {
            return List.of();
        }
        try {
            return List.copyOf(YamlConfiguration.loadConfiguration(file)
                    .getStringList(EXTERNAL_OVERRIDES_ROOT + "." + station));
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    private List<String> cookingPotPath(String recipeId, String customGroupId) {
        if (customGroupId == null || customGroupId.isBlank()) {
            return List.of(COOKING_POT_ROOT, recipeId);
        }
        return List.of(CUSTOM_COOKING_POT_ROOT, customGroupId, recipeId);
    }

    private File cookingPotFile() {
        return new File(plugin.getDataFolder(), COOKING_POT_FILE);
    }

    private File cuttingBoardFile() {
        return new File(plugin.getDataFolder(), CUTTING_BOARD_FILE);
    }

    /** Applies a plan on the calling thread, the way an editor action has always done it. */
    private boolean applyPlan(RecipeEdit edit) {
        YamlFileTransaction.Outcome outcome = YamlFileTransaction.apply(edit.file().toPath(), edit.edits(),
                BukkitYamlValueWriter.INSTANCE, BukkitYamlValueWriter.check(edit.edits()));
        if (!outcome.written()) {
            I18n.logWarning("plugin.recipe_save_failed", "file", edit.file().getPath(),
                    "error", String.valueOf(outcome.reason()));
            return false;
        }
        plugin.reloadRecipeFiles();
        return true;
    }

    /** Hands a plan to the write queue; the completion runs on the player's thread after the file work. */
    private boolean submitPlan(RecipeEdit edit, Player player, Consumer<Boolean> completion) {
        EditorWriteQueue.EditPlan plan = new EditorWriteQueue.EditPlan(edit.file().toPath(), edit.edits(),
                BukkitYamlValueWriter.INSTANCE, BukkitYamlValueWriter.check(edit.edits()));
        return writeQueue.submit(plan,
                task -> {
                    try {
                        plugin.scheduler().runForEntity(player, task, () -> { });
                    } catch (RuntimeException retired) {
                        // The player or the plugin is gone: the file is written, there is no menu to update.
                    }
                },
                reason -> {
                    if (!plugin.isEnabled() || !player.isOnline()) {
                        return;
                    }
                    if (reason != null) {
                        I18n.logWarning("plugin.recipe_save_failed", "file", edit.file().getPath(), "error", reason);
                    } else {
                        // Recipes changed on disk, so the managers are rebuilt before the menu reports success.
                        plugin.reloadRecipeFiles();
                    }
                    completion.accept(reason == null);
                });
    }

    /** Editor writes still in flight; the shutdown reports them instead of dropping them silently. */
    public int pendingWrites() {
        return writeQueue.pendingWrites();
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
