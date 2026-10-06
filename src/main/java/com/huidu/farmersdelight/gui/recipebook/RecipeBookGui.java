package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.gui.editor.RecipeEditorView;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.recipe.FillOutcome;
import com.huidu.farmersdelight.api.recipe.JumpTarget;
import com.huidu.farmersdelight.api.recipe.RecipeBookLayout;
import com.huidu.farmersdelight.api.recipe.RecipeFiller;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecipeBookGui implements InventoryHolder {

    enum View { MENU, LIST, DETAIL }

    private static volatile RecipeBookGuiConfig cachedConfig;

    // book instance instead of on every click on a jump icon.

    private View view = View.MENU;
    private RecipeType type;
    private String recipeId;
    private int page;
    private RecipeFiller filler;
    private Inventory inventory;
    // When only one recipe type is registered, skip the category chooser and open its list directly.
    private boolean singleType;
    private boolean directDetail;
    // Optional "craftable only" filter (toggled by a 'filter' button); needs the viewer to test inventories.
    private boolean filterCraftable;
    private Player viewer;
    // Optional action run instead of closing when the top-level view (menu, or a single-type list with no
    // station filler) is backed out of. Lets a caller that opened this book from its own menu (FarmersDelight's
    // recipe menu handing off to the addon book) send the player back to that menu instead of an empty screen.
    // Null keeps the original behavior: the terminal back just closes the inventory.
    private Runnable onExit;
    // Back-navigation history: each drill-in (menu to list, list to detail, and a detail-to-detail jump)
    // pushes the page it left, so "back" returns there instead of always dropping to the current type's list.
    // A jump into another recipe's detail (the keg fluid icon opening the recipe that makes that fluid) thus
    // backs out to the recipe it was opened from, and back chains through multi-hop jumps. Empty history keeps
    // the original terminal behavior (list back to menu/station, menu back closes). Touched only inside
    // handleClick, which runs single-threaded per viewer, so the plain ArrayDeque needs no synchronization.
    private final Deque<ViewState> history = new ArrayDeque<>();

    private record ViewState(View view, RecipeType type, String recipeId, int page) {
    }

    // The resource-pack animated item advances client-side; the server places it once per detail open.
    private static volatile ItemStack progressItem;
    private List<Integer> progressSlots = List.of();

    public static void openMenu(Player player, RecipeFiller filler) {
        openMenu(player, filler, null);
    }

    public static void openMenu(Player player, RecipeFiller filler, Runnable onExit) {
        RecipeBookListener.ensureRegistered();
        RecipeBookGui gui = new RecipeBookGui();
        gui.filler = filler;
        gui.viewer = player;
        gui.onExit = onExit;
        List<RecipeType> types = FarmersDelightApi.get().recipeTypes();
        if (types.size() == 1) {
            gui.singleType = true;
            gui.drawList(types.getFirst(), 0);
        } else {
            gui.drawMenu();
        }
        player.openInventory(gui.inventory);
    }

    public static void openEditor(Player player, RecipeType type, String recipeId) {
        RecipeEditorView.open(player, type, recipeId);
    }

    public static void openType(Player player, RecipeType type, RecipeFiller filler) {
        RecipeBookListener.ensureRegistered();
        RecipeBookGui gui = new RecipeBookGui();
        gui.filler = filler;
        gui.viewer = player;
        gui.singleType = true;
        gui.drawList(type, 0);
        player.openInventory(gui.inventory);
    }

    // Opens the addon book directly at one workstation recipe's detail (used by FD's linked-recipe jumps
    // into an addon station). singleType/singleType book: backing out of the detail reaches the type's list,
    // and fully exiting runs onExit so the caller (FD recipe view) can hand the player back.
    public static void openRecipe(Player player, RecipeType type, String recipeId, Runnable onExit) {
        RecipeBookListener.ensureRegistered();
        RecipeBookGui gui = new RecipeBookGui();
        gui.viewer = player;
        gui.singleType = true;
        gui.directDetail = true;
        gui.onExit = onExit;
        gui.drawDetail(type, recipeId, player);
        player.openInventory(gui.inventory);
    }

    // Roles whose slots are filled dynamically/conditionally by the GUI (not static chrome).
    private static final Set<String> DYNAMIC_ROLES = Set.of(
            "category", "recipe", "ingredient", "result", "prev_page", "next_page", "fill", "filter", "switch",
            "progress", "fluid", "fluid_icon", "fluid_level", "output_fluid", "ferment_info", "temperature",
            "return");

    private interface RenderSpec {
        int size();
        Component title();
        void renderChrome(Inventory inventory);
        List<Integer> slotsByType(String role);
        int firstSlotByType(String role);
        ItemStack button(String role);
    }

    private record ViewConfigSpec(RecipeBookGuiConfig.ViewConfig cfg, Component title) implements RenderSpec {
        public int size() {
            return cfg.size();
        }
        public void renderChrome(Inventory inventory) {
            cfg.renderChrome(inventory);
        }
        public List<Integer> slotsByType(String role) {
            return cfg.slotsByType(role);
        }
        public int firstSlotByType(String role) {
            return cfg.firstSlotByType(role);
        }
        public ItemStack button(String role) {
            GuiConfig.GuiItem item = cfg.item(role);
            return item == null ? null : normalizeGuiItem(item.createItem());
        }
    }

    private record LayoutSpec(RecipeBookLayout layout) implements RenderSpec {
        public int size() {
            return layout.size();
        }
        public Component title() {
            return layout.title();
        }
        public void renderChrome(Inventory inventory) {
            List<String> rows = layout.layout();
            Map<Character, String> legend = layout.legend();
            Map<String, ItemStack> decorations = layout.decorations();
            for (int row = 0; row < rows.size(); row++) {
                String line = rows.get(row);
                for (int col = 0; col < line.length() && col < 9; col++) {
                    int slot = row * 9 + col;
                    if (slot >= inventory.getSize()) {
                        continue;
                    }
                    String role = legend.get(line.charAt(col));
                    if (role == null || DYNAMIC_ROLES.contains(role)) {
                        continue;
                    }
                    ItemStack item = decorations.get(role);
                    if (item != null) {
                        inventory.setItem(slot, normalizeGuiItem(item));
                    }
                }
            }
        }
        public List<Integer> slotsByType(String role) {
            return layout.slotsByType(role);
        }
        public int firstSlotByType(String role) {
            return layout.firstSlotByType(role);
        }
        public ItemStack button(String role) {
            ItemStack item = layout.decorations().get(role);
            return item == null ? null : normalizeGuiItem(item);
        }
    }

    private RenderSpec listSpec(RecipeType target) {
        RecipeBookLayout layout = target.listLayout();
        return layout != null ? new LayoutSpec(layout) : new ViewConfigSpec(config().list(), target.title());
    }

    private RenderSpec detailSpec(RecipeType target) {
        RecipeBookLayout layout = target.detailLayout();
        return layout != null ? new LayoutSpec(layout) : new ViewConfigSpec(config().detail(), target.title());
    }

    public static void clearConfigCache() {
        cachedConfig = null;
        progressItem = null;
    }

    private static RecipeBookGuiConfig config() {
        RecipeBookGuiConfig cached = cachedConfig;
        if (cached != null) {
            return cached;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        ConfigurationSection section = plugin != null ? plugin.getRecipeBookGuiSection() : null;
        cached = RecipeBookGuiConfig.fromConfig(section);
        cachedConfig = cached;
        return cached;
    }

    View view() {
        return view;
    }

    RecipeType type() {
        return type;
    }

    int page() {
        return page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    void drawMenu() {
        view = View.MENU;
        type = null;
        recipeId = null;
        progressSlots = List.of();
        RecipeBookGuiConfig.ViewConfig cfg = config().menu();
        inventory = Bukkit.createInventory(this, cfg.size(), Text.title(cfg.title()));
        cfg.renderChrome(inventory);
        List<Integer> categorySlots = cfg.slotsByType("category");
        List<RecipeType> types = FarmersDelightApi.get().recipeTypes();
        for (int i = 0; i < categorySlots.size() && i < types.size(); i++) {
            RecipeType registered = types.get(i);
            ItemStack icon = clone(registered.icon(), Material.BOOK);
            rename(icon, registered.title());
            inventory.setItem(categorySlots.get(i), icon);
        }
    }

    private List<ViewableRecipe> visibleRecipes(RecipeType target) {
        List<ViewableRecipe> all = target.recipes();
        RecipeDiscoveryManager discovery = discovery();
        boolean hideLocked = discovery != null && discovery.isEnabled() && discovery.hidesLocked() && viewer != null;
        if ((!filterCraftable || viewer == null) && !hideLocked) {
            return all;
        }
        List<ViewableRecipe> shown = new ArrayList<>();
        for (ViewableRecipe recipe : all) {
            if (filterCraftable && viewer != null && !recipe.craftableBy(viewer)) {
                continue;
            }
            if (hideLocked && isLocked(discovery, target, recipe)) {
                continue;
            }
            shown.add(recipe);
        }
        return shown;
    }

    private RecipeDiscoveryManager discovery() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null ? null : plugin.getRecipeDiscoveryManager();
    }

    private boolean isLocked(RecipeDiscoveryManager discovery, RecipeType target, ViewableRecipe recipe) {
        return discovery != null && discovery.isEnabled() && viewer != null
                && !discovery.isUnlocked(viewer.getUniqueId(), target.id(), recipe.id());
    }

    // Substitutes the {page}/{total} placeholders in a list title with the current page and page count, so an
    // addon's list title can show a page counter like FarmersDelight's own recipe list. A title without the
    // placeholders is returned unchanged.
    private static Component withPageInfo(Component title, int page, int total) {
        return title
                .replaceText(b -> b.matchLiteral("{page}").replacement(Integer.toString(page)))
                .replaceText(b -> b.matchLiteral("{total}").replacement(Integer.toString(total)));
    }

    void drawList(RecipeType target, int targetPage) {
        view = View.LIST;
        type = target;
        recipeId = null;
        progressSlots = List.of();
        RenderSpec spec = listSpec(target);
        List<Integer> recipeSlots = spec.slotsByType("recipe");
        int pageSize = Math.max(1, recipeSlots.size());
        List<ViewableRecipe> recipes = visibleRecipes(target);
        int pages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(targetPage, pages - 1));
        inventory = Bukkit.createInventory(this, spec.size(), withPageInfo(spec.title(), page + 1, pages));
        spec.renderChrome(inventory);
        int start = page * pageSize;
        RecipeDiscoveryManager discovery = discovery();
        for (int i = 0; i < recipeSlots.size() && start + i < recipes.size(); i++) {
            ViewableRecipe recipe = recipes.get(start + i);
            ItemStack icon = isLocked(discovery, target, recipe)
                    ? discovery.lockedPlaceholder(viewer)
                    : buildListDisplayItem(recipe, viewer);
            inventory.setItem(recipeSlots.get(i), normalizeGuiItem(icon));
        }
        if (page > 0) {
            placeButton(spec, "prev_page");
        }
        if (page < pages - 1) {
            placeButton(spec, "next_page");
        }
        // Optional "switch" button (e.g. toggle keg fermenting <-> pouring recipes), shown when this type
        // declares a sibling via switchTarget().
        int switchSlot = spec.firstSlotByType("switch");
        if (switchSlot >= 0 && target.switchTarget() != null) {
            placeButton(spec, "switch");
        }
        // Optional "craftable only" toggle: shows the 'filter_active' item when on, else 'filter'.
        int filterSlot = spec.firstSlotByType("filter");
        if (filterSlot >= 0) {
            ItemStack button = spec.button(filterCraftable ? "filter_active" : "filter");
            if (button == null) {
                button = spec.button("filter");
            }
            if (button != null) {
                inventory.setItem(filterSlot, button);
            }
        }
    }

    void drawDetail(RecipeType target, String id, Player viewer) {
        view = View.DETAIL;
        type = target;
        recipeId = id;
        RenderSpec spec = detailSpec(target);
        ViewableRecipe recipe = target.recipe(id);
        // Per-recipe title override falls back to the static layout title.
        Component detailTitle = recipe != null ? recipe.detailTitle() : null;
        inventory = Bukkit.createInventory(this, spec.size(), detailTitle != null ? detailTitle : spec.title());
        spec.renderChrome(inventory);
        if (recipe != null) {
            List<Integer> ingredientSlots = spec.slotsByType("ingredient");
            List<ItemStack> inputs = recipe.inputs();
            for (int i = 0; i < ingredientSlots.size() && i < inputs.size(); i++) {
                inventory.setItem(ingredientSlots.get(i), normalizeGuiItem(clone(inputs.get(i), Material.AIR)));
            }
            // Custom roles supplied by the recipe (e.g. fluid / return / temperature for the keg), placed
            // into the type's own detail layout slots for that role.
            for (Map.Entry<String, List<ItemStack>> entry : recipe.displaySlots().entrySet()) {
                List<Integer> slots = spec.slotsByType(entry.getKey());
                List<ItemStack> items = entry.getValue();
                for (int i = 0; i < slots.size() && i < items.size(); i++) {
                    if (items.get(i) != null && !items.get(i).getType().isAir()) {
                        inventory.setItem(slots.get(i), normalizeGuiItem(items.get(i)));
                    }
                }
            }
            int resultSlot = spec.firstSlotByType("result");
            if (resultSlot >= 0) {
                ItemStack result = clone(recipe.result(), Material.PAPER);
                List<Component> lore = new ArrayList<>(recipe.detailInfoLines(viewer));
                if (!lore.isEmpty()) {
                    applyLore(result, lore);
                }
                inventory.setItem(resultSlot, normalizeGuiItem(result));
            }
            if (filler != null) {
                placeButton(spec, "fill");
            }
        }
        // Animated progress bar: the client-side item is placed once in every "progress" slot.
        progressSlots = spec.slotsByType("progress");
        if (!progressSlots.isEmpty()) {
            renderProgress();
        }
    }

    private void renderProgress() {
        if (inventory == null) {
            return;
        }
        ItemStack frame = progressFrameItem();
        for (int slot : progressSlots) {
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, frame.clone());
            }
        }
    }

    void onClose(Inventory closed) {
        // Kept for the listener contract; animated textures need no per-viewer task to stop.
    }

    private static ItemStack progressFrameItem() {
        if (progressItem != null) {
            return progressItem.clone();
        }
        ItemStack item = ItemUtils.createItem("farmersdelight:animated");
        boolean resolved = item != null && !item.getType().isAir();
        if (!resolved) {
            // CraftEngine may be midway through a reload; keep the fallback transient so the next detail open retries.
            item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            meta.lore(List.of());
            meta.setHideTooltip(true);
            item.setItemMeta(meta);
        }
        if (resolved) {
            progressItem = item;
        }
        return item;
    }

    private void placeButton(RenderSpec spec, String role) {
        int slot = spec.firstSlotByType(role);
        ItemStack item = spec.button(role);
        if (slot >= 0 && item != null) {
            inventory.setItem(slot, normalizeGuiItem(item));
        }
    }

    private ViewState snapshot() {
        return new ViewState(view, type, recipeId, page);
    }

    private void restore(ViewState state, Player player) {
        switch (state.view()) {
            case MENU -> drawMenu();
            case LIST -> drawList(state.type(), state.page());
            case DETAIL -> drawDetail(state.type(), state.recipeId(), player);
        }
    }

    private void back(Player player) {
        if (!history.isEmpty()) {
            restore(history.pop(), player);
            player.openInventory(inventory);
            return;
        }
        switch (view) {
            case MENU -> exitOrClose(player);
            case LIST -> {
                if (singleType) {
                    // Opened from a station (e.g. a keg): let its filler reopen that GUI; else exit or close.
                    if (filler == null || !filler.onBack(player)) {
                        exitOrClose(player);
                    }
                } else {
                    drawMenu();
                    player.openInventory(inventory);
                }
            }
            case DETAIL -> {
                if (directDetail) {
                    exitOrClose(player);
                } else {
                    drawList(type, page);
                    player.openInventory(inventory);
                }
            }
        }
    }

    private void exitOrClose(Player player) {
        if (onExit != null) {
            onExit.run();
        } else {
            player.closeInventory();
        }
    }

    private void tryJump(Player player, RenderSpec cfg, int rawSlot) {
        // Jump icons come from the recipe layout; the config switch decides whether they act.
        if (!FarmersDelightPlugin.getInstance().getConfigBoolean(true, "recipe-navigation.jumps")) {
            return;
        }
        ViewableRecipe recipe = type.recipe(recipeId);
        if (recipe == null) {
            return;
        }
        for (Map.Entry<String, JumpTarget> entry : recipe.jumpTargets().entrySet()) {
            JumpTarget target = entry.getValue();
            if (target == null || !cfg.slotsByType(entry.getKey()).contains(rawSlot)) {
                continue;
            }
            RecipeType targetType = FarmersDelightApi.get().recipeType(target.typeId());
            if (targetType == null || targetType.recipe(target.recipeId()) == null) {
                return; // target unregistered/removed -> ignore the click
            }
            history.push(snapshot());
            drawDetail(targetType, target.recipeId(), player);
            player.openInventory(inventory);
            return;
        }
        if (!cfg.slotsByType("ingredient").contains(rawSlot)) {
            return;
        }
        FarmersDelightApi api = FarmersDelightApi.get();
        ItemStack clicked = inventory.getItem(rawSlot);
        for (JumpTarget target : api.findRecipesProducing(clicked)) {
            if (target.typeId().equals(type.id()) && target.recipeId().equals(recipeId)) {
                continue;
            }
            RecipeType targetType = api.recipeType(target.typeId());
            if (targetType != null && targetType.recipe(target.recipeId()) != null) {
                history.push(snapshot());
                drawDetail(targetType, target.recipeId(), player);
                player.openInventory(inventory);
                return;
            }
            ViewState returnState = snapshot();
            if (RecipeViewGui.openLinkedRecipe(player, target, () -> {
                viewer = player;
                restore(returnState, player);
                player.openInventory(inventory);
            })) {
                return;
            }
        }
    }

    private void applyFillOutcome(Player player, FillOutcome outcome, RenderSpec cfg) {
        int fillSlot = cfg.firstSlotByType("fill");
        if (fillSlot < 0) {
            return;
        }
        // A book that configures a button per outcome shows that button; a book that does not keeps the
        // original behaviour: the status appended as a lore line to the fill button, not a chat message.
        String variantKey = fillVariantKey(outcome);
        ItemStack variant = variantKey == null ? null : cfg.button(variantKey);
        if (variant != null) {
            inventory.setItem(fillSlot, variant);
            return;
        }
        String statusKey = fillStatusKey(outcome);
        if (statusKey == null) {
            return;
        }
        ItemStack fillItem = cfg.button("fill");
        if (fillItem == null) {
            return;
        }
        ItemMeta meta = fillItem.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Text.deserialize(I18n.get(statusKey, player))
                    .colorIfAbsent(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            fillItem.setItemMeta(meta);
        }
        inventory.setItem(fillSlot, fillItem);
    }

    /**
     * The gui.yml button that reports this outcome, or null when the outcome has no button of its own.
     * A book that does not configure it falls back to the fill button plus a status lore line.
     */
    static String fillVariantKey(FillOutcome outcome) {
        return switch (outcome) {
            case FILLED -> "fill-success";
            case MISSING_INGREDIENTS -> "fill-missing";
            case INVENTORY_FULL -> "fill-inventory-full";
            case NOTHING -> null;
        };
    }

    /** The status lore key used when the outcome's own button is not configured, or null when none applies. */
    static String fillStatusKey(FillOutcome outcome) {
        return switch (outcome) {
            case MISSING_INGREDIENTS -> "gui.recipe.missing_ingredients";
            case INVENTORY_FULL -> "gui.recipe.inventory_full";
            // FILLED reopens the station via the filler; NOTHING has no reason to show.
            case FILLED, NOTHING -> null;
        };
    }

    void handleClick(Player player, int rawSlot, boolean shiftClick) {
        this.viewer = player;
        RecipeBookGuiConfig config = config();
        switch (view) {
            case MENU -> {
                RecipeBookGuiConfig.ViewConfig cfg = config.menu();
                if (rawSlot == cfg.firstSlotByType("back")) {
                    back(player);
                    return;
                }
                int index = cfg.slotsByType("category").indexOf(rawSlot);
                if (index < 0) {
                    return;
                }
                List<RecipeType> types = FarmersDelightApi.get().recipeTypes();
                if (index < types.size()) {
                    history.push(snapshot());
                    drawList(types.get(index), 0);
                    player.openInventory(inventory);
                }
            }
            case LIST -> {
                RenderSpec cfg = listSpec(type);
                if (rawSlot == cfg.firstSlotByType("back")) {
                    back(player);
                } else if (rawSlot == cfg.firstSlotByType("prev_page")) {
                    drawList(type, page - 1);
                    player.openInventory(inventory);
                } else if (rawSlot == cfg.firstSlotByType("next_page")) {
                    drawList(type, page + 1);
                    player.openInventory(inventory);
                } else if (rawSlot == cfg.firstSlotByType("filter")) {
                    filterCraftable = !filterCraftable;
                    drawList(type, 0);
                    player.openInventory(inventory);
                } else if (rawSlot == cfg.firstSlotByType("switch") && type.switchTarget() != null) {
                    RecipeType sibling = FarmersDelightApi.get().recipeType(type.switchTarget());
                    if (sibling != null) {
                        drawList(sibling, 0);
                        player.openInventory(inventory);
                    }
                } else {
                    List<Integer> recipeSlots = cfg.slotsByType("recipe");
                    int slotIndex = recipeSlots.indexOf(rawSlot);
                    if (slotIndex >= 0) {
                        int index = page * Math.max(1, recipeSlots.size()) + slotIndex;
                        List<ViewableRecipe> recipes = visibleRecipes(type);
                        if (index < recipes.size()) {
                            ViewableRecipe clicked = recipes.get(index);
                            if (isLocked(discovery(), type, clicked)) {
                                // Resolve on the client from the pack, like the recipe viewer's own message.
                                player.sendMessage(Component.translatable("recipe-discovery.locked-click")
                                        .color(NamedTextColor.RED));
                            } else {
                                history.push(snapshot());
                                drawDetail(type, clicked.id(), player);
                                player.openInventory(inventory);
                            }
                        }
                    }
                }
            }
            case DETAIL -> {
                RenderSpec cfg = detailSpec(type);
                if (rawSlot == cfg.firstSlotByType("back")) {
                    back(player);
                } else if (rawSlot == cfg.firstSlotByType("fill") && filler != null) {
                    ViewableRecipe recipe = type.recipe(recipeId);
                    if (recipe != null) {
                        applyFillOutcome(player, filler.fillDetailed(player, recipe, shiftClick), cfg);
                    }
                } else {
                    tryJump(player, cfg, rawSlot);
                }
            }
        }
    }

    // Builds an FD-style recipe-list item: the recipe result/icon renamed and given a lore that previews the
    // materials, so the addon book's list reads like FarmersDelight's own recipe list rather than bare icons.
    // Extra per-recipe lines (e.g. keg temperature) are appended after the materials block.
    private ItemStack buildListDisplayItem(ViewableRecipe recipe, Player viewer) {
        ItemStack item = clone(recipe.icon(), Material.PAPER);
        // Resolve the recipe's real localized item name instead of whatever raw text the underlying
        // fluid/container carries (e.g. an unresolved CraftEngine placeholder such as "[item]"), so the
        // addon book's list names line up with FarmersDelight's own recipe list.
        rename(item, ItemUtils.getDisplayComponent(recipe.result(), viewer)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> lore = new ArrayList<>();
        // When the result keeps its own original lore, drop a blank line above the recipe block to separate it.
        ItemMeta meta = item.getItemMeta();
        boolean hasOriginalLore = meta != null && meta.lore() != null && !meta.lore().isEmpty();
        List<ItemStack> inputs = recipe.listInputs();
        if (!inputs.isEmpty()) {
            if (hasOriginalLore) {
                lore.add(Component.empty());
            }
            lore.add(Component.translatable("gui.recipe.ingredients_label").color(NamedTextColor.GRAY));
            for (ItemStack input : inputs) {
                if (input == null || input.getType().isAir()) {
                    continue;
                }
                lore.add(Component.text("- ").color(NamedTextColor.DARK_GRAY)
                        .append(ItemUtils.getDisplayComponent(input, viewer).colorIfAbsent(NamedTextColor.WHITE)));
            }
            lore.add(Component.empty());
        }
        lore.addAll(recipe.infoLines(viewer));
        if (!lore.isEmpty()) {
            applyLore(item, lore);
        }
        return normalizeGuiItem(item);
    }

    static ItemStack clone(ItemStack source, Material fallback) {
        if (source != null && !source.getType().isAir()) {
            return source.clone();
        }
        return new ItemStack(fallback);
    }

    public static void rename(ItemStack item, Component name) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && name != null) {
            meta.displayName(name.colorIfAbsent(NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
    }

    private static ItemStack normalizeGuiItem(ItemStack source) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (meta.displayName() != null) {
            meta.displayName(meta.displayName().colorIfAbsent(NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (meta.lore() != null) {
            meta.lore(meta.lore().stream()
                    .map(line -> line.colorIfAbsent(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    // Appends the supplied lore lines to the item's existing lore instead of replacing it, so an item's own
    // description (original lore) is preserved when recipe-derived lines are added.
    public static void applyLore(ItemStack item, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> merged = new ArrayList<>();
            if (meta.lore() != null) {
                merged.addAll(meta.lore());
            }
            for (Component line : lore) {
                merged.add(line.colorIfAbsent(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(merged);
            item.setItemMeta(meta);
        }
    }
}
