package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用于编辑单个 cooking-pot 配方的游戏内编辑器。布局、槽位位置和按钮文本来自
 * gui.yml 中的 recipe-editor-gui（或针对每个自定义锅的 recipe-editor-cooking-pot-guis.<id>）部分；
 * 聊天反馈来自 gui.editor.* 语言键。原料容量会
 * 根据解析后的布局自适应，因此自定义（大型）锅可以暴露更多的原料槽位。
 */
public final class CookingPotEditorGui implements EditorGui {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final List<String> CATEGORY_PRESETS = List.of("meals", "soups", "drinks", "misc");
    private static final int MAX_COOK_TIME = 6000;
    private static final int MIN_COOK_TIME = 20;

    private final FarmersDelightPlugin plugin;
    private final Player player;
    private final String recipeId;
    private final String customGroupId;
    private final boolean editingExisting;
    private final RecipeViewGuiConfig.BaseConfig config;
    private final Inventory inventory;

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

    private boolean closed = false;

    public CookingPotEditorGui(FarmersDelightPlugin plugin, Player player, String recipeId,
                               String customGroupId, CookingPotRecipe existing,
                               RecipeViewGuiConfig.BaseConfig config) {
        this.plugin = plugin;
        this.player = player;
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

        this.inventory = plugin.getServer().createInventory(this, config.getSize(), coloredTitle(config.getTitle()));
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
            player.sendMessage(I18n.getComponent("gui.editor.feedback.too_many_ingredients", player,
                    Map.of("shown", String.valueOf(ingredients.length),
                            "total", String.valueOf(recipeIngredients.size()))));
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
        RecipeEditorListener.ensureRegistered(plugin);
        render();
        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
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
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                player.setItemOnCursor(cleanCopy(clicked));
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
                    ingredients[idx] = appendOption(existing,
                            new RecipeIngredient.Item(Key.of(RecipeSerializer.itemIdString(cursor))));
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
                    ItemStack pickedUp = ItemUtils.createItem(item.key().toString());
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
                resultCount = clamp(resultCount + (click.isRightClick() ? -1 : 1), 1, 64);
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
                priority = clamp(priority + (click.isRightClick() ? -1 : 1), -100, 100);
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
                close();
                return;
            case "delete":
                if (editingExisting) {
                    delete();
                }
                return;
            default:
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        closed = true;
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
            player.sendMessage(I18n.getComponent("gui.editor.feedback.no_ingredients", player));
            return;
        }
        if (result == null || result.getType().isAir()) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.no_result", player));
            return;
        }
        ItemStack savedResult = result.clone();
        savedResult.setAmount(resultCount);

        CookingPotRecipe recipe = new CookingPotRecipe(
                recipeId, ingredientList, container, container != null, savedResult,
                experience, cookTime, category, priority);

        if (plugin.getRecipeEditorStore().saveCookingPotRecipe(recipe, customGroupId)) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.saved", player, Map.of("recipe_id", recipeId)));
            close();
        } else {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.save_failed", player));
        }
    }

    private void delete() {
        RecipeViewGuiConfig.BaseConfig confirmConfig = plugin.getRecipeEditorGuiConfig().getConfirmDeleteConfig();
        if (confirmConfig == null) {
            performDelete();
            return;
        }
        closed = true;
        new ConfirmGui(plugin, player, confirmConfig, Map.of("recipe_id", recipeId),
                this::performDelete, this::reopen).open();
    }

    private void performDelete() {
        if (plugin.getRecipeEditorStore().deleteCookingPotRecipe(recipeId, customGroupId)) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.deleted", player, Map.of("recipe_id", recipeId)));
        } else {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.delete_failed", player));
        }
        player.closeInventory();
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
        String addedKey = RecipeSerializer.serializeIngredient(added);
        for (RecipeIngredient option : options) {
            if (RecipeSerializer.serializeIngredient(option).equals(addedKey)) {
                return current;
            }
        }
        options.add(added);
        return options.size() == 1 ? options.get(0) : new RecipeIngredient.Choice(options);
    }

    private void openTagPicker(int idx, ItemStack source) {
        RecipeViewGuiConfig.BaseConfig pickerConfig = plugin.getRecipeEditorGuiConfig().getTagPickerConfig();
        if (pickerConfig == null) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.advanced_coming", player));
            return;
        }
        List<String> tags = ItemUtils.getAllItemTagIds(source);
        if (tags.isEmpty()) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.no_tags", player));
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
        RecipeViewGuiConfig.BaseConfig choiceConfig = plugin.getRecipeEditorGuiConfig().getChoiceBuilderConfig();
        if (choiceConfig == null) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.advanced_coming", player));
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

    private void close() {
        closed = true;
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
            ItemStack stack = ItemUtils.createItem(item.key().toString());
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

    private static Component coloredTitle(String title) {
        String resolved = title == null ? "" : title;
        if (resolved.contains("<") && resolved.contains(">")) {
            return MINI_MESSAGE.deserialize(resolved);
        }
        return LEGACY.deserialize(resolved.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "§"));
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
