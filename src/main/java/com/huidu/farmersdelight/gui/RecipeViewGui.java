package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.text.Collator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecipeViewGui implements InventoryHolder {

    private static final Map<UUID, RecipeViewGui> activeGuis = new ConcurrentHashMap<>();
    private static volatile boolean listenerRegistered = false;
    private static volatile RecipeViewGuiConfig cachedConfig = null;
    private static final Map<Key, ItemStack> itemCache = new ConcurrentHashMap<>();
    private static final ItemStack EMPTY_SLOT_BACKGROUND = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern SHIFT_TAG_PATTERN = Pattern.compile("<shift:(-?\\d+)>");
    private static final Collator DISPLAY_NAME_COLLATOR = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);

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

    private final FarmersDelightPlugin plugin;
    private final UUID playerId;
    private final Player player;
    private final RecipeViewGuiConfig config;
    private Inventory inventory;
    private GuiState state = GuiState.MAIN_MENU;
    private int currentPage = 0;
    private volatile String selectedRecipeId = null;
    private boolean cookingPotMode = true;
    private volatile boolean closed = false;
    private volatile int currentToolIndex = 0;
    private volatile int currentToolPreviewIndex = 0;
    private int toolSwitchTicks = 0;
    private static final int TOOL_SWITCH_INTERVAL = 40;
    private final Map<Integer, List<ItemStack>> animatedIngredientSlots = new HashMap<>();
    private final Map<Integer, Integer> animatedIngredientIndices = new HashMap<>();
    private final Map<Integer, RecipeIngredient> animatedIngredientDefinitions = new HashMap<>();
    private int ingredientSwitchTicks = 0;
    private static final int INGREDIENT_SWITCH_INTERVAL = 20;
    
    private final boolean fromCookingPot;
    private final Location cookingPotLocation;
    private final Consumer<Void> tickCallback;
    private boolean ignoreNextClose = false;

    public RecipeViewGui(FarmersDelightPlugin plugin, Player player) {
        this(plugin, player, false, null);
    }

    public RecipeViewGui(FarmersDelightPlugin plugin, Player player, boolean fromCookingPot) {
        this(plugin, player, fromCookingPot, null);
    }

    public RecipeViewGui(FarmersDelightPlugin plugin, Player player, boolean fromCookingPot, Location cookingPotLocation) {
        this.plugin = plugin;
        this.playerId = player.getUniqueId();
        this.player = player;
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
        this.inventory = Bukkit.createInventory(this, 54, coloredTitle(initialTitle));
        
        this.tickCallback = v -> {
            if (!closed && state == GuiState.RECIPE_DETAIL) {
                if (!cookingPotMode) {
                    tickToolSwitch();
                }
                tickIngredientSwitch();
            }
        };
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
    }

    private RecipeViewGuiConfig createDefaultConfig() {
        RecipeViewGuiConfig.MainMenuConfig mainMenu = RecipeViewGuiConfig.MainMenuConfig.fromConfig(null);
        RecipeViewGuiConfig.RecipeListConfig recipeList = RecipeViewGuiConfig.RecipeListConfig.fromConfig(null);
        RecipeViewGuiConfig.RecipeDetailConfig cookingPotDetail = RecipeViewGuiConfig.RecipeDetailConfig.createCookingPotDefault();
        RecipeViewGuiConfig.RecipeDetailConfig cuttingBoardDetail = RecipeViewGuiConfig.RecipeDetailConfig.createCuttingBoardDefault();
        return new RecipeViewGuiConfig(mainMenu, recipeList, cookingPotDetail, cuttingBoardDetail, true, false);
    }

    public void open(Player player) {
        closed = false;

        RecipeViewGui existingGui = activeGuis.get(player.getUniqueId());
        if (existingGui != null && !existingGui.closed) {
            existingGui.close();
        }

        ensureListenerRegistered();
        activeGuis.put(player.getUniqueId(), this);

        refresh(player);
        player.openInventory(inventory);

        GuiTickManager.getInstance(plugin).registerCallback(player, tickCallback);
    }

    public void openCookingPotRecipes(Player player) {
        state = GuiState.COOKING_POT_LIST;
        cookingPotMode = true;
        currentPage = 0;
        open(player);
    }

    public void openCuttingBoardRecipes(Player player) {
        state = GuiState.CUTTING_BOARD_LIST;
        cookingPotMode = false;
        currentPage = 0;
        open(player);
    }

    private void tickToolSwitch() {
        toolSwitchTicks++;
        if (toolSwitchTicks >= TOOL_SWITCH_INTERVAL) {
            toolSwitchTicks = 0;
            
            CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(selectedRecipeId);
            if (recipe == null || recipe.getTools() == null || recipe.getTools().isEmpty()) {
                return;
            }

            List<CuttingBoardRecipe.ToolRequirement> tools = recipe.getTools();
            int safeToolIndex = currentToolIndex % tools.size();
            List<ItemStack> previewOptions = resolveToolPreviewOptions(tools.get(safeToolIndex).key());

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
        Key currentTool = recipe.getTools().get(safeIndex).key();
        
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

    private void resetDetailAnimations() {
        animatedIngredientSlots.clear();
        animatedIngredientIndices.clear();
        animatedIngredientDefinitions.clear();
        ingredientSwitchTicks = 0;
    }

    private void refresh(Player player) {
        inventory.clear();

        switch (state) {
            case MAIN_MENU -> drawMainMenu(player);
            case COOKING_POT_LIST -> drawCookingPotList(player);
            case CUTTING_BOARD_LIST -> drawCuttingBoardList(player);
            case RECIPE_DETAIL -> drawRecipeDetail(player);
        }
    }

    private void drawMainMenu(Player player) {
        RecipeViewGuiConfig.MainMenuConfig menuConfig = config.getMainMenu();
        inventory = Bukkit.createInventory(this, menuConfig.getSize(),
                coloredTitle(resolveMenuTitle("main-menu", null, menuConfig.getTitle(), Map.of())));

        fillBackground(menuConfig);

        setGuiItem(menuConfig, "cooking_pot", menuConfig.getCookingPotSlot());
        setGuiItem(menuConfig, "cutting_board", menuConfig.getCuttingBoardSlot());
        setGuiItem(menuConfig, "back", menuConfig.getBackSlot());
    }

    private void drawCookingPotList(Player player) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CookingPotRecipe> recipes = new ArrayList<>(plugin.getCookingPotRecipes().getRecipes().values());
        recipes.sort(java.util.Comparator.comparing(CookingPotRecipe::getId));
        drawRecipeList(player, listConfig, recipes, true);
    }

    private void drawCuttingBoardList(Player player) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CuttingBoardRecipe> recipes = new ArrayList<>(plugin.getCuttingBoardRecipes().getRecipes().values());
        recipes.sort(java.util.Comparator.comparing(CuttingBoardRecipe::getId));
        drawRecipeList(player, listConfig, recipes, false);
    }

    private <T> void drawRecipeList(Player player, RecipeViewGuiConfig.RecipeListConfig listConfig, 
                                    List<T> recipes, boolean isCookingPot) {
        List<Integer> recipeSlots = listConfig.getRecipeSlots();
        int itemsPerPage = recipeSlots.size();
        int totalPages = Math.max(1, (int) Math.ceil(recipes.size() / (double) itemsPerPage));

        Map<String, String> titlePlaceholders = new HashMap<>();
        titlePlaceholders.put("page", String.valueOf(currentPage + 1));
        titlePlaceholders.put("total", String.valueOf(totalPages));
        String title = resolveMenuTitle("recipe-list", "level_1", listConfig.getTitle(), titlePlaceholders);
        inventory = Bukkit.createInventory(this, listConfig.getSize(), coloredTitle(title));

        fillBackground(listConfig);

        int startIndex = currentPage * itemsPerPage;
        for (int i = 0; i < recipeSlots.size(); i++) {
            int recipeIndex = startIndex + i;
            if (recipeIndex < recipes.size()) {
                ItemStack displayItem;
                if (isCookingPot) {
                    displayItem = createCookingPotRecipeDisplayItem((CookingPotRecipe) recipes.get(recipeIndex), player);
                } else {
                    displayItem = createCuttingBoardRecipeDisplayItem((CuttingBoardRecipe) recipes.get(recipeIndex), player);
                }
                inventory.setItem(recipeSlots.get(i), displayItem);
            }
        }

        drawListNavigation(listConfig, totalPages);
    }

    private void drawListNavigation(RecipeViewGuiConfig.RecipeListConfig listConfig, int totalPages) {
        if (currentPage > 0) {
            setGuiItem(listConfig, "prev_page", listConfig.getPrevPageSlot());
        }

        if (currentPage < totalPages - 1) {
            setGuiItem(listConfig, "next_page", listConfig.getNextPageSlot());
        }

        setGuiItem(listConfig, "back", listConfig.getBackSlot());

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

    private void drawRecipeDetail(Player player) {
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
        inventory = Bukkit.createInventory(this, detailConfig.getSize(), coloredTitle(title));
        resetDetailAnimations();
        currentToolPreviewIndex = 0;
        currentToolIndex = 0;
        toolSwitchTicks = 0;

        fillBackground(detailConfig);

        if (cookingPotMode) {
            CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(selectedRecipeId);
            if (recipe != null) {
                drawCookingPotDetail(recipe, detailConfig, player);
            }
        } else {
            CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(selectedRecipeId);
            if (recipe != null) {
                drawCuttingBoardDetail(recipe, detailConfig, player);
            }
        }

        setGuiItem(detailConfig, "back", detailConfig.getBackSlot());
    }

    private void drawCookingPotDetail(CookingPotRecipe recipe, RecipeViewGuiConfig.RecipeDetailConfig detailConfig, Player player) {
        if (detailConfig.getResultSlot() >= 0) {
            ItemStack resultItem = recipe.getResult().clone();
            ItemMeta resultMeta = resultItem.getItemMeta();
            resultMeta.displayName(itemNameComponent(recipe.getResult(), player).colorIfAbsent(NamedTextColor.GREEN));
            List<Component> resultLore = new ArrayList<>();
            resultLore.add(colored("&7" + I18n.get("gui.recipe.result", player)));
            resultLore.add(colored("&7" + I18n.get("gui.recipe.experience", player) + ": &e" + recipe.getExperience()));
            resultLore.add(colored("&7" + I18n.get("gui.recipe.cook_time", player) + ": &b"
                    + (recipe.getCookTime() / 20) + i18nOrDefault("gui.recipe.seconds_suffix", player, "s")));
            resultMeta.lore(resultLore);
            resultItem.setItemMeta(resultMeta);
            inventory.setItem(detailConfig.getResultSlot(), resultItem);
        }

        fillIngredientSlots(detailConfig.getIngredientSlots(), recipe.getIngredients(), player);

        if (recipe.needsContainer() && recipe.getContainer() != null && detailConfig.getContainerSlot() >= 0) {
            ItemStack containerItem = recipe.getContainer().clone();
            ItemMeta containerMeta = containerItem.getItemMeta();
            containerMeta.displayName(itemNameComponent(recipe.getContainer(), player).colorIfAbsent(NamedTextColor.AQUA));
            containerMeta.lore(List.of(colored("&7" + I18n.get("gui.recipe.container", player))));
            containerItem.setItemMeta(containerMeta);
            inventory.setItem(detailConfig.getContainerSlot(), containerItem);
        }

        setGuiItem(detailConfig, "arrow", detailConfig.getArrowSlot());
    }

    private void drawCuttingBoardDetail(CuttingBoardRecipe recipe, RecipeViewGuiConfig.RecipeDetailConfig detailConfig, Player player) {
        if (detailConfig.getInputSlot() >= 0) {
            ItemStack inputItem = recipe.getInputDisplay().clone();
            ItemMeta inputMeta = inputItem.getItemMeta();
            inputMeta.displayName(itemNameComponent(inputItem, player).colorIfAbsent(NamedTextColor.RED));
            inputMeta.lore(formatIngredientDetailLoreLines(recipe.getInput(), player, I18n.get("gui.recipe.input", player)));
            inputItem.setItemMeta(inputMeta);
            inventory.setItem(detailConfig.getInputSlot(), inputItem);
        }

        if (detailConfig.getToolSlot() >= 0) {
            List<CuttingBoardRecipe.ToolRequirement> tools = recipe.getTools();
            if (tools != null && !tools.isEmpty()) {
                int safeIndex = currentToolIndex % tools.size();
                Key currentTool = tools.get(safeIndex).key();
                ItemStack toolItem = createToolDisplayItem(currentTool, tools.size(), safeIndex, player);
                inventory.setItem(detailConfig.getToolSlot(), toolItem);
            }
        }

        fillResultSlots(detailConfig.getResultSlots(), recipe.getResults(), player);
    }

    private ItemStack createToolDisplayItem(Key toolKey, int totalTools, int currentIndex, Player player) {
        List<ItemStack> previewOptions = resolveToolPreviewOptions(toolKey);
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
        
        ItemMeta toolMeta = toolItem.getItemMeta();
        toolMeta.displayName(colored("&e" + I18n.get("gui.recipe.tool", player)));
        
        List<Component> lore = new ArrayList<>();
        lore.add(itemNameComponent(toolItem, player).colorIfAbsent(NamedTextColor.WHITE));
        if (totalTools > 1) {
            lore.add(colored("&7" + I18n.get("gui.recipe.auto_cycle", player)
                    .replace("{current}", String.valueOf(currentIndex + 1))
                    .replace("{total}", String.valueOf(totalTools))));
        }
        if (previewOptions.size() > 1) {
            lore.add(colored("&7" + I18n.get("gui.recipe.matches", player) + ": &e" + previewOptions.size()));
        }
        toolMeta.lore(lore);
        toolItem.setItemMeta(toolMeta);
        
        return toolItem;
    }

    private ItemStack createToolPreviewItem(Key toolKey) {
        List<ItemStack> previewOptions = resolveToolPreviewOptions(toolKey);
        if (!previewOptions.isEmpty()) {
            return previewOptions.get(0).clone();
        }

        return new ItemStack(Material.IRON_AXE);
    }

    private List<ItemStack> resolveToolPreviewOptions(Key toolKey) {
        List<ItemStack> previewOptions = new ArrayList<>();

        ItemStack directItem = createItemFromKey(toolKey);
        if (isDisplayableItem(directItem)) {
            previewOptions.add(directItem);
            return previewOptions;
        }

        previewOptions.addAll(createTaggedToolPreviewItems(toolKey));
        if (!previewOptions.isEmpty()) {
            return previewOptions;
        }

        ItemStack fallback = switch (toolKey.toString()) {
            case "farmersdelight:knives" -> createKnifePreviewItem();
            case "farmersdelight:axe_dig", "farmersdelight:axe_strip", "minecraft:axes" -> new ItemStack(Material.IRON_AXE);
            case "farmersdelight:pickaxe_dig" -> new ItemStack(Material.IRON_PICKAXE);
            case "farmersdelight:shovel_dig" -> new ItemStack(Material.IRON_SHOVEL);
            case "minecraft:shears" -> new ItemStack(Material.SHEARS);
            default -> createItemFromKey(toolKey);
        };
        if (isDisplayableItem(fallback)) {
            previewOptions.add(fallback);
        }
        return previewOptions;
    }

    private ItemStack createKnifePreviewItem() {
        for (String knifeId : plugin.getConfig().getStringList("knife-config.items")) {
            ItemStack knife = createItemFromKey(Key.of(knifeId));
            if (knife != null && knife.getType() != Material.BARRIER && !knife.getType().isAir()) {
                return knife;
            }
        }
        return new ItemStack(Material.IRON_SWORD);
    }

    private List<ItemStack> createTaggedToolPreviewItems(Key toolKey) {
        List<ItemStack> previewItems = new ArrayList<>();
        try {
            for (UniqueKey uniqueKey : plugin.getCraftEngine().itemManager().itemIdsByTag(toolKey)) {
                ItemStack item = createItemFromKey(uniqueKey.key());
                if (isDisplayableItem(item)) {
                    previewItems.add(item);
                }
            }
        } catch (Exception ignored) {
            // Some action keys are not CE item tags; fall back to explicit tool previews below.
        }

        for (ItemStack item : ItemUtils.createVanillaTagDisplayItems(toolKey, Set.of(), Set.of())) {
            if (isDisplayableItem(item)) {
                previewItems.add(item);
            }
        }
        return previewItems;
    }

    private boolean isDisplayableItem(ItemStack item) {
        return item != null && item.getType() != Material.BARRIER && !item.getType().isAir();
    }

    private void fillIngredientSlots(List<Integer> slots, List<RecipeIngredient> ingredients, Player player) {
        for (int i = 0; i < slots.size(); i++) {
            if (i < ingredients.size()) {
                ItemStack ingredientDisplay = createIngredientDisplay(ingredients.get(i), player, slots.get(i));
                inventory.setItem(slots.get(i), ingredientDisplay);
            } else {
                inventory.setItem(slots.get(i), EMPTY_SLOT_BACKGROUND);
            }
        }
    }

    private void fillResultSlots(List<Integer> slots, List<CuttingBoardRecipe.ResultEntry> results, Player player) {
        for (int i = 0; i < slots.size(); i++) {
            if (i < results.size()) {
                CuttingBoardRecipe.ResultEntry resultEntry = results.get(i);
                ItemStack resultDisplay = resultEntry.item().clone();
                ItemMeta resultMeta = resultDisplay.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (resultMeta.hasLore()) {
            lore = new ArrayList<>(resultMeta.lore());
        }
                lore.add(0, colored("&a" + I18n.get("gui.recipe.result", player)));
                if (resultEntry.chance() < 1.0d) {
                    lore.add(1, colored(I18n.formatNamed(
                            "gui.recipe.chance",
                            player,
                            Map.of("value", String.valueOf((int) Math.round(resultEntry.chance() * 100)))
                    )));
                }
                resultMeta.lore(lore);
                resultDisplay.setItemMeta(resultMeta);
                inventory.setItem(slots.get(i), resultDisplay);
            } else {
                inventory.setItem(slots.get(i), EMPTY_SLOT_BACKGROUND);
            }
        }
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
                GuiConfig.GuiItem slotItem = guiConfig.getItem(slotType);
                if (slotItem != null) {
                    inventory.setItem(i, slotItem.createItem());
                } else {
                    inventory.setItem(i, EMPTY_SLOT_BACKGROUND);
                }
            }
        }
    }

    private ItemStack createCookingPotRecipeDisplayItem(CookingPotRecipe recipe, Player player) {
        ItemStack result = recipe.getResult().clone();
        ItemMeta meta = result.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(colored("&7" + I18n.get("gui.recipe.ingredients", player) + ":"));
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            appendIndentedIngredientLore(lore, ingredient, player);
        }
        if (recipe.needsContainer()) {
            lore.add(colored("&7" + I18n.get("gui.recipe.container", player) + ": ")
                    .append(itemNameComponent(recipe.getContainer(), player).colorIfAbsent(NamedTextColor.AQUA)));
        }
        lore.add(Component.text(""));
        lore.add(colored("&e" + I18n.get("gui.recipe.click_to_view", player)));

        meta.lore(lore);
        result.setItemMeta(meta);
        return result;
    }

    private ItemStack createCuttingBoardRecipeDisplayItem(CuttingBoardRecipe recipe, Player player) {
        ItemStack input = recipe.getInputDisplay().clone();
        ItemMeta meta = input.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(colored("&7" + I18n.get("gui.recipe.tool", player) + ": ")
                .append(formatToolListComponent(recipe.getTools(), player).colorIfAbsent(NamedTextColor.YELLOW)));
        lore.add(colored("&7" + I18n.get("gui.recipe.results", player) + ":"));
        for (CuttingBoardRecipe.ResultEntry result : recipe.getResults()) {
            Component line = itemNameComponent(result.item(), player).colorIfAbsent(NamedTextColor.WHITE);
            if (result.chance() < 1.0d) {
                line = line.append(Component.text(" (" + (int) Math.round(result.chance() * 100) + "%)", NamedTextColor.GRAY));
            }
            lore.add(colored("&8- ").append(line));
        }
        lore.add(Component.text(""));
        lore.add(colored("&e" + I18n.get("gui.recipe.click_to_view", player)));

        meta.lore(lore);
        input.setItemMeta(meta);
        return input;
    }

    private void appendIndentedIngredientLore(List<Component> lore, RecipeIngredient ingredient, Player player) {
        List<Component> ingredientLines = formatIngredientLoreLines(ingredient, player);
        if (ingredientLines.isEmpty()) {
            return;
        }

        lore.add(colored("&8- ").append(ingredientLines.get(0).colorIfAbsent(NamedTextColor.WHITE)));
        for (int i = 1; i < ingredientLines.size(); i++) {
            lore.add(colored("&8  ").append(ingredientLines.get(i).colorIfAbsent(NamedTextColor.WHITE)));
        }
    }

    private ItemStack createIngredientDisplay(RecipeIngredient ingredient, Player player, int slot) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            List<Component> lore = new ArrayList<>();
            ItemStack display = createItemFromKey(itemIngredient.key());
            lore.add(colored("&7" + I18n.get("gui.recipe.ingredient", player)));
            if (config.isShowIngredientIds()) {
                lore.add(colored("&7" + itemIngredient.key()));
            }
            return createLabeledIngredientDisplay(display, lore, player, itemNameComponent(display, player));
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return createAnimatedOrStaticIngredientDisplay(slot, tagIngredient, resolveTagIngredientOptions(tagIngredient), player);
        }

        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            return createAnimatedOrStaticIngredientDisplay(slot, choiceIngredient, resolveIngredientOptions(choiceIngredient), player);
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
                List.of(colored("&7" + I18n.get("gui.recipe.ingredient", player))),
                player,
                itemNameComponent(currentDisplay, player)
        );
    }

    private ItemStack createTagIngredientDisplay(RecipeIngredient.Tag tagIngredient, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        ItemStack display = currentDisplay.clone();
        ItemMeta meta = display.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(colored("&7" + I18n.get("gui.recipe.ingredient", player)));
        lore.add(colored("&7" + I18n.get("gui.recipe.matches", player) + ": &e" + options.size()));
        appendCyclePosition(lore, currentDisplay, options, player);
        if (config.isShowIngredientIds()) {
            lore.add(colored("&7" + I18n.get("gui.recipe.tag", player) + ": &f#" + tagIngredient.key()));
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
        lore.add(colored("&7" + I18n.get("gui.recipe.ingredient", player)));
        lore.add(colored("&7" + I18n.get("gui.recipe.any_of", player) + ": &e" + choiceIngredient.options().size()));
        lore.add(colored("&7" + I18n.get("gui.recipe.matches", player) + ": &e" + options.size()));
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
            lore.add(colored("&c- ").append(itemNameComponent(createItemFromKey(excludedItem), player).colorIfAbsent(NamedTextColor.RED)));
        }
        for (Key excludedTag : tagIngredient.excludedTags()) {
            lore.add(colored("&c- #" + excludedTag));
        }
    }

    private List<ItemStack> resolveTagIngredientOptions(RecipeIngredient.Tag tagIngredient) {
        Map<String, ItemStack> uniqueDisplays = new LinkedHashMap<>();
        for (UniqueKey uniqueKey : plugin.getCraftEngine().itemManager().itemIdsByTag(tagIngredient.key())) {
            if (tagIngredient.excludedItems().contains(uniqueKey.key())) {
                continue;
            }
            boolean blockedByTag = false;
            for (Key excludedTag : tagIngredient.excludedTags()) {
                if (plugin.getCraftEngine().itemManager().itemIdsByTag(excludedTag).stream()
                        .anyMatch(candidate -> candidate.key().equals(uniqueKey.key()))) {
                    blockedByTag = true;
                    break;
                }
            }
            if (blockedByTag) {
                continue;
            }
            ItemStack item = createItemFromKey(uniqueKey.key());
            if (item == null || item.getType().isAir() || item.getType() == Material.BARRIER) {
                continue;
            }
            uniqueDisplays.putIfAbsent(uniqueKey.toString(), item);
        }
        for (ItemStack item : ItemUtils.createVanillaTagDisplayItems(
                tagIngredient.key(),
                tagIngredient.excludedItems(),
                tagIngredient.excludedTags())) {
            uniqueDisplays.putIfAbsent(buildIngredientDisplayKey(item), item);
        }
        return sortIngredientDisplayItems(uniqueDisplays.values());
    }

    private List<ItemStack> resolveIngredientOptions(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            ItemStack item = createItemFromKey(itemIngredient.key());
            if (item == null || item.getType().isAir() || item.getType() == Material.BARRIER) {
                return List.of();
            }
            return List.of(item);
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return resolveTagIngredientOptions(tagIngredient);
        }

        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            Map<String, ItemStack> uniqueDisplays = new LinkedHashMap<>();
            for (RecipeIngredient option : choiceIngredient.options()) {
                for (ItemStack display : resolveIngredientOptions(option)) {
                    uniqueDisplays.putIfAbsent(buildIngredientDisplayKey(display), display);
                }
            }
            return sortIngredientDisplayItems(uniqueDisplays.values());
        }

        return List.of();
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
            return createAnimatedIngredientDisplay(ingredient, options.get(0), options, player);
        }

        return createIngredientPlaceholderDisplay(ingredient, player);
    }

    private ItemStack createIngredientPlaceholderDisplay(RecipeIngredient ingredient, Player player) {
        return createLabeledIngredientDisplay(
                new ItemStack(Material.NAME_TAG),
                formatIngredientLoreLines(ingredient, player),
                player,
                colored("&b" + I18n.get("gui.recipe.ingredient", player))
        );
    }

    private ItemStack createUnknownIngredientDisplay(Player player) {
        return createLabeledIngredientDisplay(
                new ItemStack(Material.BARRIER),
                List.of(colored("&f" + I18n.get("gui.recipe.unknown", player))),
                player,
                colored("&b" + I18n.get("gui.recipe.ingredient", player))
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

    private List<Component> formatIngredientDetailLoreLines(RecipeIngredient ingredient, Player player, String category) {
        List<Component> lines = new ArrayList<>();
        lines.add(colored("&7" + category));
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            if (config.isShowIngredientIds()) {
                lines.add(colored("&7" + itemIngredient.key()));
            }
            return lines;
        }
        lines.addAll(formatIngredientLoreLines(ingredient, player));
        return removeAdjacentDuplicateComponents(lines);
    }

    private List<Component> removeAdjacentDuplicateComponents(List<Component> lines) {
        if (lines.size() < 2) {
            return lines;
        }
        List<Component> result = new ArrayList<>(lines.size());
        String previous = null;
        for (Component line : lines) {
            String serialized = LEGACY.serialize(line);
            if (!serialized.equals(previous)) {
                result.add(line);
            }
            previous = serialized;
        }
        return result;
    }

    private String buildIngredientDisplayKey(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        return "minecraft:" + item.getType().name().toLowerCase();
    }

    private ItemStack createItemFromKey(Key key) {
        ItemStack cached = itemCache.get(key);
        if (cached != null) return cached.clone();
        try {
            ItemStack customItem = ItemUtils.createItem(key);
            if (customItem != null) {
                itemCache.put(key, customItem.clone());
                return customItem;
            }

            NamespacedKey materialKey = NamespacedKey.fromString(key.toString());
            if (materialKey != null) {
                Material material = Registry.MATERIAL.get(materialKey);
                if (material != null) {
                    ItemStack item = new ItemStack(material);
                    itemCache.put(key, item.clone());
                    return item;
                }
            }
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().fine("Failed to create item from key: " + key);
            }
        }
        return new ItemStack(Material.BARRIER);
    }

    private String formatIngredient(RecipeIngredient ingredient, Player player) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return getItemDisplayName(createItemFromKey(itemIngredient.key()), player);
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            return summarizeLabels(formatChoiceIngredientLabels(choiceIngredient, player), 4);
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return formatTagIngredientSummary(tagIngredient, player);
        }
        return unknownRecipeText(player);
    }

    private List<Component> formatIngredientLoreLines(RecipeIngredient ingredient, Player player) {
        List<Component> lines = new ArrayList<>();
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            lines.add(itemNameComponent(createItemFromKey(itemIngredient.key()), player).colorIfAbsent(NamedTextColor.WHITE));
            return lines;
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            List<ItemStack> options = resolveTagIngredientOptions(tagIngredient);
            if (options.isEmpty()) {
                lines.add(colored("&7" + I18n.get("gui.recipe.no_matching_items", player)));
                if (config.isShowIngredientIds()) {
                    lines.add(colored("&8#" + tagIngredient.key()));
                }
                return lines;
            }
            appendItemPreviewLore(lines, options, 5, player);
            if (config.isShowIngredientIds()) {
                lines.add(colored("&8#" + tagIngredient.key()));
                appendTagExclusions(lines, tagIngredient, player);
            }
            return lines;
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            List<ItemStack> options = resolveIngredientOptions(choiceIngredient);
            if (options.isEmpty()) {
                lines.add(colored("&7" + I18n.get("gui.recipe.no_matching_items", player)));
                return lines;
            }
            appendItemPreviewLore(lines, options, 5, player);
            return lines;
        }
        lines.add(Component.text(I18n.get("gui.recipe.unknown", player)));
        return lines;
    }

    private String summarizeLabels(List<String> labels, int remainingCount) {
        if (labels.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(labels.get(i));
        }
        if (remainingCount > 0) {
            builder.append(", +").append(remainingCount);
        }
        return builder.toString();
    }

    private Component formatToolListComponent(List<CuttingBoardRecipe.ToolRequirement> tools, Player player) {
        Component result = Component.empty();
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                result = result.append(Component.text(", ", NamedTextColor.GRAY));
            }
            result = result.append(itemNameComponent(createToolPreviewItem(tools.get(i).key()), player));
        }
        return result;
    }

    private void appendItemPreviewLore(List<Component> lore, List<ItemStack> options, int previewLimit, Player player) {
        appendItemPreviewLore(lore, options, previewLimit, player, null);
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
        String currentKey = buildIngredientDisplayKey(currentDisplay);
        List<ItemStack> filtered = new ArrayList<>();
        for (ItemStack option : options) {
            if (!buildIngredientDisplayKey(option).equals(currentKey)) {
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
            for (ItemStack display : resolveIngredientOptions(option)) {
                if (!isDisplayableItem(display)) {
                    continue;
                }
                displayOptions.putIfAbsent(buildIngredientDisplayKey(display), display);
            }
        }

        if (displayOptions.isEmpty()) {
            lore.add(colored("&7" + I18n.get("gui.recipe.no_matching_items", player)));
            return;
        }

        appendItemPreviewLore(lore, new ArrayList<>(displayOptions.values()), previewLimit, player, currentDisplay);
    }

    private void appendCyclePosition(List<Component> lore, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        if (options.size() <= 1) {
            return;
        }
        String currentKey = buildIngredientDisplayKey(currentDisplay);
        int currentIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            if (buildIngredientDisplayKey(options.get(i)).equals(currentKey)) {
                currentIndex = i + 1;
                break;
            }
        }
        lore.add(colored(I18n.formatNamed(
                "gui.recipe.auto_cycle",
                player,
                Map.of(
                        "current", String.valueOf(Math.max(1, currentIndex)),
                        "total", String.valueOf(options.size())
                )
        )));
    }

    private void appendMoreItemsLine(List<Component> lore, int remainingCount, Player player) {
        if (remainingCount <= 0) {
            return;
        }
        lore.add(colored(I18n.formatNamed(
                "gui.recipe.more_items",
                player,
                Map.of("count", String.valueOf(remainingCount))
        )));
    }

    private List<String> formatChoiceIngredientLabels(RecipeIngredient.Choice choiceIngredient, Player player) {
        List<String> labels = new ArrayList<>();
        for (RecipeIngredient option : choiceIngredient.options()) {
            labels.add(formatIngredient(option, player));
        }
        return labels;
    }

    private String formatTagIngredientSummary(RecipeIngredient.Tag tagIngredient, Player player) {
        List<ItemStack> options = resolveTagIngredientOptions(tagIngredient);
        if (options.isEmpty()) {
            return noMatchingItemsText(player);
        }

        List<String> labels = new ArrayList<>();
        int previewCount = Math.min(options.size(), 4);
        for (int i = 0; i < previewCount; i++) {
            labels.add(getItemDisplayName(options.get(i), player));
        }
        return summarizeLabels(labels, options.size() - previewCount);
    }

    private String noMatchingItemsText(Player player) {
        return I18n.get("gui.recipe.no_matching_items", player);
    }

    private String unknownRecipeText(Player player) {
        return I18n.get("gui.recipe.unknown", player);
    }

    private List<ItemStack> sortIngredientDisplayItems(Collection<ItemStack> items) {
        List<ItemStack> sorted = new ArrayList<>(items);
        sorted.sort((left, right) -> {
            String leftName = ItemUtils.getDisplayName(left, (String) null);
            String rightName = ItemUtils.getDisplayName(right, (String) null);
            int displayCompare = DISPLAY_NAME_COLLATOR.compare(leftName, rightName);
            if (displayCompare != 0) {
                return displayCompare;
            }

            String leftId = buildIngredientDisplayKey(left);
            String rightId = buildIngredientDisplayKey(right);
            int keyCompare = leftId.compareTo(rightId);
            if (keyCompare != 0) {
                return keyCompare;
            }

            return left.getType().name().compareTo(right.getType().name());
        });
        return sorted;
    }

    private String getItemDisplayName(ItemStack item, Player player) {
        if (item == null) {
            return unknownRecipeText(player);
        }
        return ItemUtils.getDisplayName(item, player);
    }

    private Component itemNameComponent(ItemStack item, Player player) {
        return ItemUtils.getDisplayComponent(item, player);
    }

    private String i18nOrDefault(String key, Player player, String fallback) {
        String value = I18n.get(key, player);
        if (key.equals(value)) {
            return fallback;
        }
        return value;
    }

    private Component colored(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        String normalized = text.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7");
        return LEGACY.deserialize(normalized);
    }

    private Component coloredTitle(String text) {
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

    @Override
    public Inventory getInventory() {
        return inventory;
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
            case RECIPE_DETAIL -> handleRecipeDetailClick(player, slot);
        }
    }

    void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) {
            return;
        }

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleMainMenuClick(Player player, int slot) {
        RecipeViewGuiConfig.MainMenuConfig menuConfig = config.getMainMenu();

        if (slot == menuConfig.getCookingPotSlot()) {
            navigateToState(player, GuiState.COOKING_POT_LIST);
        } else if (slot == menuConfig.getCuttingBoardSlot()) {
            navigateToState(player, GuiState.CUTTING_BOARD_LIST);
        } else if (slot == menuConfig.getBackSlot()) {
            closeGui(player);
        }
    }

    private void handleCookingPotListClick(Player player, int slot) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CookingPotRecipe> recipes = new ArrayList<>(plugin.getCookingPotRecipes().getRecipes().values());
        recipes.sort(java.util.Comparator.comparing(CookingPotRecipe::getId));
        
        handleRecipeListClick(player, slot, listConfig, recipes, true);
    }

    private void handleCuttingBoardListClick(Player player, int slot) {
        RecipeViewGuiConfig.RecipeListConfig listConfig = config.getRecipeList();
        List<CuttingBoardRecipe> recipes = new ArrayList<>(plugin.getCuttingBoardRecipes().getRecipes().values());
        recipes.sort(java.util.Comparator.comparing(CuttingBoardRecipe::getId));
        
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
        } else if (slot == listConfig.getBackSlot()) {
            if (fromCookingPot && isCookingPot) {
                closeGui(player);
                returnToCookingPot(player);
            } else {
                navigateToState(player, GuiState.MAIN_MENU);
            }
        } else if (recipeSlots.contains(slot)) {
            int slotIndex = recipeSlots.indexOf(slot);
            int recipeIndex = currentPage * itemsPerPage + slotIndex;
            if (recipeIndex < recipes.size()) {
                String recipeId;
                if (isCookingPot) {
                    recipeId = ((CookingPotRecipe) recipes.get(recipeIndex)).getId();
                } else {
                    recipeId = ((CuttingBoardRecipe) recipes.get(recipeIndex)).getId();
                }
                selectedRecipeId = recipeId;
                cookingPotMode = isCookingPot;
                navigateToState(player, GuiState.RECIPE_DETAIL);
            }
        }
    }

    private void handleRecipeDetailClick(Player player, int slot) {
        RecipeViewGuiConfig.RecipeDetailConfig detailConfig = getActiveDetailConfig();
        
        if (slot == detailConfig.getBackSlot()) {
        GuiState targetState = GuiState.CUTTING_BOARD_LIST;
        if (cookingPotMode) {
            targetState = GuiState.COOKING_POT_LIST;
        }
            navigateToState(player, targetState);
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

        selectedRecipeId = linkedRecipe.recipeId();
        cookingPotMode = linkedRecipe.cookingPot();
        currentToolIndex = 0;
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
        for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getRecipes().values()) {
            if (sameRecipeItem(recipe.getResult(), item)) {
                return new LinkedRecipe(recipe.getId(), true);
            }
        }
        return null;
    }

    private LinkedRecipe findCuttingBoardRecipeByResult(ItemStack item) {
        for (CuttingBoardRecipe recipe : plugin.getCuttingBoardRecipes().getRecipes().values()) {
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

    private RecipeViewGuiConfig.RecipeDetailConfig getActiveDetailConfig() {
        if (cookingPotMode) {
            return config.getCookingPotDetail();
        }
        return config.getCuttingBoardDetail();
    }

    private void navigateToState(Player player, GuiState newState) {
        if (newState == GuiState.MAIN_MENU || newState == GuiState.COOKING_POT_LIST || newState == GuiState.CUTTING_BOARD_LIST) {
            currentPage = 0;
        }
        if (newState == GuiState.RECIPE_DETAIL) {
            currentToolIndex = 0;
        }
        state = newState;
        refresh(player);
        reopenInventory(player);
    }

    private void changePage(Player player, int delta) {
        currentPage += delta;
        refresh(player);
        reopenInventory(player);
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

    void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) return;
        if (closed) return;
        if (ignoreNextClose) {
            ignoreNextClose = false;
            return;
        }

        close();
        activeGuis.remove(event.getPlayer().getUniqueId());
    }

    private void close() {
        if (closed) return;
        closed = true;
        GuiTickManager.getInstance(plugin).unregisterCallback(tickCallback);
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
        itemCache.clear();
    }

    private void ensureListenerRegistered() {
        if (listenerRegistered) return;
        synchronized (RecipeViewGui.class) {
            if (listenerRegistered) return;
            Bukkit.getPluginManager().registerEvents(new EventDispatcher(), plugin);
            listenerRegistered = true;
        }
    }

    public static class EventDispatcher implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof RecipeViewGui gui) {
                gui.onClick(event);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof RecipeViewGui gui) {
                gui.onDrag(event);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof RecipeViewGui gui) {
                gui.onClose(event);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerQuit(PlayerQuitEvent event) {
            RecipeViewGui gui = activeGuis.remove(event.getPlayer().getUniqueId());
            if (gui != null && !gui.closed) {
                gui.close();
            }
        }
    }

    public static RecipeViewGui getActiveGui(UUID playerId) {
        return activeGuis.get(playerId);
    }
}

