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
    // 在重载时整体重建；通过 volatile 写操作整体发布，使得 Folia 区域/实体线程上的读取方
    // 永远不会观察到一个被清空了一半的 map。发布之后绝不可原地修改。
    private volatile Map<String, CuttingBoardRecipe> recipes = Map.of();
    private volatile List<CuttingBoardRecipe> sortedRecipes = List.of();

    public CuttingBoardRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadRecipes() {
        Map<String, CuttingBoardRecipe> newRecipes = new LinkedHashMap<>();
        RecipeFileLoader.loadRecipeSections(plugin, "recipes/cutting_board_recipes.yml", "cutting_board_recipes", "cutting board",
                (recipeId, section) -> newRecipes.put(recipeId, parseRecipe(recipeId, section)));

        List<CuttingBoardRecipe> newSorted;
        if (newRecipes.isEmpty()) {
            newSorted = List.of();
        } else {
            List<CuttingBoardRecipe> sorted = new ArrayList<>(newRecipes.values());
            sorted.sort(Comparator.comparingInt(CuttingBoardRecipe::getPriority).reversed()
                    .thenComparing(CuttingBoardRecipe::getId));
            newSorted = Collections.unmodifiableList(sorted);
        }

        this.recipes = newRecipes;
        this.sortedRecipes = newSorted;
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

        // 支持标量形式的 'tool:' 或复数形式的 'tools:' 列表（或两者同时存在）。'tools' 优先；
        // 'tool' 作为兜底。只要求两者中至少存在一个。
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
            String itemId = resultMap.get("item") != null ? resultMap.get("item").toString() : null;
            if (itemId == null) continue;
            
            int count = 1;
            if (resultMap.get("count") != null) {
                try {
                    count = Integer.parseInt(resultMap.get("count").toString());
                } catch (NumberFormatException e) {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().fine(I18n.formatConsole("recipe.invalid_count", "error", e.getMessage()));
                    }
                }
            }
            count = Math.max(1, count);
            
            double chance = 1.0d;
            if (resultMap.get("chance") != null) {
                try {
                    chance = Math.max(0.0d, Math.min(1.0d, Double.parseDouble(resultMap.get("chance").toString())));
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
        // ToolContext 只取决于工具本身，与具体配方无关；在循环外构建一次，避免每个配方都重复
        // 做 CraftEngine 标签/ID 查找和集合分配（切割是逐次点击的热路径）。
        ToolContext toolContext = ToolContext.from(plugin, tool, toolId);

        for (CuttingBoardRecipe recipe : sortedRecipes) {
            if (matchesInput(recipe, input) && matchesTool(recipe, toolContext)) {
                return recipe;
            }
        }

        return null;
    }

    public boolean hasAnyRecipeFor(ItemStack input) {
        if (input == null || input.getType().isAir()) return false;
        for (CuttingBoardRecipe recipe : sortedRecipes) {
            if (matchesInput(recipe, input)) return true;
        }
        return false;
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

        boolean matchesBase = vanillaId != null && (plugin.getCraftEngine().itemManager().vanillaItemIdsByTag(tagKey).stream()
                .anyMatch(k -> k.toString().equals(vanillaId))
                || ItemUtils.matchesVanillaItemTag(item, tagKey, excludedItems, excludedTags));
        if (!matchesBase) {
            return false;
        }
        return excludedTags.stream().noneMatch(excludedTag -> vanillaId != null &&
                plugin.getCraftEngine().itemManager().vanillaItemIdsByTag(excludedTag).stream()
                        .anyMatch(k -> k.toString().equals(vanillaId)));
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
