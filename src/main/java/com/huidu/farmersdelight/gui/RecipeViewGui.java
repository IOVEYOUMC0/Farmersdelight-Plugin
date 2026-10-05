package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.editor.RecipeEditorView;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.recipe.JumpTarget;
import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.SpecialRecipeRegistry;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RecipeViewGui extends AbstractInventoryGui {

    private static final Map<UUID, RecipeViewGui> activeGuis = new ConcurrentHashMap<>();
    // The parsed config, built list display items and warn-once sets live in RecipeViewCache, so what is
    // cached and when it is dropped read as one thing.
    // Ingredient option caches live in RecipeIngredientIcons; tool preview options live in ToolPreviewRenderer.
    private static final ItemStack EMPTY_SLOT_BACKGROUND = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    static {
        ItemMeta meta = EMPTY_SLOT_BACKGROUND.getItemMeta();
        meta.displayName(Component.text(" "));
        EMPTY_SLOT_BACKGROUND.setItemMeta(meta);
    }

    public enum GuiState {
        MAIN_MENU,
        COOKING_POT_LIST,
        CUTTING_BOARD_LIST,
        RECIPE_DETAIL,
        INGREDIENT_OPTIONS,
        SPECIAL_RECIPE_LIST,
        SPECIAL_RECIPE_DETAIL
    }

    final RecipeViewGuiConfig config;
    private GuiState state = GuiState.MAIN_MENU;
    private int currentPage = 0;
    private volatile String selectedRecipeId = null;
    private volatile String selectedSpecialRecipeId = null;
    private boolean cookingPotMode = true;
    private boolean craftableOnly = false;
    private GuiState recipeBackState = GuiState.COOKING_POT_LIST;
    volatile int currentToolIndex = 0;
    volatile int currentToolPreviewIndex = 0;
    // Auto-cycle drivers for the detail slots that rotate through candidates (cutting-board tool preview,
    // special-recipe catalyst items). The shared CyclicSlot keeps the per-tick advancing in one place.
    private final CyclicSlot toolCycle = new CyclicSlot(INGREDIENT_SWITCH_CALLBACKS);
    private final CyclicSlot catalystCycle = new CyclicSlot(INGREDIENT_SWITCH_CALLBACKS);
    private final SpecialRecipeRenderer specialRecipeRenderer;
    // session instead of on every click on an ingredient.
    final RecipeIngredientDisplay ingredientDisplay;
    final ToolPreviewRenderer toolPreviewRenderer;
    // Expanded catalyst options for the currently shown special recipe (tag references expand into
    // their member items); rebuilt each time the detail page is drawn.
    private volatile List<ItemStack> specialCatalystOptions = List.of();
    private List<ItemStack> expandedIngredientOptions = List.of();
    private int expandedIngredientPage;
    private DetailState ingredientOptionsOrigin;
    final RecipeDetailRenderer detailRenderer = new RecipeDetailRenderer(this);
    // Ingredient/tool preview rotation: this many GUI tick callbacks, and each callback is
    // GuiTickManager.TICK_INTERVAL (4) game ticks, so the preview switches every 80 ticks (4s).
    static final int INGREDIENT_SWITCH_CALLBACKS = 20;
    
    private final boolean fromCookingPot;
    private final Location cookingPotLocation;
    private boolean backButtonCommandsEnabled = false;
    private Runnable onExit;
    private boolean editMode = false;
    // Resolve only once (lazily, during the first open, when the viewer is at the pot = same Folia region) and memoize the result,
    // so the per-tick / redraw flow never repeats a cross-region block read.
    private boolean recipeGroupResolved;
    private String cachedRecipeGroupId;
    private boolean ignoreNextClose = false;
    private CookingPotFiller.FillButtonState fillButtonState = CookingPotFiller.FillButtonState.READY;
    private CookingPotFiller cookingPotFiller;
    private RecipeCraftability craftability;
    // Detail-to-detail navigation history: clicking a linked recipe (an ingredient/result that is itself
    // another recipe's output) pushes the detail it left, so "back" returns to that recipe instead of always
    // dropping to the original list. Reset when a detail is opened fresh from a list; empty history returns to
    // recipeBackState. Touched only inside the click handler (single-threaded per viewer).
    private final Deque<DetailState> detailHistory = new ArrayDeque<>();

    // Chain node for the detail-to-detail back stack. Records whichever detail page was left so "back"
    // can restore it exactly: either a normal (pot/board) recipe detail, a special-recipe detail, or
    // the expanded ingredient view (stored in recipeBackState as its return-state marker).
    private record DetailState(
            boolean specialDetail,
            boolean cookingPotMode,
            String selectedRecipeId,
            String selectedSpecialRecipeId,
            GuiState recipeBackState) {
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
        this.specialRecipeRenderer = new SpecialRecipeRenderer(this, plugin);
        this.ingredientDisplay = new RecipeIngredientDisplay(this);
        this.toolPreviewRenderer = new ToolPreviewRenderer(this, plugin);
        
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
        if (closed) {
            return;
        }
        if (state == GuiState.RECIPE_DETAIL) {
            if (!cookingPotMode) {
                tickToolSwitch();
            }
            tickIngredientSwitch();
        } else if (state == GuiState.SPECIAL_RECIPE_DETAIL) {
            tickCatalystSwitch();
        }
    }

    private RecipeViewGuiConfig getOrCreateConfig() {
        return RecipeViewCache.config(this::loadConfig);
    }

    private RecipeViewGuiConfig loadConfig() {
        var section = plugin.getRecipeViewGuiSection();
        return section != null ? RecipeViewGuiConfig.fromConfig(section) : createDefaultConfig();
    }

    public static void clearConfigCache() {
        RecipeViewCache.clearConfig();
        // Built display items cache resolved names/lore (from the language files), so clear them too; otherwise
        // stale item names would linger in the recipe GUI after /fd reload lang/gui.
        RecipeIngredientIcons.clearCaches();
        RecipeViewCache.clearDisplay();
        ToolPreviewRenderer.clearToolPreviewCache();
        RecipeDetailRenderer.clearProcessBarFrameCache();
    }

    public static void clearRecipeDisplayCache() {
        RecipeViewCache.clearDisplay();
        ToolPreviewRenderer.clearToolPreviewCache();
    }

    private RecipeViewGuiConfig createDefaultConfig() {
        RecipeViewGuiConfig.MainMenuConfig mainMenu = RecipeViewGuiConfig.MainMenuConfig.fromConfig(null);
        RecipeViewGuiConfig.RecipeListConfig recipeList = RecipeViewGuiConfig.RecipeListConfig.fromConfig(null);
        RecipeViewGuiConfig.RecipeDetailConfig cookingPotDetail = RecipeViewGuiConfig.RecipeDetailConfig.createCookingPotDefault();
        RecipeViewGuiConfig.RecipeDetailConfig cuttingBoardDetail = RecipeViewGuiConfig.RecipeDetailConfig.createCuttingBoardDefault();
        return new RecipeViewGuiConfig(mainMenu, recipeList, cookingPotDetail,
                Map.<String, RecipeViewGuiConfig.RecipeDetailConfig>of(), cuttingBoardDetail,
                null, null, null, true, false, 4);
    }

    public void open(Player player) {
        doOpen(() -> refresh(player));
    }

    public static boolean openLinkedRecipe(Player player, JumpTarget target, Runnable onExit) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || player == null || target == null) {
            return false;
        }
        RecipeViewGui gui = new RecipeViewGui(plugin, player);
        gui.onExit = onExit;
        if (RecipeDiscoveryManager.TYPE_COOKING_POT.equals(target.typeId())) {
            CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(target.recipeId());
            if (recipe == null) {
                return false;
            }
            if (gui.isRecipeLocked(recipe, true, player)) {
                player.sendMessage(Component.translatable("recipe-discovery.locked-click").color(NamedTextColor.RED));
                return true;
            }
            gui.cookingPotMode = true;
            gui.selectedRecipeId = target.recipeId();
            gui.recipeBackState = GuiState.COOKING_POT_LIST;
            gui.state = GuiState.RECIPE_DETAIL;
        } else if (RecipeDiscoveryManager.TYPE_CUTTING_BOARD.equals(target.typeId())) {
            CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(target.recipeId());
            if (recipe == null) {
                return false;
            }
            if (gui.isRecipeLocked(recipe, false, player)) {
                player.sendMessage(Component.translatable("recipe-discovery.locked-click").color(NamedTextColor.RED));
                return true;
            }
            gui.cookingPotMode = false;
            gui.selectedRecipeId = target.recipeId();
            gui.recipeBackState = GuiState.CUTTING_BOARD_LIST;
            gui.state = GuiState.RECIPE_DETAIL;
        } else if (SpecialRecipeRegistry.TYPE_ID.equals(target.typeId())) {
            SpecialRecipeInfo info = plugin.getSpecialRecipeRegistry() == null
                    ? null : plugin.getSpecialRecipeRegistry().get(target.recipeId());
            if (info == null) {
                return false;
            }
            gui.selectedSpecialRecipeId = target.recipeId();
            if (gui.specialRecipeRenderer.isListOnlySpecial(info)) {
                int index = plugin.getSpecialRecipeRegistry().getAll().indexOf(info);
                int pageSize = Math.max(1, gui.config.getSpecialRecipeList().getRecipeSlots().size());
                gui.currentPage = Math.max(0, index) / pageSize;
                gui.state = GuiState.SPECIAL_RECIPE_LIST;
            } else {
                gui.state = GuiState.SPECIAL_RECIPE_DETAIL;
            }
        } else {
            return false;
        }
        gui.open(player);
        return true;
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

    public void openSpecialRecipes(Player player) {
        backButtonCommandsEnabled = true;
        state = GuiState.SPECIAL_RECIPE_LIST;
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
        if (player == null || !player.isOnline()) {
            return;
        }
        CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(selectedRecipeId);
        if (recipe == null || recipe.getTools() == null || recipe.getTools().isEmpty()) {
            return;
        }

        List<CuttingBoardRecipe.ToolRequirement> tools = recipe.getTools();
        int safeToolIndex = currentToolIndex % tools.size();
        int previewCount = resolveToolPreviewOptionsSize(tools.get(safeToolIndex));
        if (previewCount <= 1 && tools.size() <= 1) {
            return;
        }
        // The generic cycle timer decides when to advance, keeping the tick cadence in one place.
        if (!toolCycle.tick()) {
            return;
        }

        if (previewCount > 1) {
            currentToolPreviewIndex++;
            if (currentToolPreviewIndex >= previewCount) {
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

    /** Cycles the special-recipe catalyst items on the detail page's catalyst_item slot(s). */
    private void tickCatalystSwitch() {
        if (player == null || !player.isOnline() || selectedSpecialRecipeId == null) {
            return;
        }
        SpecialRecipeInfo info = plugin.getSpecialRecipeRegistry() != null
                ? plugin.getSpecialRecipeRegistry().get(selectedSpecialRecipeId) : null;
        if (info == null) {
            return;
        }
        RecipeViewGuiConfig.SpecialRecipeDetailConfig detailConfig = specialRecipeRenderer.specialDetailFor(info);
        List<Integer> slots = detailConfig.getCatalystItemSlots();
        List<ItemStack> options = specialCatalystOptions;
        if (slots.isEmpty() || options.size() <= 1) {
            return;
        }
        if (!catalystCycle.tick()) {
            return;
        }
        int displayIndex = catalystCycle.current(options.size());
        for (int i = 0; i < slots.size(); i++) {
            int optionIndex = (displayIndex + i) % options.size();
            // Rebuild slot 0 with the full catalyst list so auto-cycle never wipes it (see createSpecialCycleDisplay).
            boolean showFullList = info.hasCatalystInfo() && i == 0;
            inventory.setItem(slots.get(i),
                    specialRecipeRenderer.createSpecialCycleDisplay(options.get(optionIndex), optionIndex, options.size(),
                            showFullList, options, player));
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
        ingredientDisplay.tickIngredientSwitch();
    }

    private void resetDetailAnimations() {
        ingredientDisplay.resetAnimations();
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
            case INGREDIENT_OPTIONS -> drawIngredientOptions(player);
            case SPECIAL_RECIPE_LIST -> drawSpecialRecipeList(player);
            case SPECIAL_RECIPE_DETAIL -> drawSpecialRecipeDetail(player);
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
                && !FarmersDelightApi.get().recipeTypes().isEmpty()) {
            setGuiItem(menuConfig, "recipe_book", menuConfig.getRecipeBookSlot());
        }
        // Special recipe button: only shown when special recipes are registered.
        if (menuConfig.getSpecialRecipesSlot() >= 0
                && plugin.getSpecialRecipeRegistry() != null
                && !plugin.getSpecialRecipeRegistry().isEmpty()) {
            setGuiItem(menuConfig, "special_recipes", menuConfig.getSpecialRecipesSlot());
        }
        setGuiItem(menuConfig, "back", menuConfig.getBackSlot());
    }

    private void drawCookingPotList(Player player) {
        cookingPotMode = true;
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CookingPotRecipe> recipes = plugin.getCookingPotRecipes().getSortedRecipes(getActiveCookingPotRecipeGroup());
        if (craftableOnly) {
            recipes = craftability().filterCraftableCookingPotRecipes(recipes, player);
        }
        recipes = listVisibleTo(player, recipes, true);
        drawRecipeList(player, listConfig, recipes, true);
    }

    private void drawCuttingBoardList(Player player) {
        cookingPotMode = false;
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CuttingBoardRecipe> recipes = plugin.getCuttingBoardRecipes().getSortedRecipes();
        if (craftableOnly) {
            recipes = craftability().filterCraftableCuttingBoardRecipes(recipes, player);
        }
        recipes = listVisibleTo(player, recipes, false);
        drawRecipeList(player, listConfig, recipes, false);
    }

    /**
     * The list the viewer actually sees, in the order the slots are laid out in.
     *
     *
     * The discovery filter hides recipes the viewer has not unlocked. The recipe editor reuses this list,
     * and an admin opening /fd recipe edit has no unlock record of their own, so filtering there hid
     * exactly the recipes they came to edit. Draw and click both go through here, because a slot is resolved
     * back to a recipe by rebuilding this list.
     */
    private <T> List<T> listVisibleTo(Player player, List<T> recipes, boolean isCookingPot) {
        return editMode ? recipes : applyDiscoveryFilter(recipes, isCookingPot, player);
    }

    private <T> void drawRecipeList(Player player, RecipeViewGuiConfig.RecipeListConfig listConfig, 
                                    List<T> recipes, boolean isCookingPot) {
        List<Integer> recipeSlots = listConfig.getRecipeSlots();
        int totalPages = beginListPage(listConfig, recipeSlots, recipes.size(), "recipe-list", "level_1");
        int startIndex = currentPage * recipeSlots.size();

        // Locale + (for cooking pots) the per-instance preview-count and active group fully determine
        // a non-locked display item's content, so cache the built item across opens/pages/players.
        String locale = player == null ? "default" : player.locale().toString().toLowerCase(Locale.ROOT);
        String cacheKeyPrefix = isCookingPot
                ? "pot|" + getActiveCookingPotRecipeGroup() + '|' + config.getRecipeListMaxPreviewIngredients() + '|'
                : "board|" + config.getRecipeListMaxPreviewIngredients() + '|';

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
                    displayItem = RecipeViewCache.displayCache().computeIfAbsent(
                            cacheKeyPrefix + potRecipe.getId() + '|' + locale,
                            k -> createCookingPotRecipeDisplayItem(potRecipe, player)).clone();
                } else {
                    CuttingBoardRecipe boardRecipe = (CuttingBoardRecipe) recipe;
                    displayItem = RecipeViewCache.displayCache().computeIfAbsent(
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

    // Shared pagination skeleton for the plain and special recipe lists: computes the page bounds,
    // creates the titled inventory (with {page}/{total} resolved) and fills the background. Returns
    // the total page count; the caller derives the start index from the current page.
    private int beginListPage(RecipeViewGuiConfig.BaseConfig listConfig, List<Integer> recipeSlots,
                              int totalItems, String guiPath, String legacyPath) {
        int itemsPerPage = recipeSlots.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) itemsPerPage));
        if (currentPage < 0) {
            currentPage = 0;
        } else if (currentPage >= totalPages) {
            currentPage = totalPages - 1;
        }

        Map<String, String> titlePlaceholders = new HashMap<>();
        titlePlaceholders.put("page", String.valueOf(currentPage + 1));
        titlePlaceholders.put("total", String.valueOf(totalPages));
        String title = resolveMenuTitle(guiPath, legacyPath, listConfig.getTitle(), titlePlaceholders);
        inventory = Bukkit.createInventory(this, listConfig.getSize(), coloredComponent(title));

        fillBackground(listConfig);
        return totalPages;
    }

    // Draws the shared prev/next page affordances; only renders a button when its page exists.
    private void drawPageButtons(RecipeViewGuiConfig.BaseConfig listConfig, int prevSlot, int nextSlot, int totalPages) {
        if (currentPage > 0) {
            setGuiItem(listConfig, "prev_page", prevSlot);
        }
        if (currentPage < totalPages - 1) {
            setGuiItem(listConfig, "next_page", nextSlot);
        }
    }

    private void drawListNavigation(RecipeViewGuiConfig.RecipeListConfig listConfig, int totalPages) {
        drawPageButtons(listConfig, listConfig.getPrevPageSlot(), listConfig.getNextPageSlot(), totalPages);

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
                && (backItem == null || backItem.hasNoCommands());
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
            toolCycle.reset();
        }

        fillBackground(detailConfig);
        // Material expansion opens from the folded ingredient slot; deprecated material slots remain background.
        if (detailConfig.getMaterialsSlot() >= 0) {
            inventory.setItem(detailConfig.getMaterialsSlot(), createBackgroundItem(detailConfig));
        }

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

    private void drawIngredientOptions(Player player) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<Integer> slots = listConfig.getRecipeSlots();
        int pageSize = Math.max(1, slots.size());
        int totalPages = Math.max(1, (expandedIngredientOptions.size() + pageSize - 1) / pageSize);
        expandedIngredientPage = Math.max(0, Math.min(expandedIngredientPage, totalPages - 1));
        Map<String, String> placeholders = Map.of(
                "page", String.valueOf(expandedIngredientPage + 1),
                "total", String.valueOf(totalPages));
        String title = resolveMenuTitle("recipe-list", "level_1", listConfig.getTitle(), placeholders);
        inventory = Bukkit.createInventory(this, listConfig.getSize(), coloredComponent(title));
        fillBackground(listConfig);
        if (listConfig.getFilterSlot() >= 0) {
            inventory.setItem(listConfig.getFilterSlot(), createBackgroundItem(listConfig));
        }

        int start = expandedIngredientPage * pageSize;
        for (int i = 0; i < slots.size() && start + i < expandedIngredientOptions.size(); i++) {
            inventory.setItem(slots.get(i), createExpandedIngredientDisplay(expandedIngredientOptions.get(start + i)));
        }
        drawPageButtons(listConfig, listConfig.getPrevPageSlot(), listConfig.getNextPageSlot(), totalPages);
        setGuiItem(listConfig, "back", listConfig.getBackSlot());
    }

    private ItemStack createExpandedIngredientDisplay(ItemStack source) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(tr("gui.recipe.ingredient", NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<ItemStack> collectExpandedIngredientOptions(RecipeIngredient ingredient) {
        if (ingredient == null) {
            return List.of();
        }
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        for (ItemStack option : RecipeIngredientIcons.resolveIngredientOptions(ingredient)) {
            if (isDisplayableItem(option)) {
                unique.putIfAbsent(RecipeIngredientIcons.buildIngredientDisplayKey(option), option);
            }
        }
        return RecipeIngredientIcons.sortIngredientDisplayItems(unique.values());
    }

    private boolean openIngredientOptions(Player player, RecipeIngredient ingredient) {
        List<ItemStack> options = collectExpandedIngredientOptions(ingredient);
        if (options.size() <= 1) {
            return false;
        }
        ingredientOptionsOrigin = new DetailState(false, cookingPotMode, selectedRecipeId, null, recipeBackState);
        expandedIngredientOptions = options;
        expandedIngredientPage = 0;
        navigateToState(player, GuiState.INGREDIENT_OPTIONS, false);
        return true;
    }

    private RecipeIngredient ingredientAtDetailSlot(RecipeViewGuiConfig.RecipeDetailConfig detailConfig, int slot) {
        if (cookingPotMode) {
            int index = detailConfig.getIngredientSlots().indexOf(slot);
            if (index < 0) {
                return null;
            }
            CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(
                    getActiveCookingPotRecipeGroup(), selectedRecipeId);
            return recipe != null && index < recipe.getIngredients().size()
                    ? recipe.getIngredients().get(index) : null;
        }
        if (slot != detailConfig.getInputSlot()) {
            return null;
        }
        CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(selectedRecipeId);
        return recipe == null ? null : recipe.getInput();
    }

    // ---- Special recipe rendering ----

    private void drawSpecialRecipeList(Player player) {
        RecipeViewGuiConfig.SpecialRecipeListConfig listConfig = config.getSpecialRecipeList();
        List<SpecialRecipeInfo> recipes = plugin.getSpecialRecipeRegistry() != null
                ? plugin.getSpecialRecipeRegistry().getAll() : List.of();
        List<Integer> recipeSlots = listConfig.getRecipeSlots();
        int totalPages = beginListPage(listConfig, recipeSlots, recipes.size(), "special-recipe-list", null);
        int startIndex = currentPage * recipeSlots.size();

        for (int i = 0; i < recipeSlots.size(); i++) {
            int recipeIndex = startIndex + i;
            if (recipeIndex < recipes.size()) {
                SpecialRecipeInfo info = recipes.get(recipeIndex);
                ItemStack display = specialRecipeRenderer.createSpecialRecipeListDisplayItem(info, player);
                inventory.setItem(recipeSlots.get(i), display);
            }
        }

        drawPageButtons(listConfig, listConfig.getPrevPageSlot(), listConfig.getNextPageSlot(), totalPages);
        setGuiItem(listConfig, "back", listConfig.getBackSlot());
    }

    private void drawSpecialRecipeDetail(Player player) {
        if (selectedSpecialRecipeId == null) return;
        SpecialRecipeInfo info = plugin.getSpecialRecipeRegistry() != null
                ? plugin.getSpecialRecipeRegistry().get(selectedSpecialRecipeId) : null;
        if (info == null) return;

        // Start the catalyst auto-cycle from its first item each time this detail page is (re)drawn.
        catalystCycle.reset();

        RecipeViewGuiConfig.SpecialRecipeDetailConfig detailConfig = specialRecipeRenderer.specialDetailFor(info);
        String title = resolveSpecialDetailTitle(detailConfig.guiKey(), detailConfig.getTitle(),
                Map.of("recipe_id", info.id()), info);
        inventory = Bukkit.createInventory(this, detailConfig.getSize(), coloredComponent(title));

        fillBackground(detailConfig);

        // Description — combined into single slot with all lines as lore
        List<Integer> descSlots = detailConfig.getDescriptionSlots();
        if (!descSlots.isEmpty() && !info.descriptionKeys().isEmpty()) {
            inventory.setItem(descSlots.get(0), specialRecipeRenderer.createCombinedDescriptionItem(info.descriptionKeys(), player));
        }

        // Input slots
        List<Integer> inputSlots = detailConfig.getInputSlots();
        List<SpecialRecipeInfo.SlotEntry> inputs = info.inputSlots();
        for (int i = 0; i < inputSlots.size(); i++) {
            if (i < inputs.size()) {
                inventory.setItem(inputSlots.get(i), specialRecipeRenderer.createSlotEntryItem(inputs.get(i), player, NamedTextColor.AQUA));
            } else {
                inventory.setItem(inputSlots.get(i), createBackgroundItem(detailConfig));
            }
        }

        // Output slots
        List<Integer> outputSlots = detailConfig.getOutputSlots();
        List<SpecialRecipeInfo.SlotEntry> outputs = info.outputSlots();
        for (int i = 0; i < outputSlots.size(); i++) {
            if (i < outputs.size()) {
                inventory.setItem(outputSlots.get(i), specialRecipeRenderer.createSlotEntryItem(outputs.get(i), player, NamedTextColor.GREEN));
            } else {
                inventory.setItem(outputSlots.get(i), createBackgroundItem(detailConfig));
            }
        }

        // Condition slots (D sunlight / E water / F catalyst_info). The icon itself can either be drawn by
        // the title (title-layout craftengine <image>, "title draws the look") with the slot carrying only
        // the hover text, or by the slot item itself (items.sunlight / items.water / items.catalyst_info in
        // gui.yml, falling back to torch / water bucket / paper). When the recipe lacks the condition, the
        // slot clears to plain background so the fillBackground copy and its tooltip do not linger.
        specialRecipeRenderer.setConditionSlot(detailConfig, "sunlight", detailConfig.getSunlightSlot(), info.hasSunlight(),
                specialRecipeRenderer.hasTitleConditionImage(detailConfig.guiKey(), "sunlight"),
                "minecraft:torch", "gui.condition.sunlight", "gui.condition.sunlight_lore", NamedTextColor.YELLOW);
        specialRecipeRenderer.setConditionSlot(detailConfig, "water", detailConfig.getWaterSlot(), info.hasWater(),
                specialRecipeRenderer.hasTitleConditionImage(detailConfig.guiKey(), "water"),
                "minecraft:water_bucket", "gui.condition.water", "gui.condition.water_lore", NamedTextColor.BLUE);
        // Resolve the catalyst blocks once; they feed the switching catalyst item slots (G) and their lore.
        List<ItemStack> catalystOptions = specialRecipeRenderer.expandSpecialEntries(info.catalystSlots());

        specialRecipeRenderer.setConditionSlot(detailConfig, "catalyst_info", detailConfig.getCatalystInfoSlot(), info.hasCatalystInfo(),
                specialRecipeRenderer.hasTitleConditionImage(detailConfig.guiKey(), "catalyst_info"),
                "minecraft:paper", "gui.condition.catalyst_info", "gui.condition.catalyst_info_lore", NamedTextColor.GOLD);

        // Catalyst item slots (G) — the auto-cycle timer rotates through the resolved items. The full
        // Catalyst list rides on slot 0's lore and is copied to every candidate so auto-cycle switching keeps it.
        List<Integer> catalystItemSlots = detailConfig.getCatalystItemSlots();
        this.specialCatalystOptions = catalystOptions;
        for (int i = 0; i < catalystItemSlots.size(); i++) {
            if (!catalystOptions.isEmpty()) {
                int optionIndex = (catalystCycle.current(catalystOptions.size()) + i) % catalystOptions.size();
                inventory.setItem(catalystItemSlots.get(i),
                        specialRecipeRenderer.createSpecialCycleDisplay(catalystOptions.get(optionIndex), optionIndex, catalystOptions.size(),
                                info.hasCatalystInfo() && i == 0, catalystOptions, player));
            } else {
                inventory.setItem(catalystItemSlots.get(i), createBackgroundItem(detailConfig));
            }
        }

        setGuiItem(detailConfig, "back", detailConfig.getBackSlot());
    }

    private void handleSpecialRecipeListClick(Player player, int slot) {
        RecipeViewGuiConfig.SpecialRecipeListConfig listConfig = config.getSpecialRecipeList();
        List<SpecialRecipeInfo> recipes = plugin.getSpecialRecipeRegistry() != null
                ? plugin.getSpecialRecipeRegistry().getAll() : List.of();
        List<Integer> recipeSlots = listConfig.getRecipeSlots();
        int itemsPerPage = recipeSlots.size();
        int totalPages = (int) Math.ceil(recipes.size() / (double) itemsPerPage);

        if (handlePageClick(player, slot, listConfig.getPrevPageSlot(), listConfig.getNextPageSlot(), totalPages)) {
            return;
        }
        if (slot == listConfig.getBackSlot()) {
            if (runBackButtonCommands(player, listConfig.getItem("back"))) {
                return;
            }
            if (!detailHistory.isEmpty()) {
                restoreDetailState(player, detailHistory.pop());
                return;
            }
            if (returnToLinkedSource()) {
                return;
            }
            navigateToState(player, GuiState.MAIN_MENU, false);
        } else if (recipeSlots.contains(slot)) {
            int slotIndex = recipeSlots.indexOf(slot);
            int recipeIndex = currentPage * itemsPerPage + slotIndex;
            if (recipeIndex < recipes.size()) {
                SpecialRecipeInfo info = recipes.get(recipeIndex);
                if (specialRecipeRenderer.isListOnlySpecial(info)) {
                    return;
                }
                selectedSpecialRecipeId = info.id();
                navigateToState(player, GuiState.SPECIAL_RECIPE_DETAIL, false);
            }
        }
    }

    private void handleIngredientOptionsClick(Player player, int slot) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        int pageSize = Math.max(1, listConfig.getRecipeSlots().size());
        int totalPages = Math.max(1, (expandedIngredientOptions.size() + pageSize - 1) / pageSize);
        if (slot == listConfig.getPrevPageSlot() && expandedIngredientPage > 0) {
            expandedIngredientPage--;
            refreshAndReopen(player);
            return;
        }
        if (slot == listConfig.getNextPageSlot() && expandedIngredientPage < totalPages - 1) {
            expandedIngredientPage++;
            refreshAndReopen(player);
            return;
        }
        if (slot == listConfig.getBackSlot()) {
            DetailState origin = ingredientOptionsOrigin;
            ingredientOptionsOrigin = null;
            if (origin != null) {
                cookingPotMode = origin.cookingPotMode();
                selectedRecipeId = origin.selectedRecipeId();
                recipeBackState = origin.recipeBackState();
                navigateToState(player, GuiState.RECIPE_DETAIL, false);
            } else {
                navigateToState(player, recipeBackState, true);
            }
            return;
        }
        int slotIndex = listConfig.getRecipeSlots().indexOf(slot);
        if (slotIndex < 0) {
            return;
        }
        int index = expandedIngredientPage * pageSize + slotIndex;
        if (index < 0 || index >= expandedIngredientOptions.size()) {
            return;
        }
        ItemStack clickedItem = inventory.getItem(slot);
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }
        if (navigateToLinkedRecipe(player, clickedItem, false)) {
            return;
        }
        String specialId = craftability().findSpecialRecipe(clickedItem);
        if (specialId != null) {
            if (navigateToSpecialRecipeList(player, specialId)) {
                return;
            }
            DetailState origin = ingredientOptionsOrigin;
            if (origin != null) {
                detailHistory.push(origin);
            }
            selectedSpecialRecipeId = specialId;
            navigateToState(player, GuiState.SPECIAL_RECIPE_DETAIL, false);
            return;
        }
        navigateToAddonRecipe(player, clickedItem);
    }

    private void handleSpecialRecipeDetailClick(Player player, int slot) {
        SpecialRecipeInfo info = plugin.getSpecialRecipeRegistry() != null
                ? plugin.getSpecialRecipeRegistry().get(selectedSpecialRecipeId) : null;
        RecipeViewGuiConfig.SpecialRecipeDetailConfig detailConfig = specialRecipeRenderer.specialDetailFor(info);
        if (slot == detailConfig.getBackSlot()) {
            if (runBackButtonCommands(player, detailConfig.getItem("back"))) {
                return;
            }
            if (!detailHistory.isEmpty()) {
                restoreDetailState(player, detailHistory.pop());
                return;
            }
            if (returnToLinkedSource()) {
                return;
            }
            navigateToState(player, GuiState.SPECIAL_RECIPE_LIST, true);
            return;
        }

        // Linked-recipe jump: clicking a shown item opens the pot/board recipe that produces it, if any.
        ItemStack clickedItem = inventory.getItem(slot);
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }
        if (navigateToLinkedRecipe(player, clickedItem, true)) {
            return;
        }
        String specialId = craftability().findSpecialRecipe(clickedItem);
        if (specialId != null && !specialId.equals(selectedSpecialRecipeId)) {
            if (navigateToSpecialRecipeList(player, specialId)) {
                return;
            }
            detailHistory.push(new DetailState(true, cookingPotMode, selectedRecipeId, selectedSpecialRecipeId,
                    GuiState.SPECIAL_RECIPE_LIST));
            selectedSpecialRecipeId = specialId;
            navigateToState(player, GuiState.SPECIAL_RECIPE_DETAIL, false);
            return;
        }
        navigateToAddonRecipe(player, clickedItem);
    }

    // Linked jump fallback when no FD pot/board recipe produces the clicked item: hand off to the addon
    // RecipeBook at the workstation recipe that does (keg, BBQ station, ...). The workstation view is a
    // separate GUI, so fully backing out of it re-opens this FD recipe view EXACTLY at the page it left
    // (not dropped onto the main menu), preserving the special/pot/board detail the user jumped from.
    private void navigateToAddonRecipe(Player player, ItemStack clickedItem) {
        if (recipeNavigationBlocked()) {
            return;
        }
        RecipeCraftability.LinkedAddonRecipe addon = craftability().findLinkedAddonRecipe(clickedItem);
        if (addon == null) {
            return;
        }
        RecipeViewGui source = this;
        plugin.scheduler().runLaterForEntity(player, () -> {
            if (player.isOnline()) {
                RecipeBookGui.openRecipe(player, addon.type(),
                        addon.recipeId(), () -> source.open(player));
            }
        }, 1L);
    }

    int cookTimeSeconds(CookingPotRecipe recipe) {        if (recipe == null || recipe.getCookTime() <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(recipe.getCookTime() / 20.0D));
    }

    ItemStack createToolDisplayItem(CuttingBoardRecipe.ToolRequirement tool, int totalTools,
                                    int currentIndex, Player player) {
        return toolPreviewRenderer.createToolDisplayItem(tool, totalTools, currentIndex, player);
    }

    private ItemStack createToolPreviewItem(CuttingBoardRecipe.ToolRequirement tool) {
        return toolPreviewRenderer.createToolPreviewItem(tool);
    }

    private int resolveToolPreviewOptionsSize(CuttingBoardRecipe.ToolRequirement tool) {
        return toolPreviewRenderer.resolveToolPreviewOptionsSize(tool);
    }

    private List<ItemStack> resolveToolPreviewOptions(CuttingBoardRecipe.ToolRequirement tool) {
        return toolPreviewRenderer.resolveToolPreviewOptions(tool);
    }

    boolean isDisplayableItem(ItemStack item) {
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
            offset = currentLayout.getString("offset", "");
            icon = currentLayout.getString("icon", "");
        }

        String composed = title.replace("<offset>", offset).replace("<icon>", icon);
        return composed;
    }

    // Special-recipe detail title with the per-condition images composed in. The title-layout
    // craftengine section may define sunlight / water / catalyst_info image strings (each its own
    // shift + image); the matching placeholder in the title resolves to that string when the recipe
    // has the condition, or to the "<key>-off" string (e.g. a cover image) when it does not. One
    // detail layout then shows exactly the conditions each recipe declares.
    private String resolveSpecialDetailTitle(String guiPath, String fallbackTitle,
                                             Map<String, String> placeholders, SpecialRecipeInfo info) {
        String title = applyTitlePlaceholders(fallbackTitle, placeholders);
        String offset = "";
        String icon = "";
        String sunlight = "";
        String water = "";
        String catalystInfo = "";

        var guiSection = plugin.getRecipeViewGuiSection();
        var layout = guiSection != null
                ? guiSection.getConfigurationSection(guiPath + ".title-layout.craftengine")
                : null;
        if (layout != null) {
            offset = layout.getString("offset", "");
            icon = layout.getString("icon", "");
            sunlight = conditionImage(layout, "sunlight", info != null && info.hasSunlight());
            water = conditionImage(layout, "water", info != null && info.hasWater());
            catalystInfo = conditionImage(layout, "catalyst_info", info != null && info.hasCatalystInfo());
        }

        String composed = title
                .replace("<offset>", offset)
                .replace("<icon>", icon)
                .replace("<sunlight>", sunlight)
                .replace("<water>", water)
                .replace("<catalyst_info>", catalystInfo);
        return composed;
    }

    // The "on" image string for an active condition, or the "<key>-off" cover string for an inactive
    // one (e.g. catalyst_info-off draws the none image over the mushroom slot); both default to empty.
    private String conditionImage(ConfigurationSection layout, String key, boolean active) {
        return active ? layout.getString(key, "") : layout.getString(key + "-off", "");
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
        // Keep the result item's own description instead of replacing it, matching the addon recipe books.
        if (meta.lore() != null && !meta.lore().isEmpty()) {
            lore.addAll(meta.lore());
            lore.add(Component.text(""));
        }
        lore.add(tr("gui.recipe.ingredients_label", NamedTextColor.GRAY));
        List<RecipeIngredient> ingredients = recipe.getIngredients();
        int displayedIngredients = Math.min(ingredients.size(), config.getRecipeListMaxPreviewIngredients());
        for (int i = 0; i < displayedIngredients; i++) {
            ingredientDisplay.appendCompactIngredientLore(lore, ingredients.get(i), player);
        }
        ingredientDisplay.appendMoreIngredientsLine(lore, ingredients.size() - displayedIngredients, player);
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
        // Keep the input item's own description instead of replacing it, matching the addon recipe books.
        if (meta.lore() != null && !meta.lore().isEmpty()) {
            lore.addAll(meta.lore());
            lore.add(Component.text(""));
        }
        lore.add(tr("gui.recipe.tool_line",
                formatToolListComponent(recipe.getTools(), player).colorIfAbsent(NamedTextColor.YELLOW)));
        // Show the input type so players can see when multiple alternatives exist without opening details.
        ingredientDisplay.appendCuttingBoardInputLore(lore, recipe.getInput(), player);
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

    ItemStack createIngredientDisplay(RecipeIngredient ingredient, Player player, int slot) {
        return ingredientDisplay.createIngredientDisplay(ingredient, player, slot);
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
            case INGREDIENT_OPTIONS -> handleIngredientOptionsClick(player, slot);
            case SPECIAL_RECIPE_LIST -> handleSpecialRecipeListClick(player, slot);
            case SPECIAL_RECIPE_DETAIL -> handleSpecialRecipeDetailClick(player, slot);
        }
    }

    private void handleMainMenuClick(Player player, int slot) {
        RecipeViewGuiConfig.MainMenuConfig menuConfig = config.getMainMenu();

        if (slot == menuConfig.getCookingPotSlot()) {
            backButtonCommandsEnabled = false;
            navigateToState(player, GuiState.COOKING_POT_LIST, false);
        } else if (slot == menuConfig.getCuttingBoardSlot()) {
            backButtonCommandsEnabled = false;
            navigateToState(player, GuiState.CUTTING_BOARD_LIST, false);
        } else if (slot == menuConfig.getRecipeBookSlot()
                && !FarmersDelightApi.get().recipeTypes().isEmpty()) {
            // Hand off to the generic addon recipe book (deferred a tick, like the editor handoff).
            plugin.scheduler().runLaterForEntity(player, () -> {
                if (player.isOnline()) {
                    // Backing out of the addon book returns to this recipe menu (where the player came from)
                    // instead of closing, which would strand them.
                    RecipeBookGui.openMenu(player, null,
                            () -> new RecipeViewGui(plugin, player).open(player));
                }
            }, 1L);
        } else if (slot == menuConfig.getSpecialRecipesSlot()) {
            navigateToState(player, GuiState.SPECIAL_RECIPE_LIST, false);
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
            recipes = craftability().filterCraftableCookingPotRecipes(recipes, player);
        }
        recipes = listVisibleTo(player, recipes, true);

        handleRecipeListClick(player, slot, listConfig, recipes, true);
    }

    private void handleCuttingBoardListClick(Player player, int slot) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CuttingBoardRecipe> recipes = plugin.getCuttingBoardRecipes().getSortedRecipes();
        if (craftableOnly) {
            recipes = craftability().filterCraftableCuttingBoardRecipes(recipes, player);
        }
        recipes = listVisibleTo(player, recipes, false);

        handleRecipeListClick(player, slot, listConfig, recipes, false);
    }

    private <T> void handleRecipeListClick(Player player, int slot, RecipeViewGuiConfig.RecipeListConfig listConfig,
                                           List<T> recipes, boolean isCookingPot) {
        List<Integer> recipeSlots = listConfig.getRecipeSlots();
        int itemsPerPage = recipeSlots.size();
        int totalPages = (int) Math.ceil(recipes.size() / (double) itemsPerPage);

        if (handlePageClick(player, slot, listConfig.getPrevPageSlot(), listConfig.getNextPageSlot(), totalPages)) {
            return;
        }
        if (slot == listConfig.getFilterSlot()) {
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
                navigateToState(player, GuiState.MAIN_MENU, false);
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
                fillButtonState = CookingPotFiller.FillButtonState.READY;
                recipeBackState = isCookingPot ? GuiState.COOKING_POT_LIST : GuiState.CUTTING_BOARD_LIST;
                // Fresh detail opened from a list: this is a new navigation root, so drop any prior jump chain.
                detailHistory.clear();
                navigateToState(player, GuiState.RECIPE_DETAIL, false);
            }
        }
    }

    private CookingPotFiller cookingPotFiller() {
        if (cookingPotFiller == null) {
            cookingPotFiller = new CookingPotFiller(plugin, cookingPotLocation, getActiveCookingPotRecipeGroup());
        }
        return cookingPotFiller;
    }

    private RecipeCraftability craftability() {
        if (craftability == null) {
            craftability = new RecipeCraftability(plugin, cookingPotLocation, getActiveCookingPotRecipeGroup());
        }
        return craftability;
    }

    private void handleRecipeDetailClick(Player player, int slot, boolean shiftClick) {
        RecipeViewGuiConfig.RecipeDetailConfig detailConfig = getActiveDetailConfig();
        
        if (slot == detailConfig.getBackSlot()) {
            if (runBackButtonCommands(player, detailConfig.getItem("back"))) {
                return;
            }
            if (!detailHistory.isEmpty()) {
                // Came here via a linked-recipe jump: return to the recipe it was opened from.
                restoreDetailState(player, detailHistory.pop());
                return;
            }
            if (returnToLinkedSource()) {
                return;
            }
            navigateToState(player, recipeBackState, true);
            return;
        }

        if (fromCookingPot && cookingPotMode && slot == detailConfig.getFillSlot()) {
            CookingPotFiller.FillResult result = cookingPotFiller().fillFromInventory(player, shiftClick, selectedRecipeId);
            if (result.returnToPot()) {
                closeGui(player);
                returnToCookingPot(player);
            } else {
                fillButtonState = result.buttonState();
                refreshContentsInPlace(player);
            }
            return;
        }

        RecipeIngredient clickedIngredient = ingredientAtDetailSlot(detailConfig, slot);
        if (clickedIngredient != null && openIngredientOptions(player, clickedIngredient)) {
            return;
        }
        if (slot == detailConfig.getMaterialsSlot()) {
            return;
        }

        if (slot == detailConfig.getArrowSlot()) {
            return;
        }

        ItemStack clickedItem = inventory.getItem(slot);
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }

        if (navigateToLinkedRecipe(player, clickedItem, false)) {
            return;
        }
        String specialId = craftability().findSpecialRecipe(clickedItem);
        if (specialId != null && !specialId.equals(selectedSpecialRecipeId)) {
            if (navigateToSpecialRecipeList(player, specialId)) {
                return;
            }
            detailHistory.push(new DetailState(false, cookingPotMode, selectedRecipeId, null, recipeBackState));
            selectedSpecialRecipeId = specialId;
            navigateToState(player, GuiState.SPECIAL_RECIPE_DETAIL, false);
            return;
        }
        navigateToAddonRecipe(player, clickedItem);
    }

    private boolean navigateToSpecialRecipeList(Player player, String specialId) {
        if (recipeNavigationBlocked()) {
            return true;
        }
        if (specialId == null || plugin.getSpecialRecipeRegistry() == null) {
            return false;
        }
        List<SpecialRecipeInfo> recipes = plugin.getSpecialRecipeRegistry().getAll();
        int index = -1;
        for (int i = 0; i < recipes.size(); i++) {
            SpecialRecipeInfo info = recipes.get(i);
            if (specialId.equals(info.id())) {
                if (!specialRecipeRenderer.isListOnlySpecial(info)) {
                    return false;
                }
                index = i;
                break;
            }
        }
        if (index < 0) {
            return false;
        }
        DetailState origin = switch (state) {
            case RECIPE_DETAIL -> new DetailState(false, cookingPotMode, selectedRecipeId, null, recipeBackState);
            case SPECIAL_RECIPE_DETAIL -> new DetailState(true, cookingPotMode, selectedRecipeId,
                    selectedSpecialRecipeId, GuiState.SPECIAL_RECIPE_LIST);
            case INGREDIENT_OPTIONS -> ingredientOptionsOrigin;
            default -> null;
        };
        if (origin != null) {
            detailHistory.push(origin);
        }
        int pageSize = Math.max(1, config.getSpecialRecipeList().getRecipeSlots().size());
        currentPage = index / pageSize;
        navigateToState(player, GuiState.SPECIAL_RECIPE_LIST, true);
        return true;
    }

    private void restoreDetailState(Player player, DetailState previous) {
        if (previous.specialDetail()) {
            selectedSpecialRecipeId = previous.selectedSpecialRecipeId();
            navigateToState(player, GuiState.SPECIAL_RECIPE_DETAIL, false);
            return;
        }
        cookingPotMode = previous.cookingPotMode();
        selectedRecipeId = previous.selectedRecipeId();
        currentToolIndex = 0;
        fillButtonState = CookingPotFiller.FillButtonState.READY;
        if (previous.recipeBackState() == GuiState.INGREDIENT_OPTIONS && ingredientOptionsOrigin != null) {
            // A linked recipe was opened from the expanded ingredient list. Keep that list as the return view.
            recipeBackState = ingredientOptionsOrigin.recipeBackState();
            navigateToState(player, GuiState.INGREDIENT_OPTIONS, false);
            return;
        }
        recipeBackState = previous.recipeBackState();
        navigateToState(player, GuiState.RECIPE_DETAIL, false);
    }

    /** Returns true when recipe-navigation.jumps is switched off, so the jump must not happen. */
    private boolean recipeNavigationBlocked() {
        return !plugin.getConfigBoolean(true, "recipe-navigation.jumps");
    }

    private boolean navigateToLinkedRecipe(Player player, ItemStack clickedItem, boolean fromSpecial) {
        if (recipeNavigationBlocked()) {
            return true;
        }
        RecipeCraftability.LinkedRecipe linkedRecipe = craftability().findLinkedRecipe(clickedItem, cookingPotMode);
        if (linkedRecipe == null) {
            return false;
        }
        if (!fromSpecial && linkedRecipe.cookingPot() == cookingPotMode
                && Objects.equals(linkedRecipe.recipeId(), selectedRecipeId)) {
            return true;
        }
        Object linkedTarget = linkedRecipe.cookingPot()
                ? plugin.getCookingPotRecipes().getRecipe(getActiveCookingPotRecipeGroup(), linkedRecipe.recipeId())
                : plugin.getCuttingBoardRecipes().getRecipe(linkedRecipe.recipeId());
        if (linkedTarget != null && isRecipeLocked(linkedTarget, linkedRecipe.cookingPot(), player)) {
            player.sendMessage(Component.translatable("recipe-discovery.locked-click").color(NamedTextColor.RED));
            return true;
        }
        detailHistory.push(new DetailState(fromSpecial, cookingPotMode, selectedRecipeId,
                fromSpecial ? selectedSpecialRecipeId : null,
                state == GuiState.INGREDIENT_OPTIONS
                        ? GuiState.INGREDIENT_OPTIONS
                        : (fromSpecial ? GuiState.SPECIAL_RECIPE_LIST : recipeBackState)));
        selectedRecipeId = linkedRecipe.recipeId();
        cookingPotMode = linkedRecipe.cookingPot();
        currentToolIndex = 0;
        fillButtonState = CookingPotFiller.FillButtonState.READY;
        navigateToState(player, GuiState.RECIPE_DETAIL, false);
        return true;
    }

    private RecipeViewGuiConfig.RecipeDetailConfig getActiveDetailConfig() {
        if (cookingPotMode) {
            return getActiveCookingPotDetailConfig();
        }
        return config.getCuttingBoardDetail();
    }

    private RecipeViewGuiConfig.RecipeDetailConfig getActiveCookingPotDetailConfig() {
        String customId = getActiveCookingPotRecipeGroup();
        if (customId != null && !customId.isBlank() && !config.hasCustomCookingPotDetail(customId)
                && RecipeViewCache.warnMissingCustomDetailOnce(customId)) {
            plugin.getLogger().warning(I18n.formatNamedArgs("console.gui.missing_custom_recipe_detail",
                    "id", customId));
        }
        RecipeViewGuiConfig.RecipeDetailConfig detailConfig = config.getCookingPotDetail(customId);
        warnIfCookingPotDetailTooSmall(customId, detailConfig);
        return detailConfig;
    }

    private void warnIfCookingPotDetailTooSmall(String customId, RecipeViewGuiConfig.RecipeDetailConfig detailConfig) {
        String warningKey = customId == null || customId.isBlank() ? "default" : customId;
        if (!RecipeViewCache.warnCapacityOnce(warningKey)) {
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
        RecipeEditorView.openFromViewerLater(plugin, player, recipeId, isCookingPot);
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

    private void navigateToState(Player player, GuiState newState, boolean preservePage) {
        // Entering a list from the main menu starts a fresh browse; returning from a detail view keeps the page.
        if (!preservePage
                && (newState == GuiState.MAIN_MENU || newState == GuiState.COOKING_POT_LIST || newState == GuiState.CUTTING_BOARD_LIST)) {
            currentPage = 0;
        }
        if (newState == GuiState.RECIPE_DETAIL) {
            currentToolIndex = 0;
            currentToolPreviewIndex = 0;
            toolCycle.reset();
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

    // Returns true and flips the page when slot is a usable prev/next button; false otherwise, so callers
    // can fall through to their other slot handlers.
    private boolean handlePageClick(Player player, int slot, int prevSlot, int nextSlot, int totalPages) {
        if (slot == prevSlot && currentPage > 0) {
            changePage(player, -1);
            return true;
        }
        if (slot == nextSlot && currentPage < totalPages - 1) {
            changePage(player, 1);
            return true;
        }
        return false;
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

    private boolean returnToLinkedSource() {
        if (onExit == null) {
            return false;
        }
        close();
        removeFromActiveGuis(playerId);
        onExit.run();
        return true;
    }

    private boolean runBackButtonCommands(Player player, GuiConfig.GuiItem backItem) {
        if (!backButtonCommandsEnabled || player == null || backItem == null || backItem.hasNoCommands()) {
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
        RecipeViewCache.clearConfig();
        RecipeIngredientIcons.clearItemCache();
        // Soft re-enable must register a new dispatcher after disable removes the listener.
        GuiListenerRegistrar.reset(RecipeViewEventDispatcher.class);
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
                closeViewerInventory(player);
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
        GuiListenerRegistrar.ensureRegistered(RecipeViewEventDispatcher.class,
                RecipeViewEventDispatcher::new, plugin);
    }

    static RecipeViewGui removeActiveGui(UUID playerId) {
        return activeGuis.remove(playerId);
    }

}
