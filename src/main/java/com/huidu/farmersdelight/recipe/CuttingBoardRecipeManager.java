package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class CuttingBoardRecipeManager {

    private final FarmersDelightPlugin plugin;
    // Rebuilt as a whole on reload; published as a whole via volatile writes so readers on Folia
    // region/entity threads never observe a half-cleared map. Never mutate in place after publishing.
    private volatile Map<String, CuttingBoardRecipe> recipes = Map.of();
    private volatile List<CuttingBoardRecipe> sortedRecipes = List.of();
    // Input index for matchRecipe — narrows the candidate set without changing match order. Splits recipes
    // into two buckets at load time:
    //   - byInputItemId: Item-typed recipes keyed by their input's literal item id (e.g. "minecraft:carrot")
    //   - tagInputRecipeIds: every Tag-typed recipe's id (these always need a full matchesTaggedItem check
    //     because vanilla tags aren't surfaced through ItemUtils.getItemTagIds, so we can't index them)
    // Query gathers candidates = byInputItemId[input.ids] ∪ tagInputRecipeIds, then iterates sortedRecipes
    // filtered by that set — sortedRecipes order (priority + id) preserved exactly.
    private volatile Map<String, Set<String>> byInputItemId = Map.of();
    private volatile Set<String> tagInputRecipeIds = Set.of();
    // Recipes registered at runtime by addons via the public API; kept separate so they survive a
    // /fd reload (which rebuilds the file-backed map) and merged into the published map in loadRecipes().
    private final Map<String, CuttingBoardRecipe> externalRecipes = new java.util.concurrent.ConcurrentHashMap<>();
    // External (un)register republishing is coalesced to the next tick (one loadRecipes() per batch).
    private volatile boolean externalRepublishScheduled = false;
    // Caches CraftEngine's vanillaItemIdsByTag result per tag so matchesTaggedItem doesn't re-stream
    // the full vanilla tag membership on every cutting click (mirrors CookingPotRecipeManager).
    // Cleared in loadRecipes(). Concurrent: read on Folia region/entity threads.
    private final Map<Key, Set<String>> vanillaItemIdsByTagCache = new java.util.concurrent.ConcurrentHashMap<>();

    public CuttingBoardRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadRecipes() {
        Map<String, CuttingBoardRecipe> newRecipes = new LinkedHashMap<>();
        RecipeFileLoader.loadRecipeSections(plugin, "recipes/cutting_board_recipes.yml", "cutting_board_recipes", "cutting board",
                (recipeId, section) -> newRecipes.put(recipeId, parseRecipe(recipeId, section)));

        // Merge addon-registered recipes last so they survive reloads (and override file ids on clash).
        newRecipes.putAll(externalRecipes);

        List<CuttingBoardRecipe> newSorted;
        if (newRecipes.isEmpty()) {
            newSorted = List.of();
        } else {
            List<CuttingBoardRecipe> sorted = new ArrayList<>(newRecipes.values());
            sorted.sort(Comparator.comparingInt(CuttingBoardRecipe::getPriority).reversed()
                    .thenComparing(CuttingBoardRecipe::getId));
            newSorted = Collections.unmodifiableList(sorted);
        }

        Map<String, Set<String>> newByItemId = new HashMap<>();
        Set<String> newTagInputRecipeIds = new HashSet<>();
        for (CuttingBoardRecipe recipe : newSorted) {
            RecipeIngredient ingredient = recipe.getInput();
            if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
                String key = itemIngredient.key().toString().toLowerCase(Locale.ROOT);
                newByItemId.computeIfAbsent(key, k -> new HashSet<>()).add(recipe.getId());
            } else if (ingredient instanceof RecipeIngredient.Tag) {
                // Tag-typed: can't index by tag because vanilla tags aren't surfaced via getItemTagIds.
                // Keep them all in tagInputRecipeIds so candidate set always includes them.
                newTagInputRecipeIds.add(recipe.getId());
            }
        }
        Map<String, Set<String>> frozenByItemId = new HashMap<>(newByItemId.size());
        for (Map.Entry<String, Set<String>> e : newByItemId.entrySet()) {
            frozenByItemId.put(e.getKey(), Set.copyOf(e.getValue()));
        }

        this.recipes = newRecipes;
        this.sortedRecipes = newSorted;
        this.byInputItemId = Map.copyOf(frozenByItemId);
        this.tagInputRecipeIds = Set.copyOf(newTagInputRecipeIds);
        vanillaItemIdsByTagCache.clear();
        // Invalidate the recipe-list GUI display cache: this republish path (incl. addon register/
        // unregister) bypasses RecipeViewGui.clearConfigCache.
        com.huidu.farmersdelight.gui.RecipeViewGui.clearRecipeDisplayCache();
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

    private CuttingBoardRecipe parseRecipe(String id, ConfigurationSection section) {
        String inputStr = section.getString("input");
        if (inputStr == null) {
            throw new IllegalArgumentException("Recipe must have an input");
        }

        RecipeIngredient input = parseIngredient(inputStr);
        ItemStack inputDisplay = createDisplayItem(input);
        if (inputDisplay == null) {
            throw new IllegalArgumentException("Invalid input ingredient: " + inputStr);
        }

        // Support a scalar 'tool:' or a plural 'tools:' list (or both). 'tools' takes precedence;
        // 'tool' is the fallback. Only requires at least one of the two.
        String toolStr = section.getString("tool");
        List<String> toolStrings = section.getStringList("tools");
        if (toolStrings.isEmpty() && toolStr != null && !toolStr.isBlank()) {
            toolStrings = Collections.singletonList(toolStr);
        }
        if (toolStrings.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have a tool");
        }

        List<CuttingBoardRecipe.ToolRequirement> tools = new ArrayList<>();
        for (String tool : toolStrings) {
            tools.add(parseTool(tool));
        }

        List<CuttingBoardRecipe.ResultEntry> results = new ArrayList<>();
        
        List<Map<?, ?>> resultsList = section.getMapList("results");
        for (Map<?, ?> resultMap : resultsList) {
            // Cache each .get(...) once — Map.get is O(1) but allocates an entry traversal under
            // contention and the resultsList loop runs per-recipe on every config (re)load.
            Object itemValue = resultMap.get("item");
            if (itemValue == null) continue;
            String itemId = itemValue.toString();

            int count = 1;
            Object countValue = resultMap.get("count");
            if (countValue != null) {
                try {
                    count = Integer.parseInt(countValue.toString());
                } catch (NumberFormatException e) {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().fine(I18n.formatConsole("recipe.invalid_count", "error", e.getMessage()));
                    }
                }
            }
            count = Math.max(1, count);

            double chance = 1.0d;
            Object chanceValue = resultMap.get("chance");
            if (chanceValue != null) {
                try {
                    chance = Math.max(0.0d, Math.min(1.0d, Double.parseDouble(chanceValue.toString())));
                } catch (NumberFormatException e) {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().fine(I18n.formatConsole("recipe.invalid_chance", "error", e.getMessage()));
                    }
                }
            }
            
            ItemStack result = createItem(itemId);
            if (result != null) {
                result.setAmount(count);
                results.add(new CuttingBoardRecipe.ResultEntry(result, chance));
            }
        }

        if (results.isEmpty()) {
            String resultStr = section.getString("result");
            if (resultStr != null) {
                ItemStack result = createItem(resultStr);
                if (result != null) {
                    int count = Math.max(1, section.getInt("amount", section.getInt("count", 1)));
                    double chance = Math.max(0.0d, Math.min(1.0d, section.getDouble("chance", 1.0d)));
                    result.setAmount(count);
                    results.add(new CuttingBoardRecipe.ResultEntry(result, chance));
                }
            }
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one valid result");
        }

        String sound = normalizeSound(section.getString("sound", Constants.SOUND_CUTTING_BOARD_KNIFE));
        int priority = section.getInt("priority", 0);
        return new CuttingBoardRecipe(id, input, inputDisplay, tools, results, sound, priority);
    }

    private String normalizeSound(String soundStr) {
        if (soundStr == null || soundStr.isBlank()) {
            return Constants.SOUND_CUTTING_BOARD_KNIFE;
        }

        String normalized = soundStr.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains(":")) {
            return normalized;
        }
        return "minecraft:" + normalized;
    }

    private RecipeIngredient parseIngredient(String str) {
        return RecipeParsingSupport.parseSimpleItemOrTag(str);
    }

    private CuttingBoardRecipe.ToolRequirement parseTool(String str) {
        RecipeParsingSupport.ParsedKey parsed = RecipeParsingSupport.parseKeyWithExclusions(str, "tool");
        return new CuttingBoardRecipe.ToolRequirement(parsed.key(), parsed.excludedItems(), parsed.excludedTags());
    }

    private ItemStack createDisplayItem(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return createItem(itemIngredient.key().toString());
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            for (var candidate : plugin.getCraftEngine().itemManager().itemIdsByTag(tagIngredient.key())) {
                ItemStack item = createItem(candidate.key().toString());
                if (item != null && !item.getType().isAir()) {
                    return item;
                }
            }
        }

        return null;
    }

    private ItemStack createItem(String itemId) {
        return ItemUtils.createItem(itemId);
    }

    public CuttingBoardRecipe matchRecipe(ItemStack input, ItemStack tool) {
        String toolId = ItemUtils.getCustomItemId(tool);
        // ToolContext depends only on the tool itself, not on any recipe; build it once outside the loop to avoid
        // repeating CraftEngine tag/ID lookups and set allocations per recipe (cutting is a per-click hot path).
        ToolContext toolContext = ToolContext.from(plugin, tool, toolId);

        Set<String> candidates = candidateRecipeIds(input);
        for (CuttingBoardRecipe recipe : sortedRecipes) {
            if (candidates != null && !candidates.contains(recipe.getId())) {
                continue;
            }
            if (matchesInput(recipe, input) && matchesTool(recipe, toolContext)) {
                return recipe;
            }
        }

        return null;
    }

    public boolean hasAnyRecipeFor(ItemStack input) {
        if (input == null || input.getType().isAir()) return false;
        Set<String> candidates = candidateRecipeIds(input);
        for (CuttingBoardRecipe recipe : sortedRecipes) {
            if (candidates != null && !candidates.contains(recipe.getId())) {
                continue;
            }
            if (matchesInput(recipe, input)) return true;
        }
        return false;
    }

    /** Candidate recipe ids whose declared input could match input: Item-typed recipes whose
     *  literal key matches one of the input's item ids, plus EVERY Tag-typed recipe (their tag may resolve
     *  to a vanilla item tag that isn't surfaced via getItemTagIds, so we don't try to filter them).
     *  Returns null when the index is empty / input is air — caller iterates sortedRecipes unfiltered. */
    private Set<String> candidateRecipeIds(ItemStack input) {
        Map<String, Set<String>> byId = this.byInputItemId;
        Set<String> tagIds = this.tagInputRecipeIds;
        if ((byId.isEmpty() && tagIds.isEmpty()) || input == null || input.getType().isAir()) {
            return null;
        }
        Set<String> result = null;
        for (String itemId : ItemUtils.getItemIds(input)) {
            Set<String> bucket = byId.get(itemId.toLowerCase(Locale.ROOT));
            if (bucket == null || bucket.isEmpty()) continue;
            if (result == null) result = new HashSet<>(bucket);
            else result.addAll(bucket);
        }
        if (!tagIds.isEmpty()) {
            if (result == null) result = new HashSet<>(tagIds);
            else result.addAll(tagIds);
        }
        // No bucket hit and no tag-typed recipes: empty Set (not null) → matchRecipe loop early-skips every recipe.
        return result == null ? Set.of() : result;
    }

    private boolean matchesInput(CuttingBoardRecipe recipe, ItemStack input) {
        if (input == null || input.getType().isAir()) {
            return false;
        }

        RecipeIngredient ingredient = recipe.getInput();
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return ItemUtils.matchesItemId(input, itemIngredient.key());
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return matchesTaggedItem(input, tagIngredient.key(), tagIngredient.excludedItems(), tagIngredient.excludedTags());
        }

        return false;
    }

    private boolean matchesTool(CuttingBoardRecipe recipe, ToolContext toolContext) {
        for (CuttingBoardRecipe.ToolRequirement toolRequirement : recipe.getTools()) {
            if (matchesToolRequirement(toolRequirement, toolContext)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesToolRequirement(CuttingBoardRecipe.ToolRequirement toolRequirement, ToolContext toolContext) {
        if (!toolContext.hasTool()) {
            return false;
        }

        if (matchesToolFallback(toolRequirement.key(), toolContext)) {
            return !isExcludedTool(toolContext, toolRequirement);
        }

        if (toolContext.matchesItemKey(toolRequirement.key())) {
            return !isExcludedTool(toolContext, toolRequirement);
        }

        if (toolContext.matchesCustomTag(toolRequirement.key())) {
            return !isExcludedTool(toolContext, toolRequirement);
        }

        return toolContext.matchesVanillaTag(toolRequirement.key()) && !isExcludedTool(toolContext, toolRequirement);
    }

    private boolean matchesToolFallback(Key toolKey, ToolContext toolContext) {
        String toolKeyStr = toolKey.toString();
        return (Constants.TAG_KNIVES.equalsIgnoreCase(toolKeyStr) && toolContext.knife())
                || ((Constants.TAG_AXES.equalsIgnoreCase(toolKeyStr)
                || Constants.ACTION_AXE_DIG.equalsIgnoreCase(toolKeyStr)
                || Constants.ACTION_AXE_STRIP.equalsIgnoreCase(toolKeyStr)) && toolContext.axe())
                || (Constants.ACTION_PICKAXE_DIG.equalsIgnoreCase(toolKeyStr) && toolContext.pickaxe())
                || ((Constants.TAG_SHOVELS.equalsIgnoreCase(toolKeyStr)
                || Constants.ACTION_SHOVEL_DIG.equalsIgnoreCase(toolKeyStr)) && toolContext.shovel())
                || (Constants.ITEM_SHEARS.equalsIgnoreCase(toolKeyStr) && toolContext.shears());
    }

    private boolean isExcludedTool(ToolContext toolContext, CuttingBoardRecipe.ToolRequirement toolRequirement) {
        Key itemKey = toolContext.itemKey();
        if (itemKey == null) {
            return false;
        }
        if (toolRequirement.excludedItems().contains(itemKey)) {
            return true;
        }

        if (!toolContext.customTags().isEmpty()
                && toolRequirement.excludedTags().stream().anyMatch(toolContext.customTags()::contains)) {
            return true;
        }

        return toolRequirement.excludedTags().stream().anyMatch(toolContext::matchesVanillaTag);
    }

    private boolean matchesTaggedItem(ItemStack item, Key tagKey, Set<Key> excludedItems, Set<Key> excludedTags) {
        String customId = ItemUtils.getCustomItemId(item);
        String vanillaId = ItemUtils.getVanillaMaterialItemId(item);

        if (excludedItems.stream().anyMatch(excluded -> ItemUtils.matchesItemId(item, excluded))) {
            return false;
        }

        Set<String> itemTags = ItemUtils.getItemTagIds(item);
        if (itemTags.contains(tagKey.toString())) {
            return excludedTags.stream().map(Key::toString).noneMatch(itemTags::contains);
        }

        boolean matchesBase = vanillaId != null && (getVanillaItemIdsByTag(tagKey).contains(vanillaId)
                || ItemUtils.matchesVanillaItemTag(item, tagKey, excludedItems, excludedTags));
        if (!matchesBase) {
            return false;
        }
        return excludedTags.stream().noneMatch(excludedTag ->
                vanillaId != null && getVanillaItemIdsByTag(excludedTag).contains(vanillaId));
    }

    public Map<String, CuttingBoardRecipe> getRecipes() {
        return Collections.unmodifiableMap(recipes);
    }

    public List<CuttingBoardRecipe> getSortedRecipes() {
        return sortedRecipes;
    }

    public int getRecipeCount() {
        return recipes.size();
    }

    public CuttingBoardRecipe getRecipe(String id) {
        return recipes.get(id);
    }

    public void reload() {
        loadRecipes();
    }

    /**
     * Registers (or replaces) an addon-supplied cutting-board recipe at runtime and republishes. Retained
     * across /fd reload. inputSpec/toolSpec use the recipe-file syntax ("ns:id" or
     * "#ns:tag"); each result stack carries its own amount and is dropped with 100% chance.
     */
    public void registerExternalRecipe(String id, String inputSpec, String toolSpec,
                                       List<ItemStack> results, String sound) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Recipe id is required");
        }
        if (inputSpec == null || inputSpec.isBlank()) {
            throw new IllegalArgumentException("Recipe must have an input");
        }
        if (toolSpec == null || toolSpec.isBlank()) {
            throw new IllegalArgumentException("Recipe must have a tool");
        }
        RecipeIngredient input = parseIngredient(inputSpec);
        ItemStack inputDisplay = createDisplayItem(input);
        if (inputDisplay == null) {
            throw new IllegalArgumentException("Invalid input ingredient: " + inputSpec);
        }
        List<CuttingBoardRecipe.ResultEntry> entries = new ArrayList<>();
        if (results != null) {
            for (ItemStack result : results) {
                if (result != null && !result.getType().isAir()) {
                    entries.add(new CuttingBoardRecipe.ResultEntry(result.clone(), 1.0d));
                }
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one valid result");
        }
        List<CuttingBoardRecipe.ToolRequirement> tools = List.of(parseTool(toolSpec));
        CuttingBoardRecipe recipe = new CuttingBoardRecipe(id, input, inputDisplay, tools, entries,
                normalizeSound(sound), 0);
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
        }, 1L);
    }

    private record ToolContext(
            ItemStack tool,
            String customId,
            String vanillaId,
            Key itemKey,
            Set<Key> customTags,
            boolean knife,
            boolean axe,
            boolean pickaxe,
            boolean shovel,
            boolean shears
    ) {
        private static ToolContext from(FarmersDelightPlugin plugin, ItemStack tool, String toolId) {
            if (tool == null || tool.getType().isAir()) {
                return new ToolContext(tool, null, null, null, Set.of(), false, false, false, false, false);
            }

            String vanillaId = ItemUtils.getVanillaMaterialItemId(tool);
            Key itemKey = toolId != null
                    ? Key.of(toolId)
                    : (vanillaId != null ? Key.of(vanillaId) : null);
            Set<Key> customTags = ItemUtils.getItemTagIds(tool).stream()
                    .map(Key::of)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new ToolContext(
                    tool,
                    toolId,
                    vanillaId,
                    itemKey,
                    customTags,
                    isKnifeTool(plugin, toolId),
                    isMaterialSuffix(tool, "_AXE"),
                    isMaterialSuffix(tool, "_PICKAXE"),
                    isMaterialSuffix(tool, "_SHOVEL"),
                    tool.getType() == Material.SHEARS
            );
        }

        private static boolean isKnifeTool(FarmersDelightPlugin plugin, String toolId) {
            if (toolId == null) {
                return false;
            }

            return plugin.isKnifeItemId(toolId);
        }

        private static boolean isMaterialSuffix(ItemStack tool, String suffix) {
            return tool != null && tool.getType().name().endsWith(suffix);
        }

        private boolean hasTool() {
            return tool != null && !tool.getType().isAir();
        }

        private boolean matchesItemKey(Key key) {
            return ItemUtils.matchesItemId(tool, key);
        }

        private boolean matchesCustomTag(Key key) {
            return !customTags.isEmpty() && customTags.contains(key);
        }

        private boolean matchesVanillaTag(Key key) {
            return vanillaId != null && ItemUtils.matchesVanillaItemTag(tool, key, Set.of(), Set.of());
        }
    }
}
