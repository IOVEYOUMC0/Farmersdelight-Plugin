package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.AbstractInventoryGui;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CookingPotEditorGui extends AbstractInventoryGui implements EditorGui {

    private static final List<String> CATEGORY_PRESETS = List.of("meals", "soups", "drinks", "misc");
    private static final int MAX_COOK_TIME = 6000;
    private static final int MIN_COOK_TIME = 20;
    private static final int HISTORY_LIMIT = 50;

    // Every field the editor lets the admin mutate. Each click on one of these snapshots the state
    // before the change so the undo button can step back; a snapshot is a cheap shallow copy because the
    // ingredient records are immutable (Item/Tag/Choice) and only the two ItemStacks need cloning.
    private static final Set<String> MUTABLE_TYPES = Set.of(
            "ingredient", "container", "result", "result-count",
            "cook-time", "experience", "priority", "category");

    private final String recipeId;
    private final String customGroupId;
    private final boolean editingExisting;
    private final RecipeViewGuiConfig.BaseConfig config;

    private final List<Integer> ingredientSlots;
    private final Map<Integer, String> slotTypeByIndex = new HashMap<>();

    private final RecipeIngredient[] ingredients;
    private ItemStack container;
    private ItemStack result;
    private int resultCount = 1;
    private int cookTime = 200;
    private float experience = 0.0f;
    private int priority = 0;
    private String category = "meals";

    private final ArrayDeque<Snapshot> undoStack = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redoStack = new ArrayDeque<>();

    public CookingPotEditorGui(FarmersDelightPlugin plugin, Player player, String recipeId,
                               String customGroupId, CookingPotRecipe existing,
                               RecipeViewGuiConfig.BaseConfig config) {
        super(plugin, player);
        this.recipeId = recipeId;
        this.customGroupId = customGroupId;
        this.editingExisting = existing != null;
        this.config = config;
        this.ingredientSlots = config.getSlotsByType("ingredient");
        this.ingredients = new RecipeIngredient[Math.max(1, ingredientSlots.size())];

        for (int i = 0; i < config.getSize(); i++) {
            String type = config.getSlotType(i);
            if (type != null) {
                slotTypeByIndex.put(i, type);
            }
        }

        this.inventory = plugin.getServer().createInventory(this, config.getSize(), EditorGui.coloredComponent(config.getTitle()));
        if (existing != null) {
            loadFrom(existing);
        }
    }

    private void loadFrom(CookingPotRecipe recipe) {
        List<RecipeIngredient> recipeIngredients = recipe.getIngredients();
        for (int i = 0; i < ingredients.length && i < recipeIngredients.size(); i++) {
            ingredients[i] = recipeIngredients.get(i);
        }
        if (recipeIngredients.size() > ingredients.length) {
            player.sendMessage(Component.translatable("gui.editor.feedback.too_many_ingredients",
                    Component.text(recipeIngredients.size()),
                    Component.text(ingredients.length))
                    .color(NamedTextColor.YELLOW));
        }
        this.container = recipe.getContainer() == null ? null : recipe.getContainer().clone();
        this.result = recipe.getResult() == null ? null : recipe.getResult().clone();
        this.resultCount = result == null ? 1 : Math.max(1, result.getAmount());
        this.cookTime = recipe.getCookTime();
        this.experience = recipe.getExperience();
        this.priority = recipe.getPriority();
        this.category = recipe.getCategory() == null || recipe.getCategory().isBlank() ? "meals" : recipe.getCategory();
    }

    public void open() {
        doOpen(this::render);
    }

    @Override
    protected AbstractInventoryGui findExistingGui(UUID playerId) {
        return null; // The editor replaces the current view directly and needs no tracking.
    }

    @Override
    protected void putActiveGui(UUID playerId, AbstractInventoryGui gui) {
        // RecipeEditorListener owns the editor lifecycle.
    }

    @Override
    protected void removeFromActiveGuis(UUID playerId) {
        // RecipeEditorListener owns the editor lifecycle.
    }

    @Override
    protected void ensureListenerRegistered() {
        RecipeEditorListener.ensureRegistered(plugin);
    }

    private void render() {
        for (int i = 0; i < config.getSize(); i++) {
            inventory.setItem(i, renderSlot(i, slotTypeByIndex.get(i)));
        }
    }

    private ItemStack renderSlot(int slot, String type) {
        if (type == null) {
            return configItem("background", noPlaceholders());
        }
        switch (type) {
            case "ingredient": {
                int idx = ingredientSlots.indexOf(slot);
                RecipeIngredient ingredient = idx >= 0 && idx < ingredients.length ? ingredients[idx] : null;
                return ingredient == null ? configItem("ingredient", noPlaceholders()) : displayForIngredient(ingredient);
            }
            case "container":
                return container == null || container.getType().isAir()
                        ? configItem("container", noPlaceholders()) : displayCopy(container, 1);
            case "result":
                return result == null || result.getType().isAir()
                        ? configItem("result", noPlaceholders()) : displayCopy(result, resultCount);
            case "result-count":
                return configItem("result-count", Map.of("count", String.valueOf(resultCount)));
            case "cook-time":
                return configItem("cook-time", Map.of(
                        "cook_time", String.valueOf(cookTime),
                        "seconds", formatSeconds(cookTime)));
            case "experience":
                return configItem("experience", Map.of("experience", String.valueOf(experience)));
            case "priority":
                return configItem("priority", Map.of("priority", String.valueOf(priority)));
            case "category":
                return configItem("category", Map.of("category", category));
            case "info":
                return configItem("info", Map.of(
                        "recipe_id", recipeId,
                        "type", customGroupId == null || customGroupId.isBlank() ? "default" : customGroupId));
            case "save":
                return configItem("save", noPlaceholders());
            case "cancel":
                return configItem("cancel", noPlaceholders());
            case "delete":
                return editingExisting ? configItem("delete", noPlaceholders()) : configItem("background", noPlaceholders());
            case "undo":
                // Buttons only appear when there is a history to step through.
                return undoStack.isEmpty() ? configItem("background", noPlaceholders()) : configItem("undo", noPlaceholders());
            case "redo":
                return redoStack.isEmpty() ? configItem("background", noPlaceholders()) : configItem("redo", noPlaceholders());
            default:
                return configItem("background", noPlaceholders());
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (closed) {
            return;
        }
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < config.getSize();

        if (!top) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                // Picked-up copies never leave the inventory, so dropping the cursor cancels the pickup
                // and lets the player put the item back without touching the GUI.
                player.setItemOnCursor(null);
            } else {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && !clicked.getType().isAir()) {
                    player.setItemOnCursor(cleanCopy(clicked));
                }
            }
            return;
        }

        handleTopClick(raw, event.getClick(), event.getCursor());
    }

    private void handleTopClick(int slot, ClickType click, ItemStack cursor) {
        boolean hasCursorItem = cursor != null && !cursor.getType().isAir();
        String type = slotTypeByIndex.get(slot);
        if (type == null) {
            return;
        }

        // Snapshot the pre-edit state so the undo button can step back; stepping also resets the redo
        // branch, since a fresh edit invalidates anything that was reverted.
        if (MUTABLE_TYPES.contains(type)) {
            pushHistory();
        }

        switch (type) {
            case "ingredient": {
                int idx = ingredientSlots.indexOf(slot);
                if (idx < 0 || idx >= ingredients.length) {
                    return;
                }
                if (click.isShiftClick()) {
                    openChoiceBuilder(idx);
                    return;
                }
                RecipeIngredient existing = ingredients[idx];
                if (hasCursorItem) {
                    if (click.isRightClick()) {
                        ItemStack source = cleanCopy(cursor);
                        clearCursor();
                        openTagPicker(idx, source);
                        return;
                    }
                    ingredients[idx] = appendOption(existing, RecipeIngredient.Item.fromStack(cursor));
                    clearCursor();
                } else if (click.isRightClick()) {
                    if (existing instanceof RecipeIngredient.Choice) {
                        openChoiceBuilder(idx);
                        return;
                    }
                    ingredients[idx] = null;
                } else if (existing instanceof RecipeIngredient.Choice) {
                    openChoiceBuilder(idx);
                    return;
                } else if (existing instanceof RecipeIngredient.Item item) {
                    ItemStack pickedUp = item.createStack();
                    player.setItemOnCursor(pickedUp != null && !pickedUp.getType().isAir() ? cleanCopy(pickedUp) : null);
                    ingredients[idx] = null;
                }
                render();
                return;
            }
            case "container":
                if (hasCursorItem) {
                    container = cleanCopy(cursor);
                    clearCursor();
                } else {
                    if (container != null) {
                        player.setItemOnCursor(cleanCopy(container));
                    }
                    container = null;
                }
                render();
                return;
            case "result":
                if (hasCursorItem) {
                    result = cleanCopy(cursor);
                    result.setAmount(resultCount);
                    clearCursor();
                } else {
                    if (result != null) {
                        player.setItemOnCursor(cleanCopy(result));
                    }
                    result = null;
                }
                render();
                return;
            case "result-count":
                // Shift-click adjusts by tens, plain click by one.
                resultCount = clamp(resultCount + (click.isRightClick()
                        ? (click.isShiftClick() ? -10 : -1) : (click.isShiftClick() ? 10 : 1)), 1, 64);
                if (result != null) {
                    result.setAmount(resultCount);
                }
                render();
                return;
            case "cook-time": {
                int step = click.isShiftClick() ? 100 : 20;
                cookTime = clamp(cookTime + (click.isRightClick() ? -step : step), MIN_COOK_TIME, MAX_COOK_TIME);
                render();
                return;
            }
            case "experience": {
                float step = click.isShiftClick() ? 1.0f : 0.1f;
                experience = Math.max(0.0f, round1(experience + (click.isRightClick() ? -step : step)));
                render();
                return;
            }
            case "priority":
                priority = clamp(priority + (click.isRightClick()
                        ? (click.isShiftClick() ? -10 : -1) : (click.isShiftClick() ? 10 : 1)), -100, 100);
                render();
                return;
            case "category": {
                int idx = CATEGORY_PRESETS.indexOf(category);
                category = CATEGORY_PRESETS.get((idx + 1) % CATEGORY_PRESETS.size());
                render();
                return;
            }
            case "save":
                save();
                return;
            case "cancel":
                closeEditor();
                return;
            case "delete":
                if (editingExisting) {
                    delete();
                }
                return;
            case "undo":
                if (!undoStack.isEmpty()) {
                    undo();
                }
                return;
            case "redo":
                if (!redoStack.isEmpty()) {
                    redo();
                }
                return;
            default:
        }
    }

    /** A frozen copy of every editable field. Ingredient records are immutable, so the array is copied
     * shallowly; the two ItemStacks are cloned since they carry mutable stack data. */
    private record Snapshot(RecipeIngredient[] ingredients, ItemStack container, ItemStack result,
                            int resultCount, int cookTime, float experience, int priority, String category) {
    }

    private Snapshot capture() {
        RecipeIngredient[] ingredientsCopy = new RecipeIngredient[ingredients.length];
        System.arraycopy(ingredients, 0, ingredientsCopy, 0, ingredients.length);
        return new Snapshot(ingredientsCopy,
                container == null ? null : container.clone(),
                result == null ? null : result.clone(),
                resultCount, cookTime, experience, priority, category);
    }

    private void apply(Snapshot snapshot) {
        System.arraycopy(snapshot.ingredients, 0, ingredients, 0, ingredients.length);
        container = snapshot.container == null ? null : snapshot.container.clone();
        result = snapshot.result == null ? null : snapshot.result.clone();
        resultCount = snapshot.resultCount;
        cookTime = snapshot.cookTime;
        experience = snapshot.experience;
        priority = snapshot.priority;
        category = snapshot.category;
        if (result != null) {
            result.setAmount(resultCount);
        }
        render();
    }

    private void pushHistory() {
        undoStack.push(capture());
        if (undoStack.size() > HISTORY_LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(capture());
        apply(undoStack.pop());
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(capture());
        apply(redoStack.pop());
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        super.close();
        clearCursor();
        plugin.scheduler().runLaterForEntity(player, this::clearCursor, 1L);
    }

    private void save() {
        List<RecipeIngredient> ingredientList = new ArrayList<>();
        for (RecipeIngredient ingredient : ingredients) {
            if (ingredient != null) {
                ingredientList.add(ingredient);
            }
        }
        if (ingredientList.isEmpty()) {
            player.sendMessage(Component.translatable("gui.editor.feedback.no_ingredients")
                    .color(NamedTextColor.RED));
            return;
        }
        if (result == null || result.getType().isAir()) {
            player.sendMessage(Component.translatable("gui.editor.feedback.no_result")
                    .color(NamedTextColor.RED));
            return;
        }
        ItemStack savedResult = result.clone();
        savedResult.setAmount(resultCount);

        // Auto-add the container the result declares (a soup's bowl, a drink's bottle) so a recipe written in
        // the editor is saved exactly like a hand-written one; the file's "container: none" removes it again.
        ItemStack savedContainer = container == null || container.getType().isAir() ? null : container;
        if (savedContainer == null) {
            ItemStack inferred = ItemUtils.craftingRemainderOf(savedResult, recipeId);
            if (inferred != null && !inferred.getType().isAir()) {
                savedContainer = inferred;
            }
        }

        CookingPotRecipe recipe = new CookingPotRecipe(
                recipeId, ingredientList, savedContainer, savedContainer != null, savedResult,
                experience, cookTime, category, priority);

        boolean accepted = RecipeEditorView.store().saveCookingPotRecipeAsync(recipe, customGroupId, player,
                saved -> {
                    if (saved) {
                        player.sendMessage(Component.translatable("gui.editor.feedback.saved",
                                Component.text(recipeId).color(NamedTextColor.WHITE))
                                .color(NamedTextColor.GREEN));
                        closeEditor();
                    } else {
                        player.sendMessage(Component.translatable("gui.editor.feedback.save_failed")
                                .color(NamedTextColor.RED));
                    }
                });
        if (!accepted) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.save_busy", player));
        }
    }

    private void delete() {
        RecipeViewGuiConfig.BaseConfig confirmConfig = RecipeEditorView.guiConfig().getConfirmDeleteConfig();
        if (confirmConfig == null) {
            performDelete();
            return;
        }
        closed = true;
        new ConfirmGui(plugin, player, confirmConfig, Map.of("recipe_id", recipeId),
                this::performDelete, this::reopen).open();
    }

    private void performDelete() {
        boolean accepted = RecipeEditorView.store().deleteCookingPotRecipeAsync(recipeId, customGroupId, player,
                deleted -> {
                    if (deleted) {
                        player.sendMessage(Component.translatable("gui.editor.feedback.deleted",
                                Component.text(recipeId).color(NamedTextColor.WHITE))
                                .color(NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.translatable("gui.editor.feedback.delete_failed")
                                .color(NamedTextColor.RED));
                    }
                    player.closeInventory();
                });
        if (!accepted) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.save_busy", player));
        }
    }

    private RecipeIngredient appendOption(RecipeIngredient current, RecipeIngredient.Item added) {
        if (current == null) {
            return added;
        }
        List<RecipeIngredient> options = new ArrayList<>();
        if (current instanceof RecipeIngredient.Choice choice) {
            options.addAll(choice.options());
        } else {
            options.add(current);
        }
        for (RecipeIngredient option : options) {
            if (option.equals(added)) {
                return current;
            }
        }
        options.add(added);
        return options.size() == 1 ? options.getFirst() : new RecipeIngredient.Choice(options);
    }

    private void openTagPicker(int idx, ItemStack source) {
        RecipeViewGuiConfig.BaseConfig pickerConfig = RecipeEditorView.guiConfig().getTagPickerConfig();
        if (pickerConfig == null) {
            player.sendMessage(Component.translatable("gui.editor.feedback.not_configured")
                    .color(NamedTextColor.RED));
            return;
        }
        List<String> tags = ItemUtils.getAllItemTagIds(source);
        if (tags.isEmpty()) {
            plugin.getLogger().warning("Recipe editor: item '" + ItemUtils.resolveItemId(source)
                    + "' belongs to no tag; tag selection skipped.");
            player.sendMessage(Component.translatable("gui.editor.feedback.no_tags")
                    .color(NamedTextColor.RED));
            return;
        }
        closed = true;
        new TagPickerGui(plugin, player, pickerConfig, source, tags,
                ingredient -> {
                    ingredients[idx] = ingredient;
                    reopen();
                },
                this::reopen).open();
    }

    private void openChoiceBuilder(int idx) {
        RecipeViewGuiConfig.BaseConfig choiceConfig = RecipeEditorView.guiConfig().getChoiceBuilderConfig();
        if (choiceConfig == null) {
            player.sendMessage(Component.translatable("gui.editor.feedback.not_configured")
                    .color(NamedTextColor.RED));
            return;
        }
        closed = true;
        new ChoiceBuilderGui(plugin, player, choiceConfig, idx + 1, ingredients[idx],
                ingredient -> {
                    ingredients[idx] = ingredient;
                    reopen();
                },
                this::reopen).open();
    }

    void reopen() {
        closed = false;
        render();
        player.openInventory(inventory);
    }

    private void closeEditor() {
        super.close();
        clearCursor();
        player.closeInventory();
    }

    private void clearCursor() {
        player.setItemOnCursor(null);
    }

    private ItemStack configItem(String key, Map<String, String> placeholders) {
        GuiConfig.GuiItem item = config.getItem(key);
        if (item == null) {
            item = config.getItem("background");
        }
        if (item == null) {
            return new ItemStack(Material.AIR);
        }
        return item.createItem(placeholders);
    }

    private ItemStack displayForIngredient(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            ItemStack stack = item.createStack();
            return stack != null && !stack.getType().isAir() ? displayCopy(stack, 1)
                    : named(new ItemStack(Material.BARRIER), item.key().toString());
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return named(new ItemStack(Material.NAME_TAG), RecipeSerializer.serializeIngredient(tag));
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            return named(new ItemStack(Material.CHEST), RecipeSerializer.serializeIngredient(choice));
        }
        return new ItemStack(Material.BARRIER);
    }

    private static ItemStack displayCopy(ItemStack source, int amount) {
        ItemStack copy = source.clone();
        copy.setAmount(Math.max(1, amount));
        return copy;
    }

    private static ItemStack cleanCopy(ItemStack source) {
        ItemStack copy = source.clone();
        copy.setAmount(1);
        return copy;
    }

    private static ItemStack named(ItemStack stack, String name) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.name(name));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Map<String, String> noPlaceholders() {
        return Map.of();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float round1(float value) {
        return Math.round(value * 10.0f) / 10.0f;
    }

    private static String formatSeconds(int ticks) {
        double seconds = ticks / 20.0;
        return String.valueOf(Math.round(seconds * 10.0) / 10.0);
    }
}
