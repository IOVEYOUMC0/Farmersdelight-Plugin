package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecipeViewGui extends AbstractInventoryGui {

    private static final Map<UUID, RecipeViewGui> activeGuis = new ConcurrentHashMap<>();
    private static volatile boolean listenerRegistered = false;
    private static volatile RecipeViewGuiConfig cachedConfig = null;
    private static final Set<String> warnedMissingCustomCookingPotDetailConfigs = ConcurrentHashMap.newKeySet();
    private static final Set<String> warnedCookingPotDetailCapacityConfigs = ConcurrentHashMap.newKeySet();
    // Fully-built recipe-list display item (result icon + formatted ingredient/tool lore) keyed by
    // type + group + preview-count + recipe id + locale. Rebuilding it per list draw runs the whole
    // name-resolution + lore-format chain (the profile's formatCompactIngredientLoreLines/getDisplayName
    // hot node); this collapses a warm open/page to one clone. Cleared on config reload and whenever a
    // recipe manager republishes (loadRecipes). Values and returns are cloned like itemCache.
    private static final Map<String, ItemStack> recipeListDisplayCache = new ConcurrentHashMap<>();
    // Tag/choice ingredient option caches live in RecipeIngredientIcons (extracted with the resolvers).
    // Tool preview options include item/tag identity and exclusions, so cache by the complete requirement.
    private static final Map<CuttingBoardRecipe.ToolRequirement, List<ItemStack>> toolPreviewCache = new ConcurrentHashMap<>();
    private static final ItemStack EMPTY_SLOT_BACKGROUND = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern SHIFT_TAG_PATTERN = Pattern.compile("<shift:(-?\\d+)>");
    private static final int MAX_COMPACT_INGREDIENT_LINE_LENGTH = 42;
    private static final int GUI_TICK_INTERVAL_TICKS = 4;

    static {
        ItemMeta meta = EMPTY_SLOT_BACKGROUND.getItemMeta();
        meta.displayName(Component.text(" "));
        EMPTY_SLOT_BACKGROUND.setItemMeta(meta);
    }

    public enum GuiState {
        MAIN_MENU,
        COOKING_POT_LIST,
        CUTTING_BOARD_LIST,
        RECIPE_DETAIL
    }

    final RecipeViewGuiConfig config;
    private GuiState state = GuiState.MAIN_MENU;
    private int currentPage = 0;
    private volatile String selectedRecipeId = null;
    private boolean cookingPotMode = true;
    private boolean craftableOnly = false;
    private GuiState recipeBackState = GuiState.COOKING_POT_LIST;
    volatile int currentToolIndex = 0;
    private volatile int currentToolPreviewIndex = 0;
    private int toolSwitchTicks = 0;
    private final Map<Integer, List<ItemStack>> animatedIngredientSlots = new HashMap<>();
    private final Map<Integer, Integer> animatedIngredientIndices = new HashMap<>();
    private final Map<Integer, RecipeIngredient> animatedIngredientDefinitions = new HashMap<>();
    private int ingredientSwitchTicks = 0;
    int cookingProcessBarTicks = 0;
    final RecipeDetailRenderer detailRenderer = new RecipeDetailRenderer(this);
    private static final int INGREDIENT_SWITCH_INTERVAL = 20;
    
    private final boolean fromCookingPot;
    private final Location cookingPotLocation;
    private boolean backButtonCommandsEnabled = false;
    private boolean editMode = false;
    // Resolve only once (lazily, during the first open, when the viewer is at the pot = same Folia region) and memoize the result,
    // so the per-tick / redraw flow never repeats a cross-region block read.
    private boolean recipeGroupResolved;
    private String cachedRecipeGroupId;
    private boolean ignoreNextClose = false;
    private FillButtonState fillButtonState = FillButtonState.READY;
    // Detail-to-detail navigation history: clicking a linked recipe (an ingredient/result that is itself
    // another recipe's output) pushes the detail it left, so "back" returns to that recipe instead of always
    // dropping to the original list. Reset when a detail is opened fresh from a list; empty history keeps the
    // old behavior (back to recipeBackState). Touched only inside the click handler (single-threaded per viewer).
    private final java.util.Deque<DetailState> detailHistory = new java.util.ArrayDeque<>();

    private record DetailState(boolean cookingPotMode, String selectedRecipeId, GuiState recipeBackState) {
    }

    public RecipeViewGui(FarmersDelightPlugin plugin, Player player) {
        this(plugin, player, false, null);
    }

    public RecipeViewGui(FarmersDelightPlugin plugin, Player player, boolean fromCookingPot) {
        this(plugin, player, fromCookingPot, null);
    }

    public RecipeViewGui(FarmersDelightPlugin plugin, Player player, boolean fromCookingPot, Location cookingPotLocation) {
        super(plugin, player);
        this.fromCookingPot = fromCookingPot;
        this.cookingPotLocation = cookingPotLocation;
        this.config = getOrCreateConfig();
        
        if (fromCookingPot) {
            this.state = GuiState.COOKING_POT_LIST;
        }
        
        String initialTitle;
        if (fromCookingPot) {
            initialTitle = resolveMenuTitle(
                    "recipe-list",
                    "level_1",
                    config.getRecipeList().getTitle(),
                    Map.of("page", "1", "total", "1")
            );
        } else {
            initialTitle = resolveMenuTitle("main-menu", null, config.getMainMenu().getTitle(), Map.of());
        }
        this.inventory = Bukkit.createInventory(this, 54, coloredComponent(initialTitle));
    }

    @Override
    protected void onTick() {
        if (!closed && state == GuiState.RECIPE_DETAIL) {
            if (cookingPotMode) {
                tickCookingPotProcessBar();
            } else {
                tickToolSwitch();
            }
            tickIngredientSwitch();
        }
    }

    private RecipeViewGuiConfig getOrCreateConfig() {
        if (cachedConfig != null) {
            return cachedConfig;
        }
        
        var section = plugin.getRecipeViewGuiSection();
        if (section != null) {
            cachedConfig = RecipeViewGuiConfig.fromConfig(section);
        } else {
            cachedConfig = createDefaultConfig();
        }
        return cachedConfig;
    }

    public static void clearConfigCache() {
        cachedConfig = null;
        // Built display items cache resolved names/lore (from the language files), so clear them too; otherwise
        // stale item names would linger in the recipe GUI after /fd reload lang/gui.
        RecipeIngredientIcons.clearCaches();
        recipeListDisplayCache.clear();
        toolPreviewCache.clear();
        RecipeDetailRenderer.clearProcessBarFrameCache();
        warnedMissingCustomCookingPotDetailConfigs.clear();
        warnedCookingPotDetailCapacityConfigs.clear();
    }

    public static void clearRecipeDisplayCache() {
        recipeListDisplayCache.clear();
        toolPreviewCache.clear();
    }

    private RecipeViewGuiConfig createDefaultConfig() {
        RecipeViewGuiConfig.MainMenuConfig mainMenu = RecipeViewGuiConfig.MainMenuConfig.fromConfig(null);
        RecipeViewGuiConfig.RecipeListConfig recipeList = RecipeViewGuiConfig.RecipeListConfig.fromConfig(null);
        RecipeViewGuiConfig.RecipeDetailConfig cookingPotDetail = RecipeViewGuiConfig.RecipeDetailConfig.createCookingPotDefault();
        RecipeViewGuiConfig.RecipeDetailConfig cuttingBoardDetail = RecipeViewGuiConfig.RecipeDetailConfig.createCuttingBoardDefault();
        return new RecipeViewGuiConfig(mainMenu, recipeList, cookingPotDetail, Map.of(), cuttingBoardDetail, true, false, 4);
    }

    public void open(Player player) {
        doOpen(() -> refresh(player));
    }

    public void openCookingPotRecipes(Player player) {
        backButtonCommandsEnabled = true;
        state = GuiState.COOKING_POT_LIST;
        cookingPotMode = true;
        recipeBackState = GuiState.COOKING_POT_LIST;
        currentPage = 0;
        open(player);
    }

    public void openCuttingBoardRecipes(Player player) {
        backButtonCommandsEnabled = true;
        state = GuiState.CUTTING_BOARD_LIST;
        cookingPotMode = false;
        recipeBackState = GuiState.CUTTING_BOARD_LIST;
        currentPage = 0;
        open(player);
    }

    public void openCookingPotRecipesForEdit(Player player) {
        editMode = true;
        openCookingPotRecipes(player);
    }

    public void openCuttingBoardRecipesForEdit(Player player) {
        editMode = true;
        openCuttingBoardRecipes(player);
    }

    private void tickToolSwitch() {
        toolSwitchTicks++;
        if (toolSwitchTicks >= INGREDIENT_SWITCH_INTERVAL) {
            toolSwitchTicks = 0;
            
            CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(selectedRecipeId);
            if (recipe == null || recipe.getTools() == null || recipe.getTools().isEmpty()) {
                return;
            }

            List<CuttingBoardRecipe.ToolRequirement> tools = recipe.getTools();
            int safeToolIndex = currentToolIndex % tools.size();
            List<ItemStack> previewOptions = resolveToolPreviewOptions(tools.get(safeToolIndex));

            if (previewOptions.size() > 1) {
                currentToolPreviewIndex++;
                if (currentToolPreviewIndex >= previewOptions.size()) {
                    currentToolPreviewIndex = 0;
                    if (tools.size() > 1) {
                        currentToolIndex = (currentToolIndex + 1) % tools.size();
                    }
                }
            } else if (tools.size() > 1) {
                currentToolIndex = (currentToolIndex + 1) % tools.size();
                currentToolPreviewIndex = 0;
            }

            updateToolDisplay();
        }
    }

    private void updateToolDisplay() {
        if (state != GuiState.RECIPE_DETAIL || cookingPotMode) return;
        
        RecipeViewGuiConfig.RecipeDetailConfig detailConfig = getActiveDetailConfig();
        if (detailConfig.getToolSlot() < 0) return;
        
        CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(selectedRecipeId);
        if (recipe == null || recipe.getTools() == null || recipe.getTools().isEmpty()) return;
        
        int safeIndex = currentToolIndex % recipe.getTools().size();
        CuttingBoardRecipe.ToolRequirement currentTool = recipe.getTools().get(safeIndex);
        
        if (player == null || !player.isOnline()) return;
        
        ItemStack toolItem = createToolDisplayItem(currentTool, recipe.getTools().size(), safeIndex, player);
        inventory.setItem(detailConfig.getToolSlot(), toolItem);
    }

    private void tickIngredientSwitch() {
        if (animatedIngredientSlots.isEmpty()) {
            return;
        }

        ingredientSwitchTicks++;
        if (ingredientSwitchTicks < INGREDIENT_SWITCH_INTERVAL) {
            return;
        }

        ingredientSwitchTicks = 0;
        if (player == null || !player.isOnline()) {
            return;
        }

        for (Map.Entry<Integer, List<ItemStack>> entry : animatedIngredientSlots.entrySet()) {
            List<ItemStack> options = entry.getValue();
            if (options.size() <= 1) {
                continue;
            }

            int slot = entry.getKey();
            int nextIndex = (animatedIngredientIndices.getOrDefault(slot, 0) + 1) % options.size();
            animatedIngredientIndices.put(slot, nextIndex);
            RecipeIngredient ingredient = animatedIngredientDefinitions.get(slot);
            if (ingredient == null) {
                continue;
            }
            inventory.setItem(slot, createAnimatedIngredientDisplay(ingredient, options.get(nextIndex), options, player));
        }
    }

    private void tickCookingPotProcessBar() {
        if (player == null || !player.isOnline() || selectedRecipeId == null) {
            return;
        }

        CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(getActiveCookingPotRecipeGroup(), selectedRecipeId);
        if (recipe == null) {
            return;
        }

        RecipeViewGuiConfig.RecipeDetailConfig detailConfig = getActiveCookingPotDetailConfig();
        int progressSlot = detailRenderer.getCookingPotProcessBarSlot(detailConfig);
        if (progressSlot < 0) {
            return;
        }

        int duration = Math.max(1, recipe.getCookTime());
        cookingProcessBarTicks += GUI_TICK_INTERVAL_TICKS;
        if (cookingProcessBarTicks > duration) {
            cookingProcessBarTicks = 0;
        }

        inventory.setItem(progressSlot, RecipeDetailRenderer.createCookingPotProcessBarItem(detailRenderer.cookingProcessBarFrame(recipe)));
    }

    private void resetDetailAnimations() {
        animatedIngredientSlots.clear();
        animatedIngredientIndices.clear();
        animatedIngredientDefinitions.clear();
        ingredientSwitchTicks = 0;
        cookingProcessBarTicks = 0;
    }

    private void refresh(Player player) {
        refresh(player, true);
    }

    private void refresh(Player player, boolean resetDetailAnimations) {
        switch (state) {
            case MAIN_MENU -> drawMainMenu(player);
            case COOKING_POT_LIST -> drawCookingPotList(player);
            case CUTTING_BOARD_LIST -> drawCuttingBoardList(player);
            case RECIPE_DETAIL -> drawRecipeDetail(player, resetDetailAnimations);
        }
    }

    private void drawMainMenu(Player player) {
        RecipeViewGuiConfig.MainMenuConfig menuConfig = config.getMainMenu();
        inventory = Bukkit.createInventory(this, menuConfig.getSize(),
                coloredComponent(resolveMenuTitle("main-menu", null, menuConfig.getTitle(), Map.of())));

        fillBackground(menuConfig);

        setGuiItem(menuConfig, "cooking_pot", menuConfig.getCookingPotSlot());
        setGuiItem(menuConfig, "cutting_board", menuConfig.getCuttingBoardSlot());
        // Addon recipe-book button: only shown when an addon has registered a recipe type.
        if (menuConfig.getRecipeBookSlot() >= 0
                && !com.huidu.farmersdelight.api.FarmersDelightApi.get().recipeTypes().isEmpty()) {
            setGuiItem(menuConfig, "recipe_book", menuConfig.getRecipeBookSlot());
        }
        setGuiItem(menuConfig, "back", menuConfig.getBackSlot());
    }

    private void drawCookingPotList(Player player) {
        cookingPotMode = true;
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CookingPotRecipe> recipes = plugin.getCookingPotRecipes().getSortedRecipes(getActiveCookingPotRecipeGroup());
        if (craftableOnly) {
            recipes = filterCraftableCookingPotRecipes(recipes);
        }
        recipes = applyDiscoveryFilter(recipes, true, player);
        drawRecipeList(player, listConfig, recipes, true);
    }

    private void drawCuttingBoardList(Player player) {
        cookingPotMode = false;
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CuttingBoardRecipe> recipes = plugin.getCuttingBoardRecipes().getSortedRecipes();
        if (craftableOnly) {
            recipes = filterCraftableCuttingBoardRecipes(recipes);
        }
        recipes = applyDiscoveryFilter(recipes, false, player);
        drawRecipeList(player, listConfig, recipes, false);
    }

    private <T> void drawRecipeList(Player player, RecipeViewGuiConfig.RecipeListConfig listConfig, 
                                    List<T> recipes, boolean isCookingPot) {
        List<Integer> recipeSlots = listConfig.getRecipeSlots();
        int itemsPerPage = recipeSlots.size();
        int totalPages = Math.max(1, (int) Math.ceil(recipes.size() / (double) itemsPerPage));
        if (currentPage < 0) {
            currentPage = 0;
        } else if (currentPage >= totalPages) {
            currentPage = totalPages - 1;
        }

        Map<String, String> titlePlaceholders = new HashMap<>();
        titlePlaceholders.put("page", String.valueOf(currentPage + 1));
        titlePlaceholders.put("total", String.valueOf(totalPages));
        String title = resolveMenuTitle("recipe-list", "level_1", listConfig.getTitle(), titlePlaceholders);
        inventory = Bukkit.createInventory(this, listConfig.getSize(), coloredComponent(title));

        fillBackground(listConfig);

        // Locale + (for cooking pots) the per-instance preview-count and active group fully determine
        // a non-locked display item's content, so cache the built item across opens/pages/players.
        String locale = player == null ? "default" : player.locale().toString().toLowerCase(Locale.ROOT);
        String cacheKeyPrefix = isCookingPot
                ? "pot|" + getActiveCookingPotRecipeGroup() + '|' + config.getRecipeListMaxPreviewIngredients() + '|'
                : "board|" + config.getRecipeListMaxPreviewIngredients() + '|';

        int startIndex = currentPage * itemsPerPage;
        for (int i = 0; i < recipeSlots.size(); i++) {
            int recipeIndex = startIndex + i;
            if (recipeIndex < recipes.size()) {
                Object recipe = recipes.get(recipeIndex);
                ItemStack displayItem;
                if (isRecipeLocked(recipe, isCookingPot, player)) {
                    // Locked placeholder is per-player discovery state, never cached.
                    displayItem = plugin.getRecipeDiscoveryManager().lockedPlaceholder(player);
                } else if (isCookingPot) {
                    CookingPotRecipe potRecipe = (CookingPotRecipe) recipe;
                    displayItem = recipeListDisplayCache.computeIfAbsent(
                            cacheKeyPrefix + potRecipe.getId() + '|' + locale,
                            k -> createCookingPotRecipeDisplayItem(potRecipe, player)).clone();
                } else {
                    CuttingBoardRecipe boardRecipe = (CuttingBoardRecipe) recipe;
                    displayItem = recipeListDisplayCache.computeIfAbsent(
                            cacheKeyPrefix + boardRecipe.getId() + '|' + locale,
                            k -> createCuttingBoardRecipeDisplayItem(boardRecipe, player)).clone();
                }
                inventory.setItem(recipeSlots.get(i), displayItem);
            }
        }

        drawListNavigation(listConfig, totalPages);
    }

    private <T> List<T> applyDiscoveryFilter(List<T> recipes, boolean isCookingPot, Player player) {
        RecipeDiscoveryManager discovery = plugin.getRecipeDiscoveryManager();
        if (discovery == null || !discovery.isEnabled() || !discovery.hidesLocked() || player == null) {
            return recipes;
        }
        List<T> shown = new ArrayList<>(recipes.size());
        for (T recipe : recipes) {
            if (!isRecipeLocked(recipe, isCookingPot, player)) {
                shown.add(recipe);
            }
        }
        return shown;
    }

    private boolean isRecipeLocked(Object recipe, boolean isCookingPot, Player player) {
        RecipeDiscoveryManager discovery = plugin.getRecipeDiscoveryManager();
        if (discovery == null || !discovery.isEnabled() || player == null) {
            return false;
        }
        String typeId = isCookingPot
                ? RecipeDiscoveryManager.TYPE_COOKING_POT
                : RecipeDiscoveryManager.TYPE_CUTTING_BOARD;
        String id = isCookingPot
                ? ((CookingPotRecipe) recipe).getId()
                : ((CuttingBoardRecipe) recipe).getId();
        return !discovery.isUnlocked(player.getUniqueId(), typeId, id);
    }

    private void drawListNavigation(RecipeViewGuiConfig.RecipeListConfig listConfig, int totalPages) {
        if (currentPage > 0) {
            setGuiItem(listConfig, "prev_page", listConfig.getPrevPageSlot());
        }

        if (currentPage < totalPages - 1) {
            setGuiItem(listConfig, "next_page", listConfig.getNextPageSlot());
        }

        drawListBackOrCloseButton(listConfig);
        setFilterToggleItem(listConfig, player);

        if (listConfig.getInfoSlot() >= 0) {
            GuiConfig.GuiItem infoItem = listConfig.getItem("info");
            if (infoItem != null) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("page", String.valueOf(currentPage + 1));
                placeholders.put("total", String.valueOf(totalPages));
                inventory.setItem(listConfig.getInfoSlot(), infoItem.createItem(placeholders));
            }
        }
    }

    private void drawListBackOrCloseButton(RecipeViewGuiConfig.RecipeListConfig listConfig) {
        int backSlot = listConfig.getBackSlot();
        if (backSlot < 0) {
            return;
        }
        GuiConfig.GuiItem backItem = listConfig.getItem("back");
        boolean closesOnBack = backButtonCommandsEnabled && !fromCookingPot
                && (backItem == null || backItem.hasCommands());
        if (closesOnBack) {
            GuiConfig.GuiItem closeItem = listConfig.getItem("close");
            GuiConfig.GuiItem rendered = closeItem != null ? closeItem : backItem;
            if (rendered != null) {
                inventory.setItem(backSlot, rendered.createItem());
            }
            return;
        }
        if (backItem != null) {
            inventory.setItem(backSlot, backItem.createItem());
        }
    }

    private void drawRecipeDetail(Player player, boolean resetAnimations) {
        RecipeViewGuiConfig.RecipeDetailConfig detailConfig = getActiveDetailConfig();
        Map<String, String> titlePlaceholders = new HashMap<>();
        if (selectedRecipeId != null) {
            titlePlaceholders.put("recipe_id", selectedRecipeId);
        } else {
            titlePlaceholders.put("recipe_id", "");
        }
        String menuKey = "recipe-detail-cutting-board";
        String levelKey = "level_2_board";
        if (cookingPotMode) {
            menuKey = "recipe-detail-cooking-pot";
            levelKey = "level_2_pot";
        }
        String title = resolveMenuTitle(menuKey, levelKey, detailConfig.getTitle(), titlePlaceholders);
        inventory = Bukkit.createInventory(this, detailConfig.getSize(), coloredComponent(title));
        if (resetAnimations) {
            resetDetailAnimations();
            currentToolPreviewIndex = 0;
            currentToolIndex = 0;
            toolSwitchTicks = 0;
        }

        fillBackground(detailConfig);

        if (cookingPotMode) {
            CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(getActiveCookingPotRecipeGroup(), selectedRecipeId);
            if (recipe != null) {
                detailRenderer.drawCookingPotDetail(recipe, detailConfig, player);
            }
        } else {
            CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(selectedRecipeId);
            if (recipe != null) {
                detailRenderer.drawCuttingBoardDetail(recipe, detailConfig, player);
            }
        }

        setGuiItem(detailConfig, "back", detailConfig.getBackSlot());
        if (cookingPotMode) {
            drawCookingPotDetailActions(detailConfig, player);
        }
    }

    private void setFilterToggleItem(RecipeViewGuiConfig.RecipeListConfig listConfig, Player player) {
        int slot = listConfig.getFilterSlot();
        if (slot < 0) {
            return;
        }
        GuiConfig.GuiItem configured = listConfig.getItem(craftableOnly ? "filter-on" : "filter-off");
        if (configured != null) {
            inventory.setItem(slot, configured.createItem());
            return;
        }
        ItemStack item = new ItemStack(craftableOnly ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(craftableOnly
                ? tr("gui.recipe.filter_craftable_on", NamedTextColor.GREEN)
                : tr("gui.recipe.filter_craftable_off", NamedTextColor.GRAY));
        meta.lore(List.of(tr("gui.recipe.click_to_toggle", NamedTextColor.YELLOW)));
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private void drawCookingPotDetailActions(RecipeViewGuiConfig.RecipeDetailConfig detailConfig, Player player) {
        clearDetailActionSlot(detailConfig, detailConfig.getFilterSlot());
        if (fromCookingPot) {
            setFillButton(detailConfig, player);
        } else {
            // The background pass already painted the layout's fill slot, so without this the button stays
            // visible when the view was opened by command and there is no pot to fill.
            clearDetailActionSlot(detailConfig, detailConfig.getFillSlot());
        }
    }

    private void setFillButton(RecipeViewGuiConfig.RecipeDetailConfig detailConfig, Player player) {
        int slot = detailConfig.getFillSlot();
        if (slot < 0) {
            return;
        }
        GuiConfig.GuiItem configured = detailConfig.getItem(fillButtonState.itemKey());
        if (configured == null) {
            configured = detailConfig.getItem("fill");
        }
        if (configured == null) {
            return;
        }
        inventory.setItem(slot, configured.createItem(fillButtonState.placeholders(player)));
    }

    private void clearDetailActionSlot(RecipeViewGuiConfig.RecipeDetailConfig detailConfig, int slot) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, createBackgroundItem(detailConfig));
    }

    int cookTimeSeconds(CookingPotRecipe recipe) {
        if (recipe == null || recipe.getCookTime() <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(recipe.getCookTime() / 20.0D));
    }

    ItemStack createToolDisplayItem(CuttingBoardRecipe.ToolRequirement tool, int totalTools,
                                    int currentIndex, Player player) {
        List<ItemStack> previewOptions = resolveToolPreviewOptions(tool);
        int safePreviewIndex = 0;
        if (!previewOptions.isEmpty()) {
            safePreviewIndex = currentToolPreviewIndex % previewOptions.size();
        }
        ItemStack toolItem = null;
        if (!previewOptions.isEmpty()) {
            toolItem = previewOptions.get(safePreviewIndex).clone();
        }
        if (toolItem == null || toolItem.getType() == Material.BARRIER) {
            toolItem = new ItemStack(Material.IRON_AXE);
        }

        Component toolName = itemNameComponent(toolItem, player).colorIfAbsent(NamedTextColor.WHITE);

        ItemMeta toolMeta = toolItem.getItemMeta();
        toolMeta.displayName(tr("gui.recipe.tool", NamedTextColor.YELLOW));

        List<Component> lore = new ArrayList<>();
        lore.add(toolName);
        if (totalTools > 1) {
            lore.add(tr("gui.recipe.auto_cycle",
                    currentIndex + 1, totalTools));
        }
        if (previewOptions.size() > 1) {
            lore.add(tr("gui.recipe.matches_line",
                    Component.text(previewOptions.size()).color(NamedTextColor.YELLOW)));
        }
        toolMeta.lore(lore);
        toolItem.setItemMeta(toolMeta);

        return toolItem;
    }

    private ItemStack createToolPreviewItem(CuttingBoardRecipe.ToolRequirement tool) {
        List<ItemStack> previewOptions = resolveToolPreviewOptions(tool);
        if (!previewOptions.isEmpty()) {
            return previewOptions.getFirst().clone();
        }

        return new ItemStack(Material.IRON_AXE);
    }

    private List<ItemStack> resolveToolPreviewOptions(CuttingBoardRecipe.ToolRequirement tool) {
        List<ItemStack> cached = toolPreviewCache.computeIfAbsent(tool, this::computeToolPreviewOptions);
        List<ItemStack> copy = new ArrayList<>(cached.size());
        for (ItemStack item : cached) {
            copy.add(item.clone());
        }
        return copy;
    }

    private List<ItemStack> computeToolPreviewOptions(CuttingBoardRecipe.ToolRequirement tool) {
        if (tool.tag() && "farmersdelight:knives".equals(tool.key().toString())) {
            return finalizeToolPreviewOptions(createKnifePreviewItems(), tool);
        }

        List<ItemStack> previewOptions = finalizeToolPreviewOptions(
                RecipeIngredientIcons.resolveIngredientOptions(tool.asIngredient()), tool);
        if (!previewOptions.isEmpty()) {
            return previewOptions;
        }

        // Action keys are predicates rather than item/tag IDs, so map them to their visible tool families.
        previewOptions = new ArrayList<>();
        switch (tool.key().toString()) {
            case "farmersdelight:knives" -> previewOptions.addAll(createKnifePreviewItems());
            case "farmersdelight:axe_dig", "farmersdelight:axe_strip", "minecraft:axes" -> previewOptions.addAll(createVanillaToolPreviewItems("_axe"));
            case "farmersdelight:pickaxe_dig", "minecraft:pickaxes" -> previewOptions.addAll(createVanillaToolPreviewItems("_pickaxe"));
            case "farmersdelight:shovel_dig", "minecraft:shovels" -> previewOptions.addAll(createVanillaToolPreviewItems("_shovel"));
            case "minecraft:shears" -> previewOptions.add(new ItemStack(Material.SHEARS));
            default -> { }
        }
        return finalizeToolPreviewOptions(previewOptions, tool);
    }

    private List<ItemStack> createKnifePreviewItems() {
        List<ItemStack> knives = new ArrayList<>();
        for (String knifeId : plugin.getConfigStringList("knife-items.items", "drops.knife-items.items", "knife-config.items")) {
            ItemStack knife = RecipeIngredientIcons.createItemFromKey(Key.of(knifeId));
            if (isDisplayableItem(knife)) {
                knives.add(knife);
            }
        }
        if (knives.isEmpty()) {
            knives.add(new ItemStack(Material.IRON_SWORD));
        }
        return knives;
    }

    private List<ItemStack> createVanillaToolPreviewItems(String suffix) {
        List<ItemStack> items = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isItem()) continue;
            String name = material.name();
            if (name.endsWith(suffix.toUpperCase(Locale.ROOT))) {
                items.add(new ItemStack(material));
            }
        }
        if (items.isEmpty()) {
            // Last resort: show at least one iron tool.
            items.add(switch (suffix) {
                case "_axe" -> new ItemStack(Material.IRON_AXE);
                case "_pickaxe" -> new ItemStack(Material.IRON_PICKAXE);
                case "_shovel" -> new ItemStack(Material.IRON_SHOVEL);
                default -> new ItemStack(Material.IRON_AXE);
            });
        }
        return items;
    }

    private List<ItemStack> finalizeToolPreviewOptions(
            List<ItemStack> candidates,
            CuttingBoardRecipe.ToolRequirement tool
    ) {
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        for (ItemStack item : candidates) {
            if (isDisplayableItem(item) && !isExcludedToolPreview(item, tool)) {
                unique.putIfAbsent(RecipeIngredientIcons.buildIngredientDisplayKey(item), item);
            }
        }
        return RecipeIngredientIcons.sortIngredientDisplayItems(unique.values());
    }

    private boolean isExcludedToolPreview(ItemStack item, CuttingBoardRecipe.ToolRequirement tool) {
        if (tool.excludedItems().stream().anyMatch(excluded -> ItemUtils.matchesItemId(item, excluded))) {
            return true;
        }
        Set<String> customTags = ItemUtils.getItemTagIds(item);
        for (Key excludedTag : tool.excludedTags()) {
            if (customTags.contains(excludedTag.toString())
                    || ItemUtils.matchesVanillaItemTag(item, excludedTag, Set.of(), Set.of())) {
                return true;
            }
        }
        return false;
    }

    private boolean isDisplayableItem(ItemStack item) {
        return item != null && item.getType() != Material.BARRIER && !item.getType().isAir();
    }

    private void setGuiItem(RecipeViewGuiConfig.BaseConfig guiConfig, String itemKey, int slot) {
        if (slot < 0) return;
        GuiConfig.GuiItem item = guiConfig.getItem(itemKey);
        if (item != null) {
            inventory.setItem(slot, item.createItem());
        }
    }

    private String applyTitlePlaceholders(String title, Map<String, String> placeholders) {
        String result = "GUI";
        if (title != null) {
            result = title;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String resolveMenuTitle(String guiPath, String legacyPath, String fallbackTitle, Map<String, String> placeholders) {
        String title = applyTitlePlaceholders(fallbackTitle, placeholders);
        String offset = "";
        String icon = "";

        var guiSection = plugin.getRecipeViewGuiSection();
        var currentLayout = guiSection != null
                ? guiSection.getConfigurationSection(guiPath + ".title-layout.craftengine")
                : null;
        if (currentLayout != null) {
            offset = parseOffset(currentLayout.getString("offset", ""));
            icon = currentLayout.getString("icon", "");
        }

        String composed = title.replace("<offset>", offset).replace("<icon>", icon);
        return parseShiftTags(composed);
    }

    private String parseOffset(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher matcher = SHIFT_TAG_PATTERN.matcher(raw);
        if (!matcher.matches()) {
            return raw;
        }
        int amount = Integer.parseInt(matcher.group(1));
        if (amount < 0) {
            return getNegativeSpace(Math.abs(amount));
        }
        return "";
    }

    private String parseShiftTags(String title) {
        String source = "";
        if (title != null) {
            source = title;
        }
        Matcher matcher = SHIFT_TAG_PATTERN.matcher(source);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            int amount = Integer.parseInt(matcher.group(1));
            String replacement = "";
            if (amount < 0) {
                replacement = Matcher.quoteReplacement(getNegativeSpace(Math.abs(amount)));
            }
            matcher.appendReplacement(builder, replacement);
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String getNegativeSpace(int amount) {
        StringBuilder builder = new StringBuilder();
        int remaining = amount;
        while (remaining > 0) {
            if (remaining >= 128) { builder.append("\uF80C"); remaining -= 128; }
            else if (remaining >= 64) { builder.append("\uF80B"); remaining -= 64; }
            else if (remaining >= 32) { builder.append("\uF80A"); remaining -= 32; }
            else if (remaining >= 16) { builder.append("\uF809"); remaining -= 16; }
            else if (remaining >= 8) { builder.append("\uF808"); remaining -= 8; }
            else if (remaining >= 7) { builder.append("\uF807"); remaining -= 7; }
            else if (remaining >= 6) { builder.append("\uF806"); remaining -= 6; }
            else if (remaining >= 5) { builder.append("\uF805"); remaining -= 5; }
            else if (remaining >= 4) { builder.append("\uF804"); remaining -= 4; }
            else if (remaining >= 3) { builder.append("\uF803"); remaining -= 3; }
            else if (remaining >= 2) { builder.append("\uF802"); remaining -= 2; }
            else { builder.append("\uF801"); remaining -= 1; }
        }
        return builder.toString();
    }

    private void fillBackground(RecipeViewGuiConfig.BaseConfig guiConfig) {
        if (!config.isBackgroundItemsEnabled()) {
            return;
        }
        for (int i = 0; i < guiConfig.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                String slotType = guiConfig.getSlotType(i);
                GuiConfig.GuiItem slotItem = slotType == null ? null : guiConfig.getItem(slotType);
                if (slotItem != null) {
                    inventory.setItem(i, slotItem.createItem());
                } else if (slotType != null) {
                    inventory.setItem(i, createBackgroundItem(guiConfig));
                }
            }
        }
    }

    ItemStack createBackgroundItem(RecipeViewGuiConfig.BaseConfig guiConfig) {
        GuiConfig.GuiItem background = guiConfig == null ? null : guiConfig.getItem("background");
        return background == null ? EMPTY_SLOT_BACKGROUND.clone() : background.createItem();
    }

    private ItemStack createCookingPotRecipeDisplayItem(CookingPotRecipe recipe, Player player) {
        ItemStack result = recipe.getResult().clone();
        ItemMeta meta = result.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(tr("gui.recipe.ingredients_label", NamedTextColor.GRAY));
        List<RecipeIngredient> ingredients = recipe.getIngredients();
        int displayedIngredients = Math.min(ingredients.size(), config.getRecipeListMaxPreviewIngredients());
        for (int i = 0; i < displayedIngredients; i++) {
            appendCompactIngredientLore(lore, ingredients.get(i), player);
        }
        appendMoreIngredientsLine(lore, ingredients.size() - displayedIngredients, player);
        if (recipe.needsContainer() && recipe.getContainer() != null) {
            lore.add(tr("gui.recipe.container_line",
                    itemNameComponent(recipe.getContainer(), player).colorIfAbsent(NamedTextColor.AQUA)));
        }
        if (recipe.getExperience() > 0.0D || recipe.getCookTime() > 0) {
            // Use the shared cookTimeSeconds() (ceil, min 1s) so the list preview matches the detail
            // screen; raw integer /20 shows a misleading "0s" for sub-20-tick recipes.
            String cookTimeStr = cookTimeSeconds(recipe)
                    + i18nOrDefault(player);
            lore.add(tr("gui.recipe.cook_time_line",
                    Component.text(cookTimeStr).color(NamedTextColor.AQUA)));
        }
        lore.add(Component.text(""));
        lore.add(tr("gui.recipe.click_to_view", NamedTextColor.YELLOW));

        meta.lore(lore);
        result.setItemMeta(meta);
        return result;
    }

    private ItemStack createCuttingBoardRecipeDisplayItem(CuttingBoardRecipe recipe, Player player) {
        ItemStack input = recipe.getInputDisplay().clone();
        ItemMeta meta = input.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(tr("gui.recipe.tool_line",
                formatToolListComponent(recipe.getTools(), player).colorIfAbsent(NamedTextColor.YELLOW)));
        // Show the input type so players can see when multiple alternatives exist without opening details.
        appendCuttingBoardInputLore(lore, recipe.getInput(), player);
        lore.add(tr("gui.recipe.results_label", NamedTextColor.GRAY));
        int maxPreview = config.getRecipeListMaxPreviewIngredients();
        List<CuttingBoardRecipe.ResultEntry> results = recipe.getResults();
        int displayed = Math.min(results.size(), maxPreview);
        for (int i = 0; i < displayed; i++) {
            CuttingBoardRecipe.ResultEntry result = results.get(i);
            Component line = itemNameComponent(result.item(), player).colorIfAbsent(NamedTextColor.WHITE);
            if (result.chance() < 1.0d) {
                line = line.append(Component.text(" (" + (int) Math.round(result.chance() * 100) + "%)", NamedTextColor.GRAY));
            }
            lore.add(colored("&8- ").append(line));
            if (config.isShowIngredientIds()) {
                lore.add(colored("&7  " + RecipeIngredientIcons.buildIngredientDisplayKey(result.item())));
            }
        }
        int remaining = results.size() - displayed;
        if (remaining > 0) {
            lore.add(tr("gui.recipe.more_items", remaining));
        }
        lore.add(Component.text(""));
        lore.add(tr("gui.recipe.click_to_view", NamedTextColor.YELLOW));

        meta.lore(lore);
        input.setItemMeta(meta);
        return input;
    }

    private void appendCuttingBoardInputLore(List<Component> lore, RecipeIngredient input, Player player) {
        if (input instanceof RecipeIngredient.Item) {
            return; // A single item is already identifiable from its icon.
        }
        // Tag/choice input: list matching names in the same compact format used for cooking-pot ingredients.
        appendCompactIngredientLore(lore, input, player);
    }

    private void appendCompactIngredientLore(List<Component> lore, RecipeIngredient ingredient, Player player) {
        List<Component> lines = formatCompactIngredientLoreLines(ingredient, player);
        if (lines.isEmpty()) {
            return;
        }
        lore.add(colored("&8- ").append(lines.getFirst().colorIfAbsent(NamedTextColor.WHITE)));
        for (int i = 1; i < lines.size(); i++) {
            lore.add(colored("&8  ").append(lines.get(i).colorIfAbsent(NamedTextColor.WHITE)));
        }
    }

    private void appendMoreIngredientsLine(List<Component> lore, int remainingCount, Player player) {
        if (remainingCount <= 0) {
            return;
        }
        lore.add(tr("gui.recipe.more_ingredients", remainingCount));
        lore.add(tr("gui.recipe.click_to_view_materials", NamedTextColor.YELLOW));
    }

    ItemStack createIngredientDisplay(RecipeIngredient ingredient, Player player, int slot) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            List<Component> lore = new ArrayList<>();
            ItemStack display = RecipeIngredientIcons.createItemFromKey(itemIngredient.key());
            lore.add(tr("gui.recipe.ingredient", NamedTextColor.GRAY));
            if (config.isShowIngredientIds()) {
                lore.add(colored("&7" + itemIngredient.key()));
            }
            return createLabeledIngredientDisplay(display, lore, player, itemNameComponent(display, player));
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return createAnimatedOrStaticIngredientDisplay(slot, tagIngredient, RecipeIngredientIcons.resolveTagIngredientOptions(tagIngredient), player);
        }

        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            return createAnimatedOrStaticIngredientDisplay(slot, choiceIngredient, RecipeIngredientIcons.resolveIngredientOptions(choiceIngredient), player);
        }

        return createUnknownIngredientDisplay(player);
    }

    private ItemStack createAnimatedIngredientDisplay(RecipeIngredient ingredient, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return createTagIngredientDisplay(tagIngredient, currentDisplay, options, player);
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            return createChoiceIngredientDisplay(choiceIngredient, currentDisplay, options, player);
        }

        ItemStack display = currentDisplay.clone();
        return createLabeledIngredientDisplay(
                display,
                List.of(tr("gui.recipe.ingredient", NamedTextColor.GRAY)),
                player,
                itemNameComponent(currentDisplay, player)
        );
    }

    private ItemStack createTagIngredientDisplay(RecipeIngredient.Tag tagIngredient, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        ItemStack display = currentDisplay.clone();
        ItemMeta meta = display.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(tr("gui.recipe.ingredient", NamedTextColor.GRAY));
        lore.add(tr("gui.recipe.matches_line",
                Component.text(options.size()).color(NamedTextColor.YELLOW)));
        appendCyclePosition(lore, currentDisplay, options, player);
        if (config.isShowIngredientIds()) {
            lore.add(tr("gui.recipe.tag_line",
                    Component.text("#" + tagIngredient.key()).color(NamedTextColor.WHITE)));
            appendTagExclusions(lore, tagIngredient, player);
        }

        appendItemPreviewLore(lore, options, 5, player, currentDisplay);

        meta.displayName(itemNameComponent(currentDisplay, player).colorIfAbsent(NamedTextColor.AQUA));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack createChoiceIngredientDisplay(RecipeIngredient.Choice choiceIngredient, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        ItemStack display = currentDisplay.clone();
        ItemMeta meta = display.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(tr("gui.recipe.ingredient", NamedTextColor.GRAY));
        lore.add(tr("gui.recipe.any_of_line",
                Component.text(choiceIngredient.options().size()).color(NamedTextColor.YELLOW)));
        lore.add(tr("gui.recipe.matches_line",
                Component.text(options.size()).color(NamedTextColor.YELLOW)));
        appendCyclePosition(lore, currentDisplay, options, player);
        appendIngredientPreviewLore(lore, choiceIngredient.options(), choiceIngredient.options().size(), player, currentDisplay);

        meta.displayName(itemNameComponent(currentDisplay, player).colorIfAbsent(NamedTextColor.AQUA));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private void appendTagExclusions(List<Component> lore, RecipeIngredient.Tag tagIngredient, Player player) {
        if (!config.isShowIngredientIds()) {
            return;
        }
        for (Key excludedItem : tagIngredient.excludedItems()) {
            lore.add(colored("&c- ").append(itemNameComponent(RecipeIngredientIcons.createItemFromKey(excludedItem), player).colorIfAbsent(NamedTextColor.RED)));
        }
        for (Key excludedTag : tagIngredient.excludedTags()) {
            lore.add(colored("&c- #" + excludedTag));
        }
    }

    private ItemStack createAnimatedOrStaticIngredientDisplay(
            int slot,
            RecipeIngredient ingredient,
            List<ItemStack> options,
            Player player
    ) {
        if (!options.isEmpty()) {
            animatedIngredientSlots.put(slot, options);
            animatedIngredientIndices.put(slot, 0);
            animatedIngredientDefinitions.put(slot, ingredient);
            return createAnimatedIngredientDisplay(ingredient, options.getFirst(), options, player);
        }

        return createIngredientPlaceholderDisplay(ingredient, player);
    }

    private ItemStack createIngredientPlaceholderDisplay(RecipeIngredient ingredient, Player player) {
        return createLabeledIngredientDisplay(
                new ItemStack(Material.NAME_TAG),
                formatIngredientLoreLines(ingredient, player),
                player,
                tr("gui.recipe.ingredient", NamedTextColor.AQUA)
        );
    }

    private ItemStack createUnknownIngredientDisplay(Player player) {
        return createLabeledIngredientDisplay(
                new ItemStack(Material.BARRIER),
                List.of(tr("gui.recipe.unknown", NamedTextColor.WHITE)),
                player,
                tr("gui.recipe.ingredient", NamedTextColor.AQUA)
        );
    }

    private ItemStack createLabeledIngredientDisplay(ItemStack baseDisplay, List<Component> lore, Player player, Component displayName) {
        ItemStack display = baseDisplay.clone();
        ItemMeta meta = display.getItemMeta();
        meta.displayName(displayName.colorIfAbsent(NamedTextColor.AQUA));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }


    private List<Component> formatCompactIngredientLoreLines(RecipeIngredient ingredient, Player player) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return List.of(itemNameComponent(RecipeIngredientIcons.createItemFromKey(itemIngredient.key()), player));
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            List<ItemStack> options = RecipeIngredientIcons.resolveIngredientOptions(choiceIngredient);
            if (options.isEmpty()) {
                return List.of(tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
            }
            return formatCompactItemOptions(options, player);
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            // Clone only the first 6 for showing names; read the full count from the cache.
            int totalSize = RecipeIngredientIcons.resolveTagIngredientOptionsSize(tagIngredient);
            if (totalSize == 0) {
                return List.of(tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
            }
            List<ItemStack> previewOptions = RecipeIngredientIcons.resolveTagIngredientOptionsPreview(tagIngredient, 6);
            int previewCount = previewOptions.size();
            List<Component> lines = formatCompactItemOptions(previewOptions, player);
            if (totalSize > previewCount) {
                lines.add(tr("gui.recipe.more_items",
                        totalSize - previewCount));
            }
            return lines;
        }
        return List.of(tr("gui.recipe.unknown", NamedTextColor.WHITE));
    }

    private List<Component> formatCompactItemOptions(List<ItemStack> options, Player player) {
        List<Component> lines = new ArrayList<>();
        Component current = Component.empty();
        int currentLength = 0;
        boolean hasCurrent = false;

        for (ItemStack option : options) {
            Component name = itemNameComponent(option, player).colorIfAbsent(NamedTextColor.WHITE);
            int nameLength = Math.max(1, getItemDisplayName(option, player).length());
            int extraLength = hasCurrent ? nameLength + 2 : nameLength;
            if (hasCurrent && currentLength + extraLength > MAX_COMPACT_INGREDIENT_LINE_LENGTH) {
                lines.add(current);
                current = Component.empty();
                currentLength = 0;
                hasCurrent = false;
            }
            if (hasCurrent) {
                current = current.append(Component.text(", ", NamedTextColor.GRAY));
                currentLength += 2;
            }
            current = current.append(name);
            currentLength += nameLength;
            hasCurrent = true;
        }

        if (hasCurrent) {
            lines.add(current);
        }
        return lines;
    }

    List<Component> formatIngredientLoreLines(RecipeIngredient ingredient, Player player) {
        List<Component> lines = new ArrayList<>();
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            lines.add(itemNameComponent(RecipeIngredientIcons.createItemFromKey(itemIngredient.key()), player).colorIfAbsent(NamedTextColor.WHITE));
            return lines;
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            List<ItemStack> options = RecipeIngredientIcons.resolveTagIngredientOptions(tagIngredient);
            if (options.isEmpty()) {
                lines.add(tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
                if (config.isShowIngredientIds()) {
                    lines.add(colored("&8#" + tagIngredient.key()));
                }
                return lines;
            }
            appendItemPreviewLore(lines, options, player);
            if (config.isShowIngredientIds()) {
                lines.add(colored("&8#" + tagIngredient.key()));
                appendTagExclusions(lines, tagIngredient, player);
            }
            return lines;
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            List<ItemStack> options = RecipeIngredientIcons.resolveIngredientOptions(choiceIngredient);
            if (options.isEmpty()) {
                lines.add(tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
                return lines;
            }
            appendItemPreviewLore(lines, options, player);
            return lines;
        }
        lines.add(tr("gui.recipe.unknown", NamedTextColor.WHITE));
        return lines;
    }

    private Component formatToolListComponent(List<CuttingBoardRecipe.ToolRequirement> tools, Player player) {
        Component result = Component.empty();
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                result = result.append(Component.text(", ", NamedTextColor.GRAY));
            }
            result = result.append(itemNameComponent(createToolPreviewItem(tools.get(i)), player));
        }
        return result;
    }

    private void appendItemPreviewLore(List<Component> lore, List<ItemStack> options, Player player) {
        appendItemPreviewLore(lore, options, 5, player, null);
    }

    private void appendItemPreviewLore(
            List<Component> lore,
            List<ItemStack> options,
            int previewLimit,
            Player player,
            ItemStack currentDisplay
    ) {
        List<ItemStack> previewOptions = filterCurrentPreviewOption(options, currentDisplay);
        if (previewOptions.isEmpty()) {
            return;
        }

        int displayed = Math.min(previewOptions.size(), previewLimit);
        for (int i = 0; i < displayed; i++) {
            lore.add(colored("&8- ").append(itemNameComponent(previewOptions.get(i), player).colorIfAbsent(NamedTextColor.WHITE)));
        }
        appendMoreItemsLine(lore, previewOptions.size() - displayed, player);
    }

    private List<ItemStack> filterCurrentPreviewOption(List<ItemStack> options, ItemStack currentDisplay) {
        if (currentDisplay == null) {
            return options;
        }
        String currentKey = RecipeIngredientIcons.buildIngredientDisplayKey(currentDisplay);
        List<ItemStack> filtered = new ArrayList<>();
        for (ItemStack option : options) {
            if (!RecipeIngredientIcons.buildIngredientDisplayKey(option).equals(currentKey)) {
                filtered.add(option);
            }
        }
        return filtered;
    }

    private void appendIngredientPreviewLore(
            List<Component> lore,
            List<RecipeIngredient> options,
            int previewLimit,
            Player player,
            ItemStack currentDisplay
    ) {
        LinkedHashMap<String, ItemStack> displayOptions = new LinkedHashMap<>();
        for (RecipeIngredient option : options) {
            for (ItemStack display : RecipeIngredientIcons.resolveIngredientOptions(option)) {
                if (!isDisplayableItem(display)) {
                    continue;
                }
                displayOptions.putIfAbsent(RecipeIngredientIcons.buildIngredientDisplayKey(display), display);
            }
        }

        if (displayOptions.isEmpty()) {
            lore.add(tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
            return;
        }

        appendItemPreviewLore(lore, new ArrayList<>(displayOptions.values()), previewLimit, player, currentDisplay);
    }

    private void appendCyclePosition(List<Component> lore, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        if (options.size() <= 1) {
            return;
        }
        String currentKey = RecipeIngredientIcons.buildIngredientDisplayKey(currentDisplay);
        int currentIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            if (RecipeIngredientIcons.buildIngredientDisplayKey(options.get(i)).equals(currentKey)) {
                currentIndex = i + 1;
                break;
            }
        }
        lore.add(tr("gui.recipe.auto_cycle",
                Math.max(1, currentIndex), options.size()));
    }

    private void appendMoreItemsLine(List<Component> lore, int remainingCount, Player player) {
        if (remainingCount <= 0) {
            return;
        }
        lore.add(tr("gui.recipe.more_items", remainingCount));
    }

    private String unknownRecipeText(Player player) {
        return I18n.get("gui.recipe.unknown", player);
    }

    private String getItemDisplayName(ItemStack item, Player player) {
        if (item == null) {
            return unknownRecipeText(player);
        }
        return ItemUtils.getDisplayName(item, player);
    }

    Component itemNameComponent(ItemStack item, Player player) {
        return ItemUtils.getDisplayComponent(item, player)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    String i18nOrDefault(Player player) {
        String value = I18n.get("gui.recipe.seconds_suffix", player);
        if ("gui.recipe.seconds_suffix".equals(value)) {
            return "s";
        }
        return value;
    }

    Component tr(String key, NamedTextColor color) {
        return Component.translatable(key)
                .color(color)
                .decoration(TextDecoration.ITALIC, false);
    }

    Component tr(String key, Object... args) {
        Component[] components = new Component[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            components[i] = a instanceof Component c ? c : Component.text(String.valueOf(a));
        }
        return Component.translatable(key, components)
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    Component colored(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        // \u652F\u6301 MiniMessage \u6807\u7B7E\u4EE5\u53CA\u65E7\u7248 &/\u00A7 \u989C\u8272\u4EE3\u7801\uFF1B\u5F3A\u5236\u5173\u95ED\u659C\u4F53\uFF08\u7269\u54C1 lore/\u540D\u79F0\u9ED8\u8BA4\u4F1A\u4EE5\u659C\u4F53
        // \u6E32\u67D3\uFF09\uFF0C\u8FD9\u6837\u5F00\u5934\u7684\u989C\u8272\u7247\u6BB5\u4EE5\u53CA\u6BCF\u4E2A\u8FFD\u52A0\u7684\u5B50\u8282\u70B9\u90FD\u662F\u76F4\u7ACB\u7684\uFF0C\u9664\u975E\u6587\u672C\u660E\u786E\u8981\u6C42\u4F7F\u7528\u659C\u4F53\u3002
        return Text.deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private Component coloredComponent(String text) {
        String resolved = "";
        if (text != null) {
            resolved = text;
        }
        if (resolved.contains("<") && resolved.contains(">")) {
            return MINI_MESSAGE.deserialize(resolved);
        }
        String normalized = resolved.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7");
        return LEGACY.deserialize(normalized);
    }

    void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) return;
        event.setCancelled(true);

        if (closed) return;

        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) return;

        switch (state) {
            case MAIN_MENU -> handleMainMenuClick(player, slot);
            case COOKING_POT_LIST -> handleCookingPotListClick(player, slot);
            case CUTTING_BOARD_LIST -> handleCuttingBoardListClick(player, slot);
            case RECIPE_DETAIL -> handleRecipeDetailClick(player, slot, event.isShiftClick());
        }
    }

    private void handleMainMenuClick(Player player, int slot) {
        RecipeViewGuiConfig.MainMenuConfig menuConfig = config.getMainMenu();

        if (slot == menuConfig.getCookingPotSlot()) {
            backButtonCommandsEnabled = false;
            navigateToState(player, GuiState.COOKING_POT_LIST);
        } else if (slot == menuConfig.getCuttingBoardSlot()) {
            backButtonCommandsEnabled = false;
            navigateToState(player, GuiState.CUTTING_BOARD_LIST);
        } else if (slot == menuConfig.getRecipeBookSlot()
                && !com.huidu.farmersdelight.api.FarmersDelightApi.get().recipeTypes().isEmpty()) {
            // Hand off to the generic addon recipe book (deferred a tick, like the editor handoff).
            plugin.scheduler().runLaterForEntity(player, () -> {
                if (player.isOnline()) {
                    // Backing out of the addon book returns to this recipe menu (where the player came from)
                    // instead of closing, which would strand them.
                    com.huidu.farmersdelight.gui.recipebook.RecipeBookGui.openMenu(player, null,
                            () -> new RecipeViewGui(plugin, player).open(player));
                }
            }, 1L);
        } else if (slot == menuConfig.getBackSlot()) {
            if (runBackButtonCommands(player, menuConfig.getItem("back"))) {
                return;
            }
            closeGui(player);
        }
    }

    private void handleCookingPotListClick(Player player, int slot) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CookingPotRecipe> recipes = plugin.getCookingPotRecipes().getSortedRecipes(getActiveCookingPotRecipeGroup());
        if (craftableOnly) {
            recipes = filterCraftableCookingPotRecipes(recipes);
        }
        recipes = applyDiscoveryFilter(recipes, true, player);

        handleRecipeListClick(player, slot, listConfig, recipes, true);
    }

    private void handleCuttingBoardListClick(Player player, int slot) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CuttingBoardRecipe> recipes = plugin.getCuttingBoardRecipes().getSortedRecipes();
        if (craftableOnly) {
            recipes = filterCraftableCuttingBoardRecipes(recipes);
        }
        recipes = applyDiscoveryFilter(recipes, false, player);

        handleRecipeListClick(player, slot, listConfig, recipes, false);
    }

    private <T> void handleRecipeListClick(Player player, int slot, RecipeViewGuiConfig.RecipeListConfig listConfig,
                                           List<T> recipes, boolean isCookingPot) {
        List<Integer> recipeSlots = listConfig.getRecipeSlots();
        int itemsPerPage = recipeSlots.size();
        int totalPages = (int) Math.ceil(recipes.size() / (double) itemsPerPage);

        if (slot == listConfig.getPrevPageSlot() && currentPage > 0) {
            changePage(player, -1);
        } else if (slot == listConfig.getNextPageSlot() && currentPage < totalPages - 1) {
            changePage(player, 1);
        } else if (slot == listConfig.getFilterSlot()) {
            craftableOnly = !craftableOnly;
            currentPage = 0;
            refreshContentsInPlace(player);
        } else if (slot == listConfig.getBackSlot()) {
            if (runBackButtonCommands(player, listConfig.getItem("back"))) {
                return;
            }
            if (fromCookingPot && isCookingPot) {
                closeGui(player);
                returnToCookingPot(player);
            } else if (backButtonCommandsEnabled && !fromCookingPot) {
                // A command-opened top-level list has no menu above it, so back closes instead of dropping the
                // player into a MAIN_MENU they never opened (which would strand them with only a close button).
                closeGui(player);
            } else {
                navigateToState(player, GuiState.MAIN_MENU);
            }
        } else if (recipeSlots.contains(slot)) {
            int slotIndex = recipeSlots.indexOf(slot);
            int recipeIndex = currentPage * itemsPerPage + slotIndex;
        if (recipeIndex < recipes.size()) {
                Object clickedRecipe = recipes.get(recipeIndex);
                if (isRecipeLocked(clickedRecipe, isCookingPot, player)) {
                    player.sendMessage(Component.translatable("recipe-discovery.locked-click").color(NamedTextColor.RED));
                    return;
                }
                String recipeId;
                if (isCookingPot) {
                    recipeId = ((CookingPotRecipe) clickedRecipe).getId();
                } else {
                    recipeId = ((CuttingBoardRecipe) clickedRecipe).getId();
                }
                if (editMode) {
                    closeGui(player);
                    openEditorForRecipe(player, recipeId, isCookingPot);
                    return;
                }
                selectedRecipeId = recipeId;
                cookingPotMode = isCookingPot;
                fillButtonState = FillButtonState.READY;
                recipeBackState = isCookingPot ? GuiState.COOKING_POT_LIST : GuiState.CUTTING_BOARD_LIST;
                // Fresh detail opened from a list: this is a new navigation root, so drop any prior jump chain.
                detailHistory.clear();
                navigateToState(player, GuiState.RECIPE_DETAIL);
            }
        }
    }

    private void handleRecipeDetailClick(Player player, int slot, boolean shiftClick) {
        RecipeViewGuiConfig.RecipeDetailConfig detailConfig = getActiveDetailConfig();
        
        if (slot == detailConfig.getBackSlot()) {
            if (runBackButtonCommands(player, detailConfig.getItem("back"))) {
                return;
            }
            if (!detailHistory.isEmpty()) {
                // Came here via a linked-recipe jump: return to the recipe it was opened from.
                DetailState previous = detailHistory.pop();
                cookingPotMode = previous.cookingPotMode();
                selectedRecipeId = previous.selectedRecipeId();
                recipeBackState = previous.recipeBackState();
                currentToolIndex = 0;
                fillButtonState = FillButtonState.READY;
                navigateToState(player, GuiState.RECIPE_DETAIL);
                return;
            }
            navigateToState(player, recipeBackState);
            return;
        }

        if (fromCookingPot && cookingPotMode && slot == detailConfig.getFillSlot()) {
            FillResult result = fillCookingPotFromInventory(player, shiftClick);
            if (result.returnToPot()) {
                closeGui(player);
                returnToCookingPot(player);
            } else {
                fillButtonState = result.buttonState();
                refreshContentsInPlace(player);
            }
            return;
        }

        if (slot == detailConfig.getArrowSlot()) {
            return;
        }

        ItemStack clickedItem = inventory.getItem(slot);
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }

        LinkedRecipe linkedRecipe = findLinkedRecipe(clickedItem);
        if (linkedRecipe == null) {
            return;
        }
        // Already viewing this exact recipe (e.g. clicking the result of the recipe on screen): don't
        // re-open it, which would needlessly rebuild and "refresh" the page.
        if (linkedRecipe.cookingPot() == cookingPotMode
                && java.util.Objects.equals(linkedRecipe.recipeId(), selectedRecipeId)) {
            return;
        }

        // Block navigation to a locked linked recipe.
        Object linkedTarget = linkedRecipe.cookingPot()
                ? plugin.getCookingPotRecipes().getRecipe(getActiveCookingPotRecipeGroup(), linkedRecipe.recipeId())
                : plugin.getCuttingBoardRecipes().getRecipe(linkedRecipe.recipeId());
        if (linkedTarget != null && isRecipeLocked(linkedTarget, linkedRecipe.cookingPot(), player)) {
            player.sendMessage(Component.translatable("recipe-discovery.locked-click").color(NamedTextColor.RED));
            return;
        }

        // Remember the recipe being left (and its own back destination) so back returns here, not straight to
        // the original list. recipeBackState is snapshotted too, so this recipe's own back still works after a
        // deeper jump chain unwinds to it.
        detailHistory.push(new DetailState(cookingPotMode, selectedRecipeId, recipeBackState));
        selectedRecipeId = linkedRecipe.recipeId();
        cookingPotMode = linkedRecipe.cookingPot();
        currentToolIndex = 0;
        fillButtonState = FillButtonState.READY;
        navigateToState(player, GuiState.RECIPE_DETAIL);
    }

    private LinkedRecipe findLinkedRecipe(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        if (cookingPotMode) {
            LinkedRecipe linked = findCookingPotRecipeByResult(item);
            if (linked != null) {
                return linked;
            }
            return findCuttingBoardRecipeByResult(item);
        }

        LinkedRecipe linked = findCuttingBoardRecipeByResult(item);
        if (linked != null) {
            return linked;
        }
        return findCookingPotRecipeByResult(item);
    }

    private LinkedRecipe findCookingPotRecipeByResult(ItemStack item) {
        for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getSortedRecipes(getActiveCookingPotRecipeGroup())) {
            if (sameRecipeItem(recipe.getResult(), item)) {
                return new LinkedRecipe(recipe.getId(), true);
            }
        }
        return null;
    }

    private LinkedRecipe findCuttingBoardRecipeByResult(ItemStack item) {
        for (CuttingBoardRecipe recipe : plugin.getCuttingBoardRecipes().getSortedRecipes()) {
            for (CuttingBoardRecipe.ResultEntry result : recipe.getResults()) {
                if (sameRecipeItem(result.item(), item)) {
                    return new LinkedRecipe(recipe.getId(), false);
                }
            }
        }
        return null;
    }

    private boolean sameRecipeItem(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.getType().isAir() || actual.getType().isAir()) {
            return false;
        }

        String expectedId = ItemUtils.getCustomItemId(expected);
        String actualId = ItemUtils.getCustomItemId(actual);
        if (expectedId != null || actualId != null) {
            return expectedId != null && expectedId.equals(actualId);
        }

        return expected.getType() == actual.getType();
    }

    private record LinkedRecipe(String recipeId, boolean cookingPot) {}
    private enum FillButtonState {
        READY("fill", "gui.recipe.fill_ready"),
        FILLED("fill-success", "gui.recipe.ingredients_filled"),
        MISSING_INGREDIENTS("fill-missing", "gui.recipe.missing_ingredients"),
        INVENTORY_FULL("fill-inventory-full", "gui.recipe.inventory_full");

        private final String itemKey;
        private final String messageKey;

        FillButtonState(String itemKey, String messageKey) {
            this.itemKey = itemKey;
            this.messageKey = messageKey;
        }

        String itemKey() {
            return itemKey;
        }

        Map<String, String> placeholders(Player player) {
            return Map.of("status", I18n.get(messageKey, player));
        }
    }

    private record FillResult(boolean returnToPot, FillButtonState buttonState) {
        static FillResult returnToPot(FillButtonState buttonState) {
            return new FillResult(true, buttonState);
        }

        static FillResult stay(FillButtonState buttonState) {
            return new FillResult(false, buttonState);
        }
    }

    private List<CookingPotRecipe> filterCraftableCookingPotRecipes(List<CookingPotRecipe> recipes) {
        CookingPotBlockEntity entity = cookingPotLocation == null ? null : CookingPotBlockBehavior.getBlockEntity(cookingPotLocation);
        List<ItemStack> available = getAvailableCookingPotItems(entity);
        List<CookingPotRecipe> craftableRecipes = new ArrayList<>();
        for (CookingPotRecipe recipe : recipes) {
            if (canCraftCookingPotRecipe(recipe, entity, available)) {
                craftableRecipes.add(recipe);
            }
        }
        return craftableRecipes;
    }

    private List<CuttingBoardRecipe> filterCraftableCuttingBoardRecipes(List<CuttingBoardRecipe> recipes) {
        List<ItemStack> available = Arrays.stream(player.getInventory().getStorageContents())
                .filter(Objects::nonNull)
                .filter(item -> !item.isEmpty())
                .toList();
        return plugin.getCuttingBoardRecipes().filterCraftable(recipes, available);
    }

    private boolean canCraftCookingPotRecipe(CookingPotRecipe recipe, CookingPotBlockEntity entity, List<ItemStack> available) {
        if (entity != null && !canRecipeFitCookingPot(recipe, entity)) {
            return false;
        }
        // The container is not part of "can I cook this": the pot cooks from ingredients alone and the bowl is
        // supplied at extraction time, so a meal recipe stays craftable even when the player carries no bowl.
        // Containment question, not the real cook: does the inventory (+ current pot inputs) hold enough of
        // each ingredient, ignoring the unrelated items every inventory carries? canCraft's lenient pass would
        // reject on the first foreign slot, so it can't be used here.
        return plugin.getCookingPotRecipes().containsIngredientsFor(recipe, available);
    }

    private List<ItemStack> getAvailableCookingPotItems(CookingPotBlockEntity entity) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        if (entity != null) {
            for (ItemStack item : entity.getInventory()) {
                if (item != null && !item.getType().isAir()) {
                    items.add(item.clone());
                }
            }
        }
        return items;
    }

    private FillResult fillCookingPotFromInventory(Player player, boolean fillAll) {
        if (!fromCookingPot || cookingPotLocation == null || selectedRecipeId == null) {
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }
        // This runs on the player's Folia region thread (inventory click). All the pot-side work below
        // (getBlockEntity, hopper inserts, markActive/checkHeatSource/saveBlockEntityData) touches the
        // cooking-pot block entity, which must happen on the pot's own region. If the pot is in a
        // different region (player teleported while the GUI stayed open), touching it here is a
        // cross-region access — skip safely rather than throw. Always owned on Paper.
        if (!plugin.scheduler().isOwnedByCurrentRegion(cookingPotLocation)) {
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }
        CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(getActiveCookingPotRecipeGroup(), selectedRecipeId);
        var entity = CookingPotBlockBehavior.getBlockEntity(cookingPotLocation);
        if (recipe == null || entity == null) {
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }

        debugCookingPotFill("start recipe=" + recipe.getId() + ", fillAll=" + fillAll
                + ", before=" + summarizeCookingPot(entity));

        List<InventorySelection> selected = selectRecipeItemsFromInventory(recipe, entity, fillAll);
        boolean hasRequiredContainer = !recipe.needsContainer() || sameRecipeItem(recipe.getContainer(), entity.getContainerItem());
        InventorySelection selectedContainer = !hasRequiredContainer && entity.getLayout().containerSlots().length > 0
                ? selectRecipeContainerFromInventory(recipe, selected)
                : null;

        if (selected.isEmpty() && selectedContainer == null) {
            if (missingIngredients(recipe, getCurrentInputItems(entity)).isEmpty() && hasRequiredContainer) {
                activateCookingPotAfterFill(entity);
                debugCookingPotFill("already matched recipe=" + recipe.getId() + ", after=" + summarizeCookingPot(entity));
                return FillResult.returnToPot(FillButtonState.FILLED);
            }
            debugCookingPotFill("missing recipe=" + recipe.getId() + ", selected=0, hasContainer=" + hasRequiredContainer
                    + ", current=" + summarizeCookingPot(entity));
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }

        int movedCount = 0;

        for (InventorySelection selection : selected) {
            ItemStack source = player.getInventory().getItem(selection.slot());
            if (source == null || source.getType().isAir()) {
                continue;
            }
            ItemStack moved = source.clone();
            moved.setAmount(1);
            ItemStack leftover = CookingPotBlockBehavior.insertIngredientLikeHopper(cookingPotLocation, moved);
            if (leftover != null && !leftover.getType().isAir()) {
                debugCookingPotFill("rollback ingredient leftover=" + summarizeItem(leftover)
                        + ", recipe=" + recipe.getId() + ", after=" + summarizeCookingPot(entity));
                player.updateInventory();
                return FillResult.stay(FillButtonState.INVENTORY_FULL);
            }
            debitInventorySlot(player, selection.slot());
            movedCount++;
        }
        if (selectedContainer != null) {
            ItemStack source = player.getInventory().getItem(selectedContainer.slot());
            if (source == null || source.getType().isAir()) {
                debugCookingPotFill("rollback missing container source recipe=" + recipe.getId()
                        + ", after=" + summarizeCookingPot(entity));
                player.updateInventory();
                return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
            }
            ItemStack container = source.clone();
            container.setAmount(1);
            ItemStack leftover = CookingPotBlockBehavior.insertContainerLikeHopper(cookingPotLocation, container);
            if (leftover != null && !leftover.getType().isAir()) {
                debugCookingPotFill("rollback container leftover=" + summarizeItem(leftover)
                        + ", recipe=" + recipe.getId() + ", after=" + summarizeCookingPot(entity));
                player.updateInventory();
                return FillResult.stay(FillButtonState.INVENTORY_FULL);
            }
            debitInventorySlot(player, selectedContainer.slot());
            movedCount++;
        }
        activateCookingPotAfterFill(entity);
        debugCookingPotFill("filled recipe=" + recipe.getId() + ", moved=" + movedCount
                + ", after=" + summarizeCookingPot(entity));
        player.updateInventory();
        return FillResult.returnToPot(FillButtonState.FILLED);
    }

    private void debitInventorySlot(Player player, int slot) {
        if (player == null || 1 <= 0) {
            return;
        }
        ItemStack source = player.getInventory().getItem(slot);
        if (source == null || source.getType().isAir()) {
            return;
        }
        int remaining = source.getAmount() - 1;
        if (remaining <= 0) {
            player.getInventory().setItem(slot, null);
            return;
        }
        source.setAmount(remaining);
        player.getInventory().setItem(slot, source);
    }

    private void activateCookingPotAfterFill(com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity entity) {
        if (entity == null || cookingPotLocation == null || cookingPotLocation.getWorld() == null) {
            return;
        }
        World world = cookingPotLocation.getWorld();
        TickManager tickManager = plugin.getTickManager();
        if (tickManager != null && entity.hasStoredContents()) {
            tickManager.markActive(world, entity.getPosKey(), TickManager.BlockType.COOKING_POT);
        }
        CookingPotBlockBehavior behavior = CookingPotBlockBehavior.getBlockBehavior(cookingPotLocation);
        if (behavior != null) {
            entity.setHasHeatSource(behavior.checkHeatSource(entity.getPos(), world));
        }
        CookingPotBlockBehavior.saveBlockEntityData(world, entity.getPosKey());
    }

    private void debugCookingPotFill(String message) {
        if (plugin.isDebugEnabled("cooking_pot")) {
            plugin.getLogger().info(I18n.formatConsole("debug.cooking_pot_fill", "message", message));
        }
    }

    private String summarizeCookingPot(CookingPotBlockEntity entity) {
        if (entity == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>();
        for (int slot : entity.getLayout().inputSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                parts.add("i" + slot + "=" + summarizeItem(item));
            }
        }
        for (int slot : entity.getLayout().containerSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                parts.add("c" + slot + "=" + summarizeItem(item));
            }
        }
        for (int slot : entity.getLayout().pendingOutputSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                parts.add("p" + slot + "=" + summarizeItem(item));
            }
        }
        for (int slot : entity.getLayout().outputSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                parts.add("o" + slot + "=" + summarizeItem(item));
            }
        }
        return parts.isEmpty() ? "empty" : String.join(",", parts);
    }

    private String summarizeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "empty";
        }
        String customId = ItemUtils.getCustomItemId(item);
        String id = customId != null ? customId : item.getType().name();
        return id + "x" + item.getAmount();
    }

    private List<ItemStack> getCurrentInputItems(com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity entity) {
        if (entity == null) {
            return List.of();
        }
        List<ItemStack> items = new ArrayList<>();
        for (int slot : entity.getLayout().inputSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        return items;
    }

    private List<RecipeIngredient> missingIngredients(CookingPotRecipe recipe, List<ItemStack> currentItems) {
        if (recipe == null) {
            return List.of();
        }
        List<ItemStack> available = new ArrayList<>();
        if (currentItems != null) {
            for (ItemStack item : currentItems) {
                if (item != null && !item.getType().isAir()) {
                    available.add(item.clone());
                }
            }
        }

        List<RecipeIngredient> missing = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            int matchIndex = findMatchingAvailableStack(available, ingredient);
            if (matchIndex < 0) {
                missing.add(ingredient);
                continue;
            }
            ItemStack matched = available.get(matchIndex);
            matched.setAmount(matched.getAmount() - 1);
            if (matched.getAmount() <= 0) {
                available.remove(matchIndex);
            }
        }
        return missing;
    }

    private int findMatchingAvailableStack(List<ItemStack> available, RecipeIngredient ingredient) {
        for (int i = 0; i < available.size(); i++) {
            ItemStack item = available.get(i);
            if (item != null && item.getAmount() > 0 && plugin.getCookingPotRecipes().matchesIngredient(item, ingredient)) {
                return i;
            }
        }
        return -1;
    }

    private boolean canRecipeFitCookingPot(CookingPotRecipe recipe, CookingPotBlockEntity entity) {
        if (recipe == null || entity == null) {
            return false;
        }
        return recipe.getIngredients().size() <= entity.getLayout().inputSlots().length;
    }

    private List<InventorySelection> selectRecipeItemsFromInventory(
            CookingPotRecipe recipe,
            com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity entity,
            boolean fillAll
    ) {
        List<InventorySelection> available = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            for (int amount = 0; amount < item.getAmount(); amount++) {
                ItemStack one = item.clone();
                one.setAmount(1);
                available.add(new InventorySelection(slot, one));
            }
        }

        List<InventorySelection> selected = new ArrayList<>();
        List<RecipeIngredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || entity == null) {
            return selected;
        }

        ItemStack[] simulatedSlots = createInputSlotSimulation(entity);
        boolean firstPass = true;
        do {
            int selectedBefore = selected.size();
            List<RecipeIngredient> needed = firstPass
                    ? missingIngredients(recipe, Arrays.asList(simulatedSlots))
                    : ingredients;
            if (needed.isEmpty()) {
                if (!fillAll) {
                    break;
                }
                needed = ingredients;
            }

            for (RecipeIngredient ingredient : needed) {
                int matchIndex = findMatchingAvailableIngredient(available, ingredient, simulatedSlots);
                if (matchIndex < 0) {
                    return selected;
                }
                InventorySelection selection = available.remove(matchIndex);
                insertIntoSimulatedSlots(simulatedSlots, selection.item());
                selected.add(selection);
            }

            firstPass = false;
            if (!fillAll || selected.size() == selectedBefore) {
                break;
            }
        } while (true);
        return selected;
    }

    private ItemStack[] createInputSlotSimulation(com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity entity) {
        int[] inputSlots = entity.getLayout().inputSlots();
        ItemStack[] simulated = new ItemStack[inputSlots.length];
        for (int i = 0; i < inputSlots.length; i++) {
            simulated[i] = entity.getInventorySlot(inputSlots[i]);
        }
        return simulated;
    }

    private int findMatchingAvailableIngredient(List<InventorySelection> available, RecipeIngredient ingredient, ItemStack[] simulatedSlots) {
        for (int i = 0; i < available.size(); i++) {
            ItemStack item = available.get(i).item();
            if (plugin.getCookingPotRecipes().matchesIngredient(item, ingredient) && canInsertIntoSimulatedSlots(simulatedSlots, item)) {
                return i;
            }
        }
        return -1;
    }

    private boolean canInsertIntoSimulatedSlots(ItemStack[] slots, ItemStack item) {
        if (slots == null || item == null || item.getType().isAir()) {
            return false;
        }
        for (ItemStack slotItem : slots) {
            if (slotItem != null && !slotItem.getType().isAir() && slotItem.isSimilar(item)
                    && slotItem.getAmount() < slotItem.getMaxStackSize()) {
                return true;
            }
        }
        for (ItemStack slotItem : slots) {
            if (slotItem == null || slotItem.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private void insertIntoSimulatedSlots(ItemStack[] slots, ItemStack item) {
        if (slots == null || item == null || item.getType().isAir()) {
            return;
        }
        for (ItemStack slotItem : slots) {
            if (slotItem != null && !slotItem.getType().isAir() && slotItem.isSimilar(item)
                    && slotItem.getAmount() < slotItem.getMaxStackSize()) {
                slotItem.setAmount(slotItem.getAmount() + 1);
                return;
            }
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null || slots[i].getType().isAir()) {
                ItemStack placed = item.clone();
                placed.setAmount(1);
                slots[i] = placed;
                return;
            }
        }
    }

    private InventorySelection selectRecipeContainerFromInventory(CookingPotRecipe recipe, List<InventorySelection> reservedItems) {
        if (recipe == null || !recipe.needsContainer()) {
            return null;
        }
        ItemStack required = recipe.getContainer();
        if (required == null || required.getType().isAir()) {
            return null;
        }
        Map<Integer, Integer> reservedBySlot = new HashMap<>();
        if (reservedItems != null) {
            for (InventorySelection selection : reservedItems) {
                reservedBySlot.merge(selection.slot(), 1, Integer::sum);
            }
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (item.getAmount() <= reservedBySlot.getOrDefault(slot, 0)) {
                continue;
            }
            if (sameRecipeItem(required, item)) {
                ItemStack selected = item.clone();
                selected.setAmount(1);
                return new InventorySelection(slot, selected);
            }
        }
        return null;
    }

    private record InventorySelection(int slot, ItemStack item) {}

    private RecipeViewGuiConfig.RecipeDetailConfig getActiveDetailConfig() {
        if (cookingPotMode) {
            return getActiveCookingPotDetailConfig();
        }
        return config.getCuttingBoardDetail();
    }

    private RecipeViewGuiConfig.RecipeDetailConfig getActiveCookingPotDetailConfig() {
        String customId = getActiveCookingPotRecipeGroup();
        if (customId != null && !customId.isBlank() && !config.hasCustomCookingPotDetail(customId)
                && warnedMissingCustomCookingPotDetailConfigs.add(customId)) {
            plugin.getLogger().warning(I18n.formatNamedArgs("console.gui.missing_custom_recipe_detail",
                    "id", customId));
        }
        RecipeViewGuiConfig.RecipeDetailConfig detailConfig = config.getCookingPotDetail(customId);
        warnIfCookingPotDetailTooSmall(customId, detailConfig);
        return detailConfig;
    }

    private void warnIfCookingPotDetailTooSmall(String customId, RecipeViewGuiConfig.RecipeDetailConfig detailConfig) {
        String warningKey = customId == null || customId.isBlank() ? "default" : customId;
        if (!warnedCookingPotDetailCapacityConfigs.add(warningKey)) {
            return;
        }
        int visibleIngredients = detailConfig.getIngredientSlots().size();
        int maxIngredients = 0;
        String maxRecipeId = null;
        for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getRecipes(customId).values()) {
            int ingredients = recipe.getIngredients().size();
            if (ingredients > maxIngredients) {
                maxIngredients = ingredients;
                maxRecipeId = recipe.getId();
            }
        }
        if (maxIngredients > visibleIngredients) {
            plugin.getLogger().warning(I18n.formatNamedArgs("console.gui.recipe_detail_capacity",
                    "id", warningKey,
                    "recipe", maxRecipeId,
                    "ingredients", maxIngredients,
                    "slots", visibleIngredients));
        }
    }

    private void openEditorForRecipe(Player player, String recipeId, boolean isCookingPot) {
        plugin.scheduler().runLaterForEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (isCookingPot) {
                RecipeViewGuiConfig.BaseConfig editorConfig =
                        plugin.getRecipeEditorGuiConfig().getCookingPotConfig(null);
                if (editorConfig == null) {
                    return;
                }
                CookingPotRecipe existing = plugin.getCookingPotRecipes().getRecipe(recipeId);
                new com.huidu.farmersdelight.gui.editor.CookingPotEditorGui(
                        plugin, player, recipeId, null, existing, editorConfig).open();
            } else {
                RecipeViewGuiConfig.BaseConfig boardConfig =
                        plugin.getRecipeEditorGuiConfig().getCuttingBoardConfig();
                if (boardConfig == null) {
                    return;
                }
                CuttingBoardRecipe existing = plugin.getCuttingBoardRecipes().getRecipe(recipeId);
                new com.huidu.farmersdelight.gui.editor.CuttingBoardEditorGui(
                        plugin, player, recipeId, existing, boardConfig).open();
            }
        }, 1L);
    }

    private String getActiveCookingPotRecipeGroup() {
        if (!fromCookingPot || cookingPotLocation == null) {
            return null;
        }
        if (!recipeGroupResolved) {
            CookingPotBlockBehavior behavior = CookingPotBlockBehavior.getBlockBehavior(cookingPotLocation);
            cachedRecipeGroupId = behavior == null ? null : behavior.getCustomRecipeGroupId();
            recipeGroupResolved = true;
        }
        return cachedRecipeGroupId;
    }

    private void navigateToState(Player player, GuiState newState) {
        if (newState == GuiState.MAIN_MENU || newState == GuiState.COOKING_POT_LIST || newState == GuiState.CUTTING_BOARD_LIST) {
            currentPage = 0;
        }
        if (newState == GuiState.RECIPE_DETAIL) {
            currentToolIndex = 0;
            currentToolPreviewIndex = 0;
            toolSwitchTicks = 0;
        }
        state = newState;
        refresh(player);
        reopenInventory(player);
    }

    private void refreshAndReopen(Player player) {
        refresh(player);
        reopenInventory(player);
    }

    private void refreshContentsInPlace(Player player) {
        Inventory visibleInventory = inventory;
        refresh(player, false);
        Inventory renderedInventory = inventory;
        if (visibleInventory == null || renderedInventory == null
                || visibleInventory.getSize() != renderedInventory.getSize()
                || player.getOpenInventory().getTopInventory().getHolder() != this) {
            reopenInventory(player);
            return;
        }

        for (int slot = 0; slot < visibleInventory.getSize(); slot++) {
            visibleInventory.setItem(slot, renderedInventory.getItem(slot));
        }
        inventory = visibleInventory;
        player.updateInventory();
    }

    private void changePage(Player player, int delta) {
        currentPage += delta;
        refreshAndReopen(player);
    }

    private void reopenInventory(Player player) {
        if (player == null) {
            return;
        }
        ignoreNextClose = true;
        player.openInventory(inventory);
    }

    private void closeGui(Player player) {
        close();
        player.closeInventory();
    }

    private boolean runBackButtonCommands(Player player, GuiConfig.GuiItem backItem) {
        if (!backButtonCommandsEnabled || player == null || backItem == null || backItem.hasCommands()) {
            return false;
        }

        List<String> commands = backItem.getCommands();
        closeGui(player);
        plugin.scheduler().runLaterForEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (String command : commands) {
                dispatchConfiguredCommand(player, command);
            }
        }, 1L);
        return true;
    }

    private void dispatchConfiguredCommand(Player player, String configuredCommand) {
        ConfiguredCommand command = parseConfiguredCommand(configuredCommand, player);
        if (command.command().isEmpty()) {
            return;
        }
        if (command.console()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.command());
        } else {
            Bukkit.dispatchCommand(player, command.command());
        }
    }

    private ConfiguredCommand parseConfiguredCommand(String configuredCommand, Player player) {
        String command = applyCommandPlaceholders(configuredCommand, player).trim();
        boolean console = false;
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[console]")) {
            console = true;
            command = command.substring("[console]".length()).trim();
        } else if (lower.startsWith("[player]")) {
            command = command.substring("[player]".length()).trim();
        } else if (lower.startsWith("console:")) {
            console = true;
            command = command.substring("console:".length()).trim();
        } else if (lower.startsWith("player:")) {
            command = command.substring("player:".length()).trim();
        }
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        return new ConfiguredCommand(command, console);
    }

    private String applyCommandPlaceholders(String command, Player player) {
        if (command == null) {
            return "";
        }
        return command
                .replace("{player}", player.getName())
                .replace("{player_name}", player.getName())
                .replace("%player%", player.getName())
                .replace("%player_name%", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{world}", player.getWorld().getName());
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) return;
        if (closed) return;
        if (ignoreNextClose) {
            ignoreNextClose = false;
            return;
        }
        super.onClose(event);
    }

    private record ConfiguredCommand(String command, boolean console) {
    }

    private void returnToCookingPot(Player player) {
        if (cookingPotLocation == null) {
            return;
        }
        
        plugin.scheduler().runLaterAt(cookingPotLocation, () -> {
            World world = cookingPotLocation.getWorld();
            if (world == null) return;
            
            var blockEntity = CookingPotBlockBehavior.getBlockEntity(cookingPotLocation);
            if (blockEntity == null) return;
            
            var blockBehavior = CookingPotBlockBehavior.getBlockBehavior(cookingPotLocation);
            if (blockBehavior == null) return;

            // Re-check permission + land protection before re-opening: this is a fresh GUI open just like a
            // direct interaction, and access may have changed since the pot was first opened.
            if (!blockBehavior.canPlayerOpen(player, cookingPotLocation.getBlock())) {
                return;
            }

            CookingPotGui gui = new CookingPotGui(plugin, blockEntity, blockBehavior, world, cookingPotLocation);
            gui.open(player);
        }, 1L);
    }

    public static void cleanupAll() {
        for (RecipeViewGui gui : activeGuis.values()) {
            if (!gui.closed) {
                gui.close();
            }
        }
        activeGuis.clear();
        cachedConfig = null;
        RecipeIngredientIcons.clearItemCache();
        // Reset this flag so a new EventDispatcher is re-registered on soft re-enable; otherwise, after disable removes the old listener,
        // recipe GUI clicks would no longer be cancelled.
        listenerRegistered = false;
    }

    public static void closeAllOpenGuis() {
        for (Map.Entry<UUID, RecipeViewGui> entry : new ArrayList<>(activeGuis.entrySet())) {
            RecipeViewGui gui = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (gui != null && !gui.closed) {
                gui.close();
            }
            activeGuis.remove(entry.getKey());
            if (player != null && player.isOnline()) {
                player.closeInventory();
            }
        }
    }

    @Override
    protected AbstractInventoryGui findExistingGui(UUID playerId) {
        return activeGuis.get(playerId);
    }

    @Override
    protected void putActiveGui(UUID playerId, AbstractInventoryGui gui) {
        activeGuis.put(playerId, (RecipeViewGui) gui);
    }

    @Override
    protected void removeFromActiveGuis(UUID playerId) {
        activeGuis.remove(playerId);
    }

    @Override
    protected void ensureListenerRegistered() {
        if (listenerRegistered) return;
        synchronized (RecipeViewGui.class) {
            if (listenerRegistered) return;
            Bukkit.getPluginManager().registerEvents(new RecipeViewEventDispatcher(), plugin);
            listenerRegistered = true;
        }
    }

    static RecipeViewGui removeActiveGui(UUID playerId) {
        return activeGuis.remove(playerId);
    }

}
