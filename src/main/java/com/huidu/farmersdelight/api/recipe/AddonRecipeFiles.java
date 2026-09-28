package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.item.FarmersDelightItems;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

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
 * <p>Prefer the pack route for static recipes: declare them under <pack>/configuration/ with the
 * cooking_recipes / cutting_recipes / special_recipes root keys and CraftEngine hands
 * them to FarmersDelight with no addon code at all. This helper remains for recipes that must be decided at
 * runtime (data another plugin feeds in, per-player or time-based content) and for addons that already ship
 * an editable file.
 *
 * <p>Reading a YAML file, registering each entry, and withdrawing the ids that disappeared is not addon
 * business — it is the recipe registry's. Before this existed every addon wrote its own copy, and the
 * copies drifted: one of them registered cutting-board results through the chance-less overload, so
 * every configured drop chance was silently promoted to guaranteed. Registration is keyed by
 * source (the same idea as registerCommonTags), so a reload replaces that source's
 * recipes wholesale and the caller keeps no state.
 *
 * <p>Recipe ids: a key that already contains ':' is used verbatim, otherwise it is prefixed
 * with namespace. Both conventions are in use across the existing addons; bare keys are
 * preferred for new files.
 *
 * <p>Call from FarmersDelightWarmupEvent and from your reload handler. Calls made before
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
        FarmersDelightApi api = FarmersDelightApi.get();
        Set<String> fresh = new LinkedHashSet<>();
        Map<String, RecipeOwner> owners = new LinkedHashMap<>();
        int skipped = 0;
        boolean awaiting = false;

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                skipped++;
                continue;
            }
            List<String> ingredients = ConfigSectionReader.optionalStringList(section, "ingredients");
            String resultId = ConfigSectionReader.optionalString(section, "result");
            if (ingredients.isEmpty() || resultId == null) {
                skipped++;
                continue;
            }
            ItemStack result = FarmersDelightItems.create(resultId);
            if (result == null) {
                skipped++;
                awaiting = true;
                warnUnresolved(plugin, api, resourcePath, key, "result", resultId);
                continue;
            }
            result.setAmount(Math.max(1, ConfigSectionReader.optionalInt(section, "result-count", 1)));

            String containerId = ConfigSectionReader.optionalString(section, "container");
            ItemStack container = containerId == null ? null : FarmersDelightItems.create(containerId);
            if (containerId != null && container == null) {
                skipped++;
                awaiting = true;
                warnUnresolved(plugin, api, resourcePath, key, "container", containerId);
                continue;
            }

            String id = recipeId(namespace, key);
            api.registerCookingPotRecipe(id, ingredients, container, result,
                    ConfigSectionReader.optionalDouble(section, "experience", 0.0),
                    ConfigSectionReader.optionalInt(section, "cook-time", 200),
                    ConfigSectionReader.optionalString(section, "category", "misc"));
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
        FarmersDelightApi api = FarmersDelightApi.get();
        Set<String> fresh = new LinkedHashSet<>();
        Map<String, RecipeOwner> owners = new LinkedHashMap<>();
        int skipped = 0;
        boolean awaiting = false;

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                skipped++;
                continue;
            }
            String input = section.getString("input");
            String tool = section.getString("tool");
            if (input == null || tool == null) {
                skipped++;
                continue;
            }
            // A tag input ("#namespace:tag") is resolved by FD at match time; a literal id must build now.
            if (!input.startsWith("#") && FarmersDelightItems.create(input) == null) {
                skipped++;
                awaiting = true;
                warnUnresolved(plugin, api, resourcePath, key, "input", input);
                continue;
            }

            List<ChanceResult> results = new ArrayList<>();
            boolean unresolved = false;
            for (Map<?, ?> entry : ConfigSectionReader.optionalMapList(section, "results")) {
                Object itemId = entry.get("item");
                if (itemId == null) {
                    continue;
                }
                ItemStack stack = FarmersDelightItems.create(itemId.toString());
                if (stack == null) {
                    unresolved = true;
                    warnUnresolved(plugin, api, resourcePath, key, "results", itemId.toString());
                    break;
                }
                Object count = entry.get("count");
                stack.setAmount(count == null ? 1 : Math.max(1, parseInt(count, 1)));
                Object chance = entry.get("chance");
                float rolled = chance == null ? 1.0f : clamp01(parseFloat(chance, 1.0f));
                results.add(new ChanceResult(stack, rolled));
            }
            if (unresolved) {
                skipped++;
                awaiting = true;
                continue;
            }
            if (results.isEmpty()) {
                skipped++;
                continue;
            }

            String id = recipeId(namespace, key);
            api.registerCuttingBoardRecipeWithChances(id, input, tool, results,
                    ConfigSectionReader.optionalString(section, "sound"));
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
        return YamlConfiguration.loadConfiguration(file).getConfigurationSection(rootKey);
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

    private static void warnUnresolved(Plugin plugin, FarmersDelightApi api, String resourcePath,
                                       String key, String field, String id) {
        // Silent before CraftEngine finishes loading: at that point an unresolved id is expected, and a
        // warning per recipe per reload would bury the ones that matter.
        if (api.isContentLoaded()) {
            plugin.getLogger().warning(FarmersDelightApi.consoleMessage("plugin.item_not_found",
                    "path", resourcePath + "." + key + "." + field, "id", id));
        }
    }

    private static int parseInt(Object value, int fallback) {
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(Object value, float fallback) {
        try {
            return Float.parseFloat(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
