package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.AbstractInventoryGui;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-game editor for editing a single cutting-board recipe (input, tools, weighted results, priority). Layout
 * and button text come from the recipe-cutting-board-editor-gui section of gui.yml; chat
 * feedback comes from the gui.editor.* language keys.
 */
public final class CuttingBoardEditorGui extends AbstractInventoryGui implements EditorGui {

    private static final String NONE = "-";

    private final String recipeId;
    private final boolean editingExisting;
    private final RecipeViewGuiConfig.BaseConfig config;

    private final List<Integer> toolSlots;
    private final List<Integer> resultSlots;

    private RecipeIngredient input;
    private final CuttingBoardRecipe.ToolRequirement[] tools;
    private final ItemStack[] resultItems;
    private final double[] resultChances;
    private int priority = 0;
    private String sound = Constants.SOUND_CUTTING_BOARD_KNIFE;
    private int selectedResult = -1;

    public CuttingBoardEditorGui(FarmersDelightPlugin plugin, Player player, String recipeId,
                                 CuttingBoardRecipe existing, RecipeViewGuiConfig.BaseConfig config) {
        super(plugin, player);
        this.recipeId = recipeId;
        this.editingExisting = existing != null;
        this.config = config;

        this.toolSlots = config.getSlotsByType("tool");
        this.resultSlots = config.getSlotsByType("result");

        this.tools = new CuttingBoardRecipe.ToolRequirement[Math.max(1, toolSlots.size())];
        this.resultItems = new ItemStack[Math.max(1, resultSlots.size())];
        this.resultChances = new double[Math.max(1, resultSlots.size())];

        this.inventory = plugin.getServer().createInventory(this, config.getSize(), EditorGui.coloredComponent(config.getTitle()));
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
            player.sendMessage(Component.translatable("gui.editor.feedback.too_many_results",
                    Component.text(recipeResults.size()),
                    Component.text(resultItems.length))
                    .color(NamedTextColor.YELLOW));
        }
        this.priority = recipe.getPriority();
        if (recipe.getSound() != null && !recipe.getSound().isBlank()) {
            this.sound = recipe.getSound();
        }
    }

    public void open() {
        doOpen(this::render);
    }

    @Override
    protected AbstractInventoryGui findExistingGui(UUID playerId) {
        return null;
    }

    @Override
    protected void putActiveGui(UUID playerId, AbstractInventoryGui gui) {
    }

    @Override
    protected void removeFromActiveGuis(UUID playerId) {
    }

    @Override
    protected void ensureListenerRegistered() {
        RecipeEditorListener.ensureRegistered(plugin);
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
                closeEditor();
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
        super.close();
        clearCursor();
        plugin.scheduler().runLaterForEntity(player, this::clearCursor, 1L);
    }

    private void save() {
        if (input == null) {
            player.sendMessage(Component.translatable("gui.editor.feedback.no_input")
                    .color(NamedTextColor.RED));
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
            player.sendMessage(Component.translatable("gui.editor.feedback.no_result")
                    .color(NamedTextColor.RED));
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
            player.sendMessage(Component.translatable("gui.editor.feedback.saved",
                    Component.text(recipeId).color(NamedTextColor.WHITE))
                    .color(NamedTextColor.GREEN));
            closeEditor();
        } else {
            player.sendMessage(Component.translatable("gui.editor.feedback.save_failed")
                    .color(NamedTextColor.RED));
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
            player.sendMessage(Component.translatable("gui.editor.feedback.deleted",
                    Component.text(recipeId).color(NamedTextColor.WHITE))
                    .color(NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.translatable("gui.editor.feedback.delete_failed")
                    .color(NamedTextColor.RED));
        }
        player.closeInventory();
    }

    private void openTagPicker(ItemStack source) {
        RecipeViewGuiConfig.BaseConfig pickerConfig = plugin.getRecipeEditorGuiConfig().getTagPickerConfig();
        if (pickerConfig == null) {
            player.sendMessage(Component.translatable("gui.editor.feedback.advanced_coming")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        List<String> tags = ItemUtils.getAllItemTagIds(source);
        if (tags.isEmpty()) {
            player.sendMessage(Component.translatable("gui.editor.feedback.no_tags")
                    .color(NamedTextColor.RED));
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

    private void closeEditor() {
        super.close();
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double roundChance(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
