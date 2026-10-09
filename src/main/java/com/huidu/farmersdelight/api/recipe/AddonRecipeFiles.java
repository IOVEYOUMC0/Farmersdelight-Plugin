package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import java.io.IOException;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Loads an addon's own recipes/*.yml into FarmersDelight and keeps the bookkeeping.
 *
 *
 * Prefer the pack route for static recipes: declare them under <pack>/configuration/ with the
 * cooking_recipes / cutting_recipes / special_recipes root keys and CraftEngine hands
 * them to FarmersDelight with no addon code at all. This helper remains for recipes that must be decided at
 * runtime (data another plugin feeds in, per-player or time-based content) and for addons that already ship
 * an editable file.
 *
 *
 * Reading a YAML file, registering each entry, and withdrawing the ids that disappeared is not addon
 * business — it is the recipe registry's. Before this existed every addon wrote its own copy, and the
 * copies drifted: one of them registered cutting-board results through the chance-less overload, so
 * every configured drop chance was silently promoted to guaranteed. Registration is keyed by
 * source (the same idea as registerCommonTags), so a reload replaces that source's
 * recipes wholesale and the caller keeps no state.
 *
 *
 * Recipe ids: a key that already contains ':' is used verbatim, otherwise it is prefixed
 * with namespace. Both conventions are in use across the existing addons; bare keys are
 * preferred for new files.
 *
 *
 * Call from FarmersDelightWarmupEvent and from your reload handler. Calls made before
 * CraftEngine has built its items resolve nothing and deliberately keep the previously registered set,
 * so a later reload retries instead of leaving the station empty.
 */
public final class AddonRecipeFiles {

    /** File ownership for recipes loaded through this helper; used by FD's generic editor. */
    public record RecipeOwner(File file, String root, String key, String source) {
        public String yamlPath() {
            return root + "." + key;
        }
    }

    /**
     * @param registered    ids registered by this call
     * @param skipped       entries that could not be registered (bad shape or unresolved items)
     * @param changed       true when the registered set differs from the previous call for this source
     * @param awaitingItems true when something was skipped only because CraftEngine items are not built
     *                      yet; stale cleanup is deferred in that case
     */
    public record LoadResult(int registered, int skipped, boolean changed, boolean awaitingItems) {

        public boolean isEmpty() {
            return registered == 0;
        }
    }

    private static final Map<String, Set<String>> COOKING_POT_IDS = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> CUTTING_BOARD_IDS = new ConcurrentHashMap<>();
    private static final Map<String, RecipeOwner> COOKING_POT_OWNERS = new ConcurrentHashMap<>();
    private static final Map<String, RecipeOwner> CUTTING_BOARD_OWNERS = new ConcurrentHashMap<>();

    private static final Map<String, Runnable> RELOADERS = new ConcurrentHashMap<>();

    private AddonRecipeFiles() {
    }

    /** Loads recipes/cooking_pot_recipes.yml (or any file with a cooking_pot_recipes root). */
    public static LoadResult loadCookingPotRecipes(Plugin plugin, String source, String resourcePath,
                                                   String namespace) {
        if (!validLoadArgs(plugin, source, resourcePath)) {
            return emptyResult();
        }
        ConfigurationSection root = readRoot(plugin, resourcePath, "cooking_pot_recipes");
        if (root == null) {
            return new LoadResult(0, 0, false, false);
        }
        RELOADERS.put("cooking_pot:" + source,
                () -> loadCookingPotRecipes(plugin, source, resourcePath, namespace));
        FarmersDelightApi api = FarmersDelightApi.get();
        Set<String> fresh = new LinkedHashSet<>();
        Map<String, RecipeOwner> owners = new LinkedHashMap<>();
        int skipped = 0;
        boolean awaiting = false;

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            String id = recipeId(namespace, key);
            if (section == null || !api.registerRecipeConfiguration("cooking_pot", id, section)) {
                skipped++;
                awaiting |= !api.isContentLoaded();
                continue;
            }
            fresh.add(id);
            owners.put(id, new RecipeOwner(new File(plugin.getDataFolder(), resourcePath),
                    "cooking_pot_recipes", key, source));
        }
        LoadResult result = commit(COOKING_POT_IDS, source, fresh, skipped, awaiting,
                api::unregisterCookingPotRecipe);
        replaceOwners(COOKING_POT_OWNERS, COOKING_POT_IDS.getOrDefault(source, Set.of()), owners,
                source, result.awaitingItems());
        return result;
    }

    /**
     * Loads recipes/cutting_board_recipes.yml. Results always go through the chance-aware
     * registration; a result without a chance is a guaranteed one.
     */
    public static LoadResult loadCuttingBoardRecipes(Plugin plugin, String source, String resourcePath,
                                                      String namespace) {
        if (!validLoadArgs(plugin, source, resourcePath)) {
            return emptyResult();
        }
        ConfigurationSection root = readRoot(plugin, resourcePath, "cutting_board_recipes");
        if (root == null) {
            return new LoadResult(0, 0, false, false);
        }
        RELOADERS.put("cutting_board:" + source,
                () -> loadCuttingBoardRecipes(plugin, source, resourcePath, namespace));
        FarmersDelightApi api = FarmersDelightApi.get();
        Set<String> fresh = new LinkedHashSet<>();
        Map<String, RecipeOwner> owners = new LinkedHashMap<>();
        int skipped = 0;
        boolean awaiting = false;

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            String id = recipeId(namespace, key);
            if (section == null || !api.registerRecipeConfiguration("cutting_board", id, section)) {
                skipped++;
                awaiting |= !api.isContentLoaded();
                continue;
            }
            fresh.add(id);
            owners.put(id, new RecipeOwner(new File(plugin.getDataFolder(), resourcePath),
                    "cutting_board_recipes", key, source));
        }
        LoadResult result = commit(CUTTING_BOARD_IDS, source, fresh, skipped, awaiting,
                api::unregisterCuttingBoardRecipe);
        replaceOwners(CUTTING_BOARD_OWNERS, CUTTING_BOARD_IDS.getOrDefault(source, Set.of()), owners,
                source, result.awaitingItems());
        return result;
    }

    /** Withdraws every recipe this source registered. Call from the addon's onDisable. */
    public static void unregisterAll(String source) {
        RELOADERS.remove("cooking_pot:" + source);
        RELOADERS.remove("cutting_board:" + source);
        if (source == null || source.isBlank()) {
            return;
        }
        FarmersDelightApi api = FarmersDelightApi.get();
        Set<String> pot = COOKING_POT_IDS.remove(source);
        if (pot != null) {
            pot.forEach(api::unregisterCookingPotRecipe);
        }
        removeOwners(COOKING_POT_OWNERS, source);
        Set<String> board = CUTTING_BOARD_IDS.remove(source);
        if (board != null) {
            board.forEach(api::unregisterCuttingBoardRecipe);
        }
        removeOwners(CUTTING_BOARD_OWNERS, source);
    }

    /** Reloads owned files before the main managers publish an editor save. */
    @ApiStatus.Internal
    public static void reloadOwnedFiles() {
        List.copyOf(RELOADERS.values()).forEach(Runnable::run);
    }

    /** Returns the writable source file for a recipe loaded by this helper, or null for API-only ids. */
    public static RecipeOwner ownerOf(String station, String id) {
        if (station == null || id == null) {
            return null;
        }
        return switch (station) {
            case "cooking_pot" -> COOKING_POT_OWNERS.get(id);
            case "cutting_board" -> CUTTING_BOARD_OWNERS.get(id);
            default -> null;
        };
    }

    private static void replaceOwners(Map<String, RecipeOwner> owners, Set<String> liveIds,
                                      Map<String, RecipeOwner> fresh, String source, boolean awaiting) {
        if (!awaiting) {
            owners.entrySet().removeIf(entry -> source.equals(entry.getValue().source())
                    && !liveIds.contains(entry.getKey()));
        }
        owners.putAll(fresh);
    }

    private static void removeOwners(Map<String, RecipeOwner> owners, String source) {
        owners.entrySet().removeIf(entry -> source.equals(entry.getValue().source()));
    }

    private static ConfigurationSection readRoot(Plugin plugin, String resourcePath, String rootKey) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            try {
                plugin.saveResource(resourcePath, false);
            } catch (IllegalArgumentException e) {
                return null; // the addon ships no such file
            }
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            ConfigurationSection root = yaml.getConfigurationSection(rootKey);
            if (root == null && yaml.isSet(rootKey)) {
                throw new InvalidConfigurationException(rootKey + " must be a section");
            }
            return root == null ? yaml.createSection(rootKey) : root;
        } catch (IOException | InvalidConfigurationException failure) {
            plugin.getLogger().log(Level.WARNING, "Cannot load recipe file; retaining the registered set: " + file, failure);
            return null;
        }
    }

    private static boolean validLoadArgs(Plugin plugin, String source, String resourcePath) {
        return plugin != null && source != null && !source.isBlank()
                && resourcePath != null && !resourcePath.isBlank();
    }

    private static LoadResult emptyResult() {
        return new LoadResult(0, 0, false, false);
    }

    private static LoadResult commit(Map<String, Set<String>> registry, String source, Set<String> fresh,
                                     int skipped, boolean awaiting,
                                     Consumer<String> unregister) {
        Set<String> previous = registry.getOrDefault(source, Set.of());
        // CraftEngine can expose items in batches. Keep every previous id while any entry is waiting for
        // an item; otherwise a partial pass would unregister recipes that are still valid and make the
        // station appear to lose recipes until the next reload.
        if (awaiting) {
            Set<String> merged = new LinkedHashSet<>(previous);
            merged.addAll(fresh);
            boolean changed = !merged.equals(previous);
            registry.put(source, Set.copyOf(merged));
            return new LoadResult(merged.size(), skipped, changed, true);
        }
        for (String stale : previous) {
            if (!fresh.contains(stale)) {
                unregister.accept(stale);
            }
        }
        boolean changed = !fresh.equals(previous);
        registry.put(source, Set.copyOf(fresh));
        return new LoadResult(fresh.size(), skipped, changed, awaiting);
    }

    private static String recipeId(String namespace, String key) {
        return key.indexOf(':') >= 0 || namespace == null || namespace.isBlank()
                ? key
                : namespace + ":" + key;
    }

}
