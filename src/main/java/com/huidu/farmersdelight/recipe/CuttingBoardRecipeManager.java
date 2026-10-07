package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles;
import com.huidu.farmersdelight.api.recipe.IngredientMatchMemo;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.MMOItemsCompat;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CuttingBoardRecipeManager {

    private final FarmersDelightPlugin plugin;
    // Rebuilt as a whole on reload; published as a whole via volatile writes so readers on Folia
    // region/entity threads never observe a half-cleared map. Never mutate in place after publishing.
    private volatile Map<String, CuttingBoardRecipe> recipes = Map.of();
    // Recipes whose winning definition came from a CraftEngine pack section; published with the maps above
    // so the startup summary can tell the plugin's own file, pack content and runtime registrations apart.
    private volatile int packRecipeCount;
    private volatile List<CuttingBoardRecipe> sortedRecipes = List.of();
    // Input index for matchRecipe — narrows the candidate set without changing match order. Splits recipes
    // into two buckets at load time:
    //   - byInputItemId: Item-typed recipes keyed by their input's literal item id (e.g. "minecraft:carrot")
    //   - tagInputRecipeIds: every Tag-typed recipe's id (these always need a full matchesTaggedItem check
    //     because vanilla tags aren't surfaced through ItemUtils.getItemTagIds, so they cannot be indexed)
    // Query gathers candidates = byInputItemId[input.ids] ∪ tagInputRecipeIds, then iterates sortedRecipes
    // filtered by that set — sortedRecipes order (priority + id) preserved exactly.
    private volatile Map<String, Set<String>> byInputItemId = Map.of();
    private volatile Set<String> tagInputRecipeIds = Set.of();
    // Reverse index result item id -> producing recipes, built at load time and published as a whole
    // (single volatile write) so API cross-reference / GUI can answer "which recipes produce X" in O(1).
    private volatile Map<String, List<CuttingBoardRecipe>> resultToRecipes = Map.of();
    private volatile List<CuttingBoardRecipe.ToolRequirement> toolRequirements = List.of();
    // Recipes registered at runtime by addons via the public API; kept separate so they survive a
    // /fd reload (which rebuilds the file-backed map) and merged into the published map in loadRecipes().
    private final Map<String, CuttingBoardRecipe> externalRecipes = new ConcurrentHashMap<>();
    // External (un)register republishing is coalesced to the next tick (one loadRecipes() per batch).
    private volatile boolean externalRepublishScheduled = false;
    // Caches CraftEngine's vanillaItemIdsByTag result per tag so matchesTaggedItem doesn't re-stream
    // the full vanilla tag membership on every cutting click (mirrors CookingPotRecipeManager).
    // Cleared in loadRecipes(). Concurrent: read on Folia region/entity threads.
    private final VanillaTagItemIdCache vanillaItemIdsByTagCache;

    public CuttingBoardRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.vanillaItemIdsByTagCache = new VanillaTagItemIdCache(plugin);
    }

    public void loadRecipes() {
        // Recompile the packs' advanced tag groups up front: recipe parsing resolves an advtag: ingredient
        // against this snapshot, and every load path (startup, /fd reload, external republish) runs through
        // here, so a recipe can never be read against the groups of an earlier load.
        RecipeParsingSupport.setAdvancedTagGroups(plugin.advancedTagGroups());
        YamlConfiguration mainConfig = RecipeFileLoader.loadRecipeFile(plugin, "recipes/cutting_board_recipes.yml");
        if (mainConfig == null) {
            // Unreadable file (the loader already warned): keep the recipes published last rather than
            // rebuilding from an empty configuration.
            return;
        }

        // Every source of this reload is queued into one registration round, and the round is what publishes:
        // entries reach the buffers only as it advances, so the derived indexes and the published maps are
        // built after the last entry. Matching reads those indexes and never the buffers, so a set published
        // while the round was still running would leave every entry past the first slice without a recipe.
        PendingLoad pending = new PendingLoad(mainConfig);
        List<RecipeRegistrationRound.Segment> segments = new ArrayList<>();
        RecipeRegistrationRound.Segment ownFile = RecipeFileLoader.recipeSectionSegment(plugin, mainConfig,
                "cutting_board_recipes", "cutting board", "recipes/cutting_board_recipes.yml",
                pending::putOwnFile);
        if (ownFile != null) {
            segments.add(ownFile);
        }

        // Recipes a CraftEngine pack declares under cutting_recipes. Queued after the plugin's own file so a
        // pack can never silently replace a built-in recipe; the runtime registrations are merged in the publish
        // below, so an explicit registration still wins on an id clash. CraftEngine read the files; see
        // PackSections.
        for (PackSections.Section packSection : plugin.packSectionsOf(PackSection.CUTTING_BOARD)) {
            RecipeRegistrationRound.Segment pack = RecipeFileLoader.recipeSectionSegment(plugin,
                    packSection.yaml(), PackSection.CUTTING_BOARD.rootKey(),
                    "cutting board [" + packSection.source() + "]",
                    packSection.source(),
                    (recipeId, section) -> pending.putPackFile(recipeId, section, packSection.source()));
            if (pack != null) {
                segments.add(pack);
            }
        }

        plugin.recipeRegistrations().start(this, segments,
                RecipeRegistrationBudget.forLoad(recipes.size(), plugin.recipeRegistrationBudget()),
                () -> publishLoadedSet(pending));
    }

    /** The plugin's own file parses under this source; every pack section passes its own. */
    private static final String OWN_FILE_SOURCE = "recipes/cutting_board_recipes.yml";

    private final ParsedRecipeCache<CuttingBoardRecipe> parseCache = new ParsedRecipeCache<>();

    /** One entry's parse, reused while the content generation and the entry's own values are unchanged. */
    private CuttingBoardRecipe parseCached(String source, String recipeId, ConfigurationSection section) {
        return parseCache.parse(RecipeContentEpoch.current(), source, recipeId,
                String.valueOf(section.getValues(true)), () -> parseRecipe(recipeId, section));
    }

    /**
     * The buffers one reload round fills before anything is published: the entries the round has registered so
     * far and the pack ids among them. A replaced round is dropped together with its buffers, so a superseded
     * reload can never publish a half-registered set over the live one.
     */
    private final class PendingLoad {

        private final Map<String, CuttingBoardRecipe> recipes = new LinkedHashMap<>();
        // Ids whose winning definition came from a CraftEngine pack section; see getPackRecipeCount().
        private final Set<String> packIds = new HashSet<>();
        private final Set<String> overriddenExternalIds;

        private PendingLoad(YamlConfiguration mainConfig) {
            this.overriddenExternalIds = externalOverrideIds(mainConfig, "cutting_board");
        }

        private void putOwnFile(String recipeId, ConfigurationSection section) {
            recipes.put(recipeId, parseCached(OWN_FILE_SOURCE, recipeId, section));
        }

        private void putPackFile(String recipeId, ConfigurationSection section, String source) {
            if (recipes.containsKey(recipeId)) {
                I18n.logWarning("recipe.pack_duplicate_skipped", "id", recipeId, "source", source);
                return;
            }
            recipes.put(recipeId, parseCached(source, recipeId, section));
            packIds.add(recipeId);
        }
    }

    /**
     * Builds the derived indexes from a finished round and publishes them together as immutable snapshots, so
     * a reader on any Folia thread sees either the previous set or the complete new one.
     */
    private void publishLoadedSet(PendingLoad pending) {
        Map<String, CuttingBoardRecipe> newRecipes = pending.recipes;
        // Merge addon-registered recipes last so they survive reloads; an editor override is explicit and wins.
        for (CuttingBoardRecipe recipe : externalRecipes.values()) {
            if (!pending.overriddenExternalIds.contains(recipe.getId()) || !newRecipes.containsKey(recipe.getId())
                    || AddonRecipeFiles.ownerOf("cutting_board", recipe.getId()) != null) {
                newRecipes.put(recipe.getId(), recipe);
                pending.packIds.remove(recipe.getId());
            }
        }

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
            } else if (ingredient instanceof RecipeIngredient.Choice choice) {
                for (RecipeIngredient option : choice.options()) {
                    if (option instanceof RecipeIngredient.Item optItem) {
                        newByItemId.computeIfAbsent(optItem.key().toString().toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(recipe.getId());
                    } else {
                        newTagInputRecipeIds.add(recipe.getId());
                    }
                }
            }
        }
        Map<String, Set<String>> frozenByItemId = new HashMap<>(newByItemId.size());
        for (Map.Entry<String, Set<String>> e : newByItemId.entrySet()) {
            frozenByItemId.put(e.getKey(), Set.copyOf(e.getValue()));
        }

        // Published as a snapshot: the round's buffer is done, and a later round must never write into the set
        // a region thread may be matching against right now.
        this.recipes = Collections.unmodifiableMap(new LinkedHashMap<>(newRecipes));
        this.packRecipeCount = pending.packIds.size();
        this.sortedRecipes = newSorted;
        this.byInputItemId = Map.copyOf(frozenByItemId);
        this.tagInputRecipeIds = Set.copyOf(newTagInputRecipeIds);
        LinkedHashSet<CuttingBoardRecipe.ToolRequirement> uniqueTools = new LinkedHashSet<>();
        for (CuttingBoardRecipe recipe : newSorted) {
            uniqueTools.addAll(recipe.getTools());
        }
        this.toolRequirements = List.copyOf(uniqueTools);
        this.resultToRecipes = buildResultIndex(newSorted);
        invalidateRecipeCaches();
    }

    /**
     * The structures one published generation holds. Capturing takes the references and restoring puts them
     * back, so the set and the indexes derived from it always belong to the same generation.
     */
    public record PublishedState(Map<String, CuttingBoardRecipe> recipes,
                                 int packRecipeCount,
                                 List<CuttingBoardRecipe> sortedRecipes,
                                 Map<String, Set<String>> byInputItemId,
                                 Set<String> tagInputRecipeIds,
                                 Map<String, List<CuttingBoardRecipe>> resultToRecipes,
                                 List<CuttingBoardRecipe.ToolRequirement> toolRequirements) {
    }

    /** Captures the published generation before a pass replaces it, so a failed pass can be put back. */
    public PublishedState capturePublished() {
        return new PublishedState(recipes, packRecipeCount, sortedRecipes, byInputItemId, tagInputRecipeIds,
                resultToRecipes, toolRequirements);
    }

    /**
     * Puts a captured generation back after a pass failed instead of publishing it. The caches keyed on the
     * recipe set are dropped too: going back to the earlier set is a change as far as they are concerned.
     */
    public void restorePublished(PublishedState state) {
        this.recipes = state.recipes();
        this.packRecipeCount = state.packRecipeCount();
        this.sortedRecipes = state.sortedRecipes();
        this.byInputItemId = state.byInputItemId();
        this.tagInputRecipeIds = state.tagInputRecipeIds();
        this.resultToRecipes = state.resultToRecipes();
        this.toolRequirements = state.toolRequirements();
        invalidateRecipeCaches();
    }

    /** Drops every cache keyed on the recipe set; a publish and a rollback both need it. */
    private void invalidateRecipeCaches() {
        vanillaItemIdsByTagCache.clear();
        // Invalidate the recipe-list GUI display cache: this republish path (incl. addon register/
        // unregister) bypasses RecipeViewGui.clearConfigCache.
        RecipeViewGui.clearRecipeDisplayCache();
        // Same reason as the cooking pot's publish path: the decoded snapshots are keyed by strings the
        // replaced recipes owned.
        RecipeItemCodec.clearDecodeCache();
    }

    private CuttingBoardRecipe parseRecipe(String id, ConfigurationSection section) {
        Object rawInput = section.get("input");
        if (rawInput == null) {
            throw new IllegalArgumentException("Recipe must have an input");
        }
        if (rawInput instanceof ConfigurationSection nested) {
            rawInput = sectionToMap(nested);
        }
        RecipeIngredient input = RecipeParsingSupport.parseIngredientValue(rawInput);
        ItemStack inputDisplay = createDisplayItem(input);
        if (inputDisplay == null) {
            throw new IllegalArgumentException("Input item or tag has no loaded items: " + rawInput);
        }

        // Support a scalar 'tool:' or a plural 'tools:' list (or both). 'tools' takes precedence;
        // 'tool' is the fallback. Only requires at least one of the two.
        String toolStr = ConfigSectionReader.optionalString(section, "tool");
        List<String> toolStrings = ConfigSectionReader.optionalStringList(section, "tools");
        if (toolStrings.isEmpty() && toolStr != null && !toolStr.isBlank()) {
            toolStrings = Collections.singletonList(toolStr);
        }
        if (toolStrings.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have a tool");
        }

        List<CuttingBoardRecipe.ToolRequirement> tools = new ArrayList<>();
        for (String tool : toolStrings) {
            CuttingBoardRecipe.ToolRequirement requirement = parseTool(tool);
            if (!toolHasMembers(requirement)) {
                throw new IllegalArgumentException("Tool item or tag has no loaded items: " + tool);
            }
            tools.add(requirement);
        }

        List<CuttingBoardRecipe.ResultEntry> results = new ArrayList<>();
        
        List<Map<?, ?>> resultsList = ConfigSectionReader.optionalMapList(section, "results");
        for (int resultIndex = 0; resultIndex < resultsList.size(); resultIndex++) {
            Map<?, ?> resultMap = resultsList.get(resultIndex);
            // Cache each .get(...) once — Map.get is O(1) but allocates an entry traversal under
            // contention and the resultsList loop runs per-recipe on every config (re)load.
            Object itemValue = resultMap.get("item");
            if (itemValue == null) {
                throw new IllegalArgumentException("Missing result item at results[" + resultIndex + "].item");
            }
            String itemId = itemValue.toString();

            int count = 1;
            Object countValue = resultMap.get("count");
            if (countValue != null) {
                try {
                    count = Integer.parseInt(countValue.toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid count at results[" + resultIndex + "].count: " + countValue, e);
                }
            }
            count = Math.max(1, count);

            double chance = 1.0d;
            Object chanceValue = resultMap.get("chance");
            if (chanceValue != null) {
                try {
                    chance = Math.max(0.0d, Math.min(1.0d, Double.parseDouble(chanceValue.toString())));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid chance at results[" + resultIndex + "].chance: " + chanceValue, e);
                }
            }
            
            ItemStack result = createItem(itemId);
            if (result == null || result.getType().isAir()) {
                throw new IllegalArgumentException("Result item not found at results[" + resultIndex + "].item: " + itemId);
            }
            result.setAmount(count);
            // Full-item NBT snapshot (base64, written by the editor) beats the id-built item.
            Object nbtValue = resultMap.get("nbt");
            if (nbtValue != null) {
                ItemStack fromNbt = RecipeItemCodec.itemFromBase64(nbtValue.toString());
                if (fromNbt != null) {
                    result = fromNbt;
                    result.setAmount(count);
                }
            }
            Object componentsValue = resultMap.get("components");
            if (componentsValue instanceof Map<?, ?> components) {
                result = RecipeItemCodec.applyComponents(result, RecipeItemCodec.coerceStringMap(components));
                result.setAmount(count);
            }
            results.add(new CuttingBoardRecipe.ResultEntry(result, chance));
        }

        if (results.isEmpty()) {
            String resultStr = ConfigSectionReader.optionalString(section, "result");
            if (resultStr != null) {
                ItemStack result = createItem(resultStr);
                if (result == null || result.getType().isAir()) {
                    throw new IllegalArgumentException("Result item not found at result: " + resultStr);
                }
                int count = Math.max(1, ConfigSectionReader.optionalInt(section, "amount",
                        ConfigSectionReader.optionalInt(section, "count", 1)));
                double chance = Math.max(0.0d, Math.min(1.0d,
                        ConfigSectionReader.optionalDouble(section, "chance", 1.0d)));
                result.setAmount(count);
                results.add(new CuttingBoardRecipe.ResultEntry(result, chance));
            }
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one valid result");
        }

        String sound = normalizeSound(ConfigSectionReader.optionalString(section, "sound", Constants.SOUND_CUTTING_BOARD_KNIFE));
        int priority = ConfigSectionReader.optionalInt(section, "priority", 0);
        return new CuttingBoardRecipe(id, input, inputDisplay, tools, results, sound, priority);
    }

    private String normalizeSound(String soundStr) {
        if (soundStr == null || soundStr.isBlank()) {
            return Constants.SOUND_CUTTING_BOARD_KNIFE;
        }

        String normalized = soundStr.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(":")) {
            return normalized;
        }
        return "minecraft:" + normalized;
    }

    private RecipeIngredient parseIngredient(String str) {
        return RecipeParsingSupport.parseIngredientChoice(str);
    }

    static CuttingBoardRecipe.ToolRequirement parseTool(String str) {
        RecipeParsingSupport.ParsedKey parsed = RecipeParsingSupport.parseKeyWithExclusions(str, "tool");
        String key = parsed.key().toString();
        boolean tag = parsed.tag()
                || Constants.TAG_KNIVES.equalsIgnoreCase(key)
                || Constants.TAG_AXES.equalsIgnoreCase(key)
                || Constants.TAG_PICKAXES.equalsIgnoreCase(key)
                || Constants.TAG_SHOVELS.equalsIgnoreCase(key);
        return new CuttingBoardRecipe.ToolRequirement(
                parsed.key(), tag, parsed.excludedItems(), parsed.excludedTags());
    }

    private ItemStack createDisplayItem(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return itemIngredient.createStack();
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            for (var candidate : plugin.getCraftEngine().itemManager().itemIdsByTag(tagIngredient.key())) {
                if (tagIngredient.excludedItems().contains(candidate.key())) {
                    continue;
                }
                boolean excludedByTag = tagIngredient.excludedTags().stream().anyMatch(excludedTag ->
                        plugin.getCraftEngine().itemManager().itemIdsByTag(excludedTag).stream()
                                .anyMatch(excluded -> excluded.key().equals(candidate.key())));
                if (excludedByTag) {
                    continue;
                }
                ItemStack item = createItem(candidate.key().toString());
                if (item != null && !item.getType().isAir()) {
                    return item;
                }
            }
            List<ItemStack> vanillaItems = ItemUtils.createVanillaTagDisplayItems(
                    tagIngredient.key(), tagIngredient.excludedItems(), tagIngredient.excludedTags());
            if (!vanillaItems.isEmpty()) {
                return vanillaItems.getFirst();
            }
        }

        if (ingredient instanceof RecipeIngredient.Choice choice) {
            for (RecipeIngredient option : choice.options()) {
                ItemStack item = createDisplayItem(option);
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

    private boolean toolHasMembers(CuttingBoardRecipe.ToolRequirement requirement) {
        // These are FarmersDelight action selectors, not registry items. They are resolved by
        // ToolContext/matchesToolFallback at click time (axe strip/dig, pickaxe dig, shovel dig).
        if (!requirement.isTag() && isVirtualToolAction(requirement.key())) {
            return true;
        }
        if (requirement.isTag()) {
            return !plugin.getCraftEngine().itemManager().itemIdsByTag(requirement.key()).isEmpty()
                    || !vanillaItemIdsByTagCache.getIds(requirement.key()).isEmpty()
                    || !CommonTagResolver.getMembers(requirement.key()).isEmpty();
        }
        ItemStack item = createItem(requirement.key().toString());
        return item != null && !item.getType().isAir();
    }

    private static boolean isVirtualToolAction(Key key) {
        String id = key == null ? "" : key.toString();
        return Constants.ACTION_AXE_DIG.equalsIgnoreCase(id)
                || Constants.ACTION_AXE_STRIP.equalsIgnoreCase(id)
                || Constants.ACTION_PICKAXE_DIG.equalsIgnoreCase(id)
                || Constants.ACTION_SHOVEL_DIG.equalsIgnoreCase(id);
    }

    private static Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection nested ? sectionToMap(nested) : value);
        }
        return map;
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

    public boolean isRecipeTool(ItemStack tool) {
        if (tool == null || tool.getType().isAir()) {
            return false;
        }
        ToolContext context = ToolContext.from(plugin, tool, ItemUtils.getCustomItemId(tool));
        for (CuttingBoardRecipe.ToolRequirement requirement : toolRequirements) {
            if (matchesToolRequirement(requirement, context)) {
                return true;
            }
        }
        return false;
    }

    public List<CuttingBoardRecipe> filterCraftable(
            List<CuttingBoardRecipe> candidateRecipes,
            Iterable<ItemStack> availableItems
    ) {
        if (candidateRecipes == null || candidateRecipes.isEmpty() || availableItems == null) {
            return List.of();
        }
        List<AvailableItem> available = new ArrayList<>();
        for (ItemStack item : availableItems) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            available.add(new AvailableItem(
                    item,
                    ToolContext.from(plugin, item, ItemUtils.getCustomItemId(item))
            ));
        }

        List<CuttingBoardRecipe> craftable = new ArrayList<>();
        // One memo for the whole draw, like the cooking-pot filter: every recipe on the page tests its own
        // input expression against the same stacks, and each test resolves CraftEngine item ids and tags.
        IngredientMatchMemo<ItemStack, RecipeIngredient> inputMatches = IngredientMatchMemo.of(
                this::matchesIngredient, RecipeIngredient::stableKey);
        for (CuttingBoardRecipe recipe : candidateRecipes) {
            boolean hasInput = false;
            boolean hasTool = false;
            for (AvailableItem candidate : available) {
                if (!hasInput && inputMatches.test(candidate.item(), recipe.getInput())) {
                    hasInput = true;
                }
                if (!hasTool && matchesTool(recipe, candidate.toolContext())) {
                    hasTool = true;
                }
                if (hasInput && hasTool) {
                    craftable.add(recipe);
                    break;
                }
            }
        }
        return List.copyOf(craftable);
    }

    private Map<String, List<CuttingBoardRecipe>> buildResultIndex(List<CuttingBoardRecipe> recipesToIndex) {
        Map<String, List<CuttingBoardRecipe>> index = new HashMap<>();
        for (CuttingBoardRecipe recipe : recipesToIndex) {
            for (CuttingBoardRecipe.ResultEntry result : recipe.getResults()) {
                if (result.item() == null) {
                    continue;
                }
                String key = ItemUtils.getCustomItemId(result.item());
                if (key == null) {
                    key = ItemUtils.getVanillaMaterialItemId(result.item());
                }
                if (key == null) {
                    continue;
                }
                index.computeIfAbsent(key, k -> new ArrayList<>(1)).add(recipe);
            }
        }
        Map<String, List<CuttingBoardRecipe>> frozen = new HashMap<>(index.size());
        for (Map.Entry<String, List<CuttingBoardRecipe>> entry : index.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /** Recipes that produce this item, from the reverse result index (O(1), no full scan). */
    public List<CuttingBoardRecipe> getRecipesProducing(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return List.of();
        }
        String key = ItemUtils.getCustomItemId(item);
        if (key == null) {
            key = ItemUtils.getVanillaMaterialItemId(item);
        }
        if (key == null) {
            return List.of();
        }
        List<CuttingBoardRecipe> matches = resultToRecipes.get(key);
        return matches == null ? List.of() : matches;
    }

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
        return matchesIngredient(input, recipe.getInput());
    }

    // Public ingredient check so API cross-reference / addons can test an item against a recipe input
    // without re-implementing item/tag/choice matching.
    public boolean matchesIngredient(ItemStack input, RecipeIngredient ingredient) {
        if (input == null || input.getType().isAir() || ingredient == null) {
            return false;
        }
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            if (!ItemUtils.matchesItemId(input, itemIngredient.key())) {
                return false;
            }
            if (itemIngredient.nbt() == null) {
                return true;
            }
            ItemStack expected = RecipeItemCodec.itemFromBase64(itemIngredient.nbt());
            return expected != null && expected.isSimilar(input);
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return matchesTaggedItem(input, tagIngredient.key(), tagIngredient.excludedItems(), tagIngredient.excludedTags());
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            for (RecipeIngredient option : choice.options()) {
                if (matchesIngredient(input, option)) {
                    return true;
                }
            }
            return false;
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

        if (toolRequirement.tag()
                && Constants.TAG_KNIVES.equalsIgnoreCase(toolRequirement.key().toString())) {
            return (toolContext.knife() || toolContext.matchesCustomTag(toolRequirement.key()))
                    && isExcludedTool(toolContext, toolRequirement);
        }

        if (matchesToolFallback(toolRequirement, toolContext)) {
            return isExcludedTool(toolContext, toolRequirement);
        }

        if (!toolRequirement.tag()) {
            return toolContext.matchesItemKey(toolRequirement.key())
                    && isExcludedTool(toolContext, toolRequirement);
        }

        if (toolContext.matchesCustomTag(toolRequirement.key())) {
            return isExcludedTool(toolContext, toolRequirement);
        }

        return toolContext.matchesVanillaTag(toolRequirement.key()) && isExcludedTool(toolContext, toolRequirement);
    }

    private boolean matchesToolFallback(CuttingBoardRecipe.ToolRequirement requirement, ToolContext toolContext) {
        String toolKey = requirement.key().toString();
        return (requirement.tag() && Constants.TAG_KNIVES.equalsIgnoreCase(toolKey) && toolContext.knife())
                || ((Constants.TAG_AXES.equalsIgnoreCase(toolKey)
                || Constants.ACTION_AXE_DIG.equalsIgnoreCase(toolKey)
                || Constants.ACTION_AXE_STRIP.equalsIgnoreCase(toolKey)) && toolContext.axe())
                || ((Constants.TAG_PICKAXES.equalsIgnoreCase(toolKey)
                || Constants.ACTION_PICKAXE_DIG.equalsIgnoreCase(toolKey)) && toolContext.pickaxe())
                || ((Constants.TAG_SHOVELS.equalsIgnoreCase(toolKey)
                || Constants.ACTION_SHOVEL_DIG.equalsIgnoreCase(toolKey)) && toolContext.shovel())
                || (!requirement.tag() && Constants.ITEM_SHEARS.equalsIgnoreCase(toolKey) && toolContext.shears());
    }

    private boolean isExcludedTool(ToolContext toolContext, CuttingBoardRecipe.ToolRequirement toolRequirement) {
        Key itemKey = toolContext.itemKey();
        if (itemKey == null) {
            return true;
        }
        if (toolRequirement.excludedItems().contains(itemKey)) {
            return false;
        }

        if (!toolContext.customTags().isEmpty()
                && toolRequirement.excludedTags().stream().anyMatch(toolContext.customTags()::contains)) {
            return false;
        }

        return toolRequirement.excludedTags().stream().noneMatch(toolContext::matchesVanillaTag);
    }

    private boolean matchesTaggedItem(ItemStack item, Key tagKey, Set<Key> excludedItems, Set<Key> excludedTags) {
        String customId = ItemUtils.getCustomItemId(item);
        String vanillaId = ItemUtils.getVanillaMaterialItemId(item);
        boolean nonVanillaIdentity = customId != null || MMOItemsCompat.getItemId(item) != null;

        if (excludedItems.stream().anyMatch(excluded -> ItemUtils.matchesItemId(item, excluded))) {
            return false;
        }

        Set<String> itemTags = ItemUtils.getItemTagIds(item);
        if (itemTags.contains(tagKey.toString())) {
            return excludedTags.stream().map(Key::toString).noneMatch(itemTags::contains);
        }

        boolean matchesBase = !nonVanillaIdentity && vanillaId != null
                && (vanillaItemIdsByTagCache.getIds(tagKey).contains(vanillaId)
                || ItemUtils.matchesVanillaItemTag(item, tagKey, excludedItems, excludedTags));
        if (!matchesBase) {
            return false;
        }
        return excludedTags.stream().noneMatch(excludedTag ->
                !nonVanillaIdentity && vanillaId != null
                        && vanillaItemIdsByTagCache.getIds(excludedTag).contains(vanillaId));
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

    public int getExternalRecipeCount() {
        return externalRecipes.size();
    }

    /** Recipes that reached this manager through a CraftEngine pack section, not the plugin's own file. */
    public int getPackRecipeCount() {
        return packRecipeCount;
    }

    public boolean isExternalRecipe(String id) {
        return id != null && externalRecipes.containsKey(id);
    }

    private static Set<String> externalOverrideIds(YamlConfiguration config, String station) {
        return Set.copyOf(config.getStringList("external-overrides." + station));
    }

    public CuttingBoardRecipe getRecipe(String id) {
        return recipes.get(id);
    }

    public void reload() {
        loadRecipes();
    }

    public void registerExternalRecipe(String id, String inputSpec, String toolSpec,
                                       List<ItemStack> results, String sound) {
        registerExternalRecipe(id, inputSpec, toolSpec, results, null, sound);
    }

    /**
     * Registers an external cutting-board recipe with a per-result drop chance. chances is
     * aligned to results by index; a null list or a null / out-of-range entry means the
     * matching result is guaranteed (chance 1.0), matching the plain no-chance registration.
     */
    public void registerExternalRecipe(String id, String inputSpec, String toolSpec,
                                       List<ItemStack> results, List<Double> chances, String sound) {
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
        CuttingBoardRecipe.ToolRequirement toolRequirement = parseTool(toolSpec);
        if (!toolHasMembers(toolRequirement)) {
            throw new IllegalArgumentException("Tool item or tag has no loaded items: " + toolSpec);
        }
        List<CuttingBoardRecipe.ResultEntry> entries = new ArrayList<>();
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                ItemStack result = results.get(i);
                if (result != null && !result.getType().isAir()) {
                    Double chance = chances != null && i < chances.size() ? chances.get(i) : null;
                    double clamped = chance == null ? 1.0d : Math.max(0.0d, Math.min(1.0d, chance));
                    entries.add(new CuttingBoardRecipe.ResultEntry(result.clone(), clamped));
                }
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one valid result");
        }
        List<CuttingBoardRecipe.ToolRequirement> tools = List.of(toolRequirement);
        CuttingBoardRecipe recipe = new CuttingBoardRecipe(id, input, inputDisplay, tools, entries,
                normalizeSound(sound), 0);
        externalRecipes.put(id, recipe);
        scheduleExternalRepublish();
    }

    public void unregisterExternalRecipe(String id) {
        if (id != null && externalRecipes.remove(id) != null) {
            scheduleExternalRepublish();
        }
    }

    private void scheduleExternalRepublish() {
        if (externalRepublishScheduled) {
            return;
        }
        externalRepublishScheduled = true;
        plugin.scheduler().runLater(() -> {
            externalRepublishScheduled = false;
            loadRecipes();
            // Same as the cooking pot republish: the summary was already printed by the CraftEngine
            // readiness pass, so the cutting board count would stay a batch behind the addon recipes
            // without this. Deduped on the counts digest, so an unchanged batch prints nothing.
            plugin.requestContentSummary();
        }, 1L);
    }

    private record AvailableItem(ItemStack item, ToolContext toolContext) {
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
                    .collect(Collectors.toUnmodifiableSet());
            return new ToolContext(
                    tool,
                    toolId,
                    vanillaId,
                    itemKey,
                    customTags,
                    plugin.isKnife(tool),
                    isMaterialSuffix(tool, "_AXE"),
                    isMaterialSuffix(tool, "_PICKAXE"),
                    isMaterialSuffix(tool, "_SHOVEL"),
                    tool.getType() == Material.SHEARS
            );
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
