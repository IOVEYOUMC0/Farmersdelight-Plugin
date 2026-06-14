package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用于编辑单条 cutting-board 配方（输入、工具、带权重的产物、优先级）的游戏内编辑器。布局
 * 和按钮文本来自 gui.yml 中的 recipe-cutting-board-editor-gui 部分；聊天栏
 * 反馈来自 gui.editor.* 语言键。
 */
public final class CuttingBoardEditorGui implements EditorGui {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String NONE = "-";

    private final FarmersDelightPlugin plugin;
    private final Player player;
    private final String recipeId;
    private final boolean editingExisting;
    private final RecipeViewGuiConfig.BaseConfig config;
    private final Inventory inventory;

    private final List<Integer> toolSlots;
    private final List<Integer> resultSlots;

    private RecipeIngredient input;
    private final CuttingBoardRecipe.ToolRequirement[] tools;
    private final ItemStack[] resultItems;
    private final double[] resultChances;
    private int priority = 0;
    private String sound = Constants.SOUND_CUTTING_BOARD_KNIFE;
    private int selectedResult = -1;

    private boolean closed = false;

    public CuttingBoardEditorGui(FarmersDelightPlugin plugin, Player player, String recipeId,
                                 CuttingBoardRecipe existing, RecipeViewGuiConfig.BaseConfig config) {
        this.plugin = plugin;
        this.player = player;
        this.recipeId = recipeId;
        this.editingExisting = existing != null;
        this.config = config;

        this.toolSlots = config.getSlotsByType("tool");
        this.resultSlots = config.getSlotsByType("result");

        this.tools = new CuttingBoardRecipe.ToolRequirement[Math.max(1, toolSlots.size())];
        this.resultItems = new ItemStack[Math.max(1, resultSlots.size())];
        this.resultChances = new double[Math.max(1, resultSlots.size())];

        this.inventory = plugin.getServer().createInventory(this, config.getSize(), coloredTitle(config.getTitle()));
        if (existing != null) {
            loadFrom(existing);
        }
    }

    private void loadFrom(CuttingBoardRecipe recipe) {
        this.input = recipe.getInput();
        List<CuttingBoardRecipe.ToolRequirement> recipeTools = recipe.getTools();
        for (int i = 0; i < tools.length && i < recipeTools.size(); i++) {
            tools[i] = recipeTools.get(i);
        }
        List<CuttingBoardRecipe.ResultEntry> recipeResults = recipe.getResults();
        for (int i = 0; i < resultItems.length && i < recipeResults.size(); i++) {
            CuttingBoardRecipe.ResultEntry entry = recipeResults.get(i);
            resultItems[i] = entry.getItem() == null ? null : entry.getItem().clone();
            resultChances[i] = entry.getChance();
        }
        if (recipeResults.size() > resultItems.length) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.too_many_results", player,
                    Map.of("shown", String.valueOf(resultItems.length),
                            "total", String.valueOf(recipeResults.size()))));
        }
        this.priority = recipe.getPriority();
        if (recipe.getSound() != null && !recipe.getSound().isBlank()) {
            this.sound = recipe.getSound();
        }
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
            inventory.setItem(i, renderSlot(i, config.getSlotType(i)));
        }
    }

    private ItemStack renderSlot(int slot, String type) {
        if (type == null) {
            return configItem("background");
        }
        switch (type) {
            case "input":
                return input == null ? configItem("input") : displayForIngredient(input);
            case "tool": {
                int idx = toolSlots.indexOf(slot);
                CuttingBoardRecipe.ToolRequirement tool = idx >= 0 && idx < tools.length ? tools[idx] : null;
                return tool == null ? configItem("tool") : displayForTool(tool);
            }
            case "result": {
                int idx = resultSlots.indexOf(slot);
                ItemStack item = idx >= 0 && idx < resultItems.length ? resultItems[idx] : null;
                if (item == null || item.getType().isAir()) {
                    return configItem("result");
                }
                ItemStack display = item.clone();
                if (idx == selectedResult) {
                    glow(display);
                }
                return display;
            }
            case "result-count":
                return configItem("result-count", Map.of("count", selectedCount()));
            case "result-chance":
                return configItem("result-chance", Map.of("chance", selectedChance()));
            case "priority":
                return configItem("priority", Map.of("priority", String.valueOf(priority)));
            case "info":
                return configItem("info", Map.of("recipe_id", recipeId));
            case "save":
                return configItem("save");
            case "cancel":
                return configItem("cancel");
            case "delete":
                return editingExisting ? configItem("delete") : configItem("background");
            default:
                return configItem("background");
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
        String type = config.getSlotType(slot);
        if (type == null) {
            return;
        }

        switch (type) {
            case "input":
                if (hasCursorItem) {
                    if (click.isRightClick()) {
                        ItemStack source = cleanCopy(cursor);
                        clearCursor();
                        openTagPicker(source);
                        return;
                    }
                    input = new RecipeIngredient.Item(Key.of(RecipeSerializer.itemIdString(cursor)));
                    clearCursor();
                } else if (click.isRightClick()) {
                    input = null;
                } else if (input instanceof RecipeIngredient.Item item) {
                    ItemStack pickedUp = ItemUtils.createItem(item.key().toString());
                    player.setItemOnCursor(pickedUp != null && !pickedUp.getType().isAir() ? cleanCopy(pickedUp) : null);
                    input = null;
                }
                render();
                return;
            case "tool": {
                int idx = toolSlots.indexOf(slot);
                if (idx < 0 || idx >= tools.length) {
                    return;
                }
                if (hasCursorItem) {
                    tools[idx] = new CuttingBoardRecipe.ToolRequirement(Key.of(RecipeSerializer.itemIdString(cursor)));
                    clearCursor();
                } else if (click.isRightClick()) {
                    tools[idx] = null;
                } else if (tools[idx] != null) {
                    ItemStack pickedUp = ItemUtils.createItem(tools[idx].getKey().toString());
                    player.setItemOnCursor(pickedUp != null && !pickedUp.getType().isAir() ? cleanCopy(pickedUp) : null);
                    tools[idx] = null;
                }
                render();
                return;
            }
            case "result": {
                int idx = resultSlots.indexOf(slot);
                if (idx < 0 || idx >= resultItems.length) {
                    return;
                }
                if (hasCursorItem) {
                    ItemStack placed = cleanCopy(cursor);
                    placed.setAmount(resultItems[idx] != null ? resultItems[idx].getAmount() : 1);
                    resultItems[idx] = placed;
                    if (resultChances[idx] <= 0.0) {
                        resultChances[idx] = 1.0;
                    }
                    selectedResult = idx;
                    clearCursor();
                } else if (click.isRightClick()) {
                    resultItems[idx] = null;
                    resultChances[idx] = 0.0;
                    if (selectedResult == idx) {
                        selectedResult = -1;
                    }
                } else if (resultItems[idx] != null) {
                    selectedResult = idx;
                }
                render();
                return;
            }
            case "result-count":
                if (selectedResult >= 0 && resultItems[selectedResult] != null) {
                    int amount = clamp(resultItems[selectedResult].getAmount() + (click.isRightClick() ? -1 : 1), 1, 64);
                    resultItems[selectedResult].setAmount(amount);
                    render();
                }
                return;
            case "result-chance":
                if (selectedResult >= 0 && resultItems[selectedResult] != null) {
                    double chance = resultChances[selectedResult] + (click.isRightClick() ? -0.05 : 0.05);
                    resultChances[selectedResult] = roundChance(Math.max(0.05, Math.min(1.0, chance)));
                    render();
                }
                return;
            case "priority":
                priority = clamp(priority + (click.isRightClick() ? -1 : 1), -100, 100);
                render();
                return;
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
        if (input == null) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.no_input", player));
            return;
        }
        List<CuttingBoardRecipe.ResultEntry> results = new ArrayList<>();
        for (int i = 0; i < resultItems.length; i++) {
            ItemStack item = resultItems[i];
            if (item != null && !item.getType().isAir()) {
                results.add(new CuttingBoardRecipe.ResultEntry(item.clone(), resultChances[i] <= 0.0 ? 1.0 : resultChances[i]));
            }
        }
        if (results.isEmpty()) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.no_result", player));
            return;
        }
        List<CuttingBoardRecipe.ToolRequirement> toolList = new ArrayList<>();
        for (CuttingBoardRecipe.ToolRequirement tool : tools) {
            if (tool != null) {
                toolList.add(tool);
            }
        }
        if (toolList.isEmpty()) {
            toolList.add(new CuttingBoardRecipe.ToolRequirement(Key.of(Constants.TAG_KNIVES)));
        }

        CuttingBoardRecipe recipe = new CuttingBoardRecipe(recipeId, input, null, toolList, results, sound, priority);
        if (plugin.getRecipeEditorStore().saveCuttingBoardRecipe(recipe)) {
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
        if (plugin.getRecipeEditorStore().deleteCuttingBoardRecipe(recipeId)) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.deleted", player, Map.of("recipe_id", recipeId)));
        } else {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.delete_failed", player));
        }
        player.closeInventory();
    }

    private void openTagPicker(ItemStack source) {
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
                    input = ingredient;
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

    private String selectedCount() {
        return selectedResult >= 0 && resultItems[selectedResult] != null
                ? String.valueOf(resultItems[selectedResult].getAmount()) : NONE;
    }

    private String selectedChance() {
        return selectedResult >= 0 && resultItems[selectedResult] != null
                ? String.valueOf(resultChances[selectedResult]) : NONE;
    }

    private ItemStack configItem(String key) {
        return configItem(key, Map.of());
    }

    private ItemStack configItem(String key, Map<String, String> placeholders) {
        GuiConfig.GuiItem item = config.getItem(key);
        if (item == null) {
            item = config.getItem("background");
        }
        return item == null ? new ItemStack(Material.AIR) : item.createItem(new java.util.HashMap<>(placeholders));
    }

    private ItemStack displayForIngredient(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            ItemStack stack = ItemUtils.createItem(item.key().toString());
            return stack != null && !stack.getType().isAir() ? stack : named(new ItemStack(Material.BARRIER), item.key().toString());
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return named(new ItemStack(Material.NAME_TAG), RecipeSerializer.serializeIngredient(tag));
        }
        return new ItemStack(Material.BARRIER);
    }

    private ItemStack displayForTool(CuttingBoardRecipe.ToolRequirement tool) {
        ItemStack stack = ItemUtils.createItem(tool.getKey().toString());
        if (stack != null && !stack.getType().isAir()) {
            return stack;
        }
        return named(new ItemStack(Material.NAME_TAG), RecipeSerializer.serializeTool(tool));
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

    private static void glow(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.LOOTING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
    }

    private static Component coloredTitle(String title) {
        String resolved = title == null ? "" : title;
        if (resolved.contains("<") && resolved.contains(">")) {
            return MINI_MESSAGE.deserialize(resolved);
        }
        return LEGACY.deserialize(resolved.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "§"));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double roundChance(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
