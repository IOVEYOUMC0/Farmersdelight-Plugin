package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.api.recipe.EditableRecipe;
import com.huidu.farmersdelight.api.recipe.NumericField;
import com.huidu.farmersdelight.api.recipe.RecipeEditor;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic recipe editor backed by a RecipeEditor: editable item slots + result, numeric-field
 * buttons (left +, right -), a result-count button, and save/delete/cancel. Persists by handing an
 * EditableRecipe back to the addon's editor.
 */
public final class RecipeEditorView implements InventoryHolder {

    private static final int RESULT_SLOT = 16;
    private static final int RESULT_COUNT_SLOT = 25;
    private static final int NUMERIC_START = 28;
    private static final int SLOT_SAVE = 48;
    private static final int SLOT_CANCEL = 49;
    private static final int SLOT_DELETE = 50;
    private static final int MAX_ITEM_SLOTS = 6;

    private final RecipeEditor editor;
    private final EditableRecipe draft;
    private final List<String> slotLabels;
    private final List<NumericField> numericFields;
    private final int[] itemSlots;
    private Inventory inventory;

    private RecipeEditorView(RecipeType type, String recipeId) {
        this.editor = type.editor();
        this.slotLabels = editor.itemSlotLabels();
        this.numericFields = editor.numericFields();
        EditableRecipe loaded = editor.load(recipeId);
        this.draft = loaded != null ? loaded : new EditableRecipe(recipeId, slotLabels.size());
        int count = Math.min(MAX_ITEM_SLOTS, slotLabels.size());
        this.itemSlots = new int[count];
        for (int i = 0; i < count; i++) {
            itemSlots[i] = 10 + i;
        }
    }

    public static void open(Player player, RecipeType type, String recipeId) {
        if (type == null || type.editor() == null) {
            return;
        }
        RecipeBookListener.ensureRegistered();
        RecipeEditorView view = new RecipeEditorView(type, recipeId);
        view.draw();
        player.openInventory(view.inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void draw() {
        Component title = Component.text("Edit: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(String.valueOf(draft.id()), NamedTextColor.DARK_AQUA));
        inventory = Bukkit.createInventory(this, 54, title);

        List<Component> labelLore = new ArrayList<>();
        for (int i = 0; i < itemSlots.length; i++) {
            labelLore.add(Component.text("Slot " + (i + 1) + ": " + slotLabels.get(i), NamedTextColor.GRAY));
        }
        inventory.setItem(4, named(new ItemStack(Material.KNOWLEDGE_BOOK),
                Component.text("Input slots", NamedTextColor.AQUA), labelLore));

        for (int i = 0; i < itemSlots.length; i++) {
            inventory.setItem(itemSlots[i], draft.item(i));
        }
        inventory.setItem(RESULT_SLOT, draft.result());
        inventory.setItem(RESULT_COUNT_SLOT, countButton());

        for (int i = 0; i < numericFields.size() && NUMERIC_START + i < SLOT_SAVE; i++) {
            inventory.setItem(NUMERIC_START + i, numericButton(numericFields.get(i)));
        }

        inventory.setItem(SLOT_SAVE, named(new ItemStack(Material.LIME_CONCRETE),
                Component.text("Save", NamedTextColor.GREEN), null));
        inventory.setItem(SLOT_CANCEL, named(new ItemStack(Material.BARRIER),
                Component.text("Cancel", NamedTextColor.RED), null));
        inventory.setItem(SLOT_DELETE, named(new ItemStack(Material.LAVA_BUCKET),
                Component.text("Delete", NamedTextColor.DARK_RED), null));
    }

    boolean isEditableSlot(int rawSlot) {
        if (rawSlot == RESULT_SLOT) {
            return true;
        }
        for (int slot : itemSlots) {
            if (slot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    /** Handles clicks on the control buttons; returns true if the click was a control (and consumed). */
    boolean handleButton(Player player, int rawSlot, boolean rightClick) {
        if (rawSlot == RESULT_COUNT_SLOT) {
            draft.setResultCount(Math.max(1, draft.resultCount() + (rightClick ? -1 : 1)));
            inventory.setItem(RESULT_COUNT_SLOT, countButton());
            return true;
        }
        int numericIndex = rawSlot - NUMERIC_START;
        if (numericIndex >= 0 && numericIndex < numericFields.size()) {
            NumericField field = numericFields.get(numericIndex);
            double current = draft.number(field.key(), field.min());
            double next = current + (rightClick ? -field.step() : field.step());
            next = Math.max(field.min(), Math.min(field.max(), next));
            draft.setNumber(field.key(), next);
            inventory.setItem(rawSlot, numericButton(field));
            return true;
        }
        if (rawSlot == SLOT_SAVE) {
            commitItems();
            boolean ok = editor.save(draft);
            player.sendMessage(Component.text(ok ? "Saved recipe " + draft.id() : "Save failed",
                    ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            player.closeInventory();
            return true;
        }
        if (rawSlot == SLOT_CANCEL) {
            player.closeInventory();
            return true;
        }
        if (rawSlot == SLOT_DELETE) {
            boolean ok = editor.delete(draft.id());
            player.sendMessage(Component.text(ok ? "Deleted recipe " + draft.id() : "Delete failed",
                    ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            player.closeInventory();
            return true;
        }
        return false;
    }

    private void commitItems() {
        for (int i = 0; i < itemSlots.length; i++) {
            draft.setItem(i, inventory.getItem(itemSlots[i]));
        }
        draft.setResult(inventory.getItem(RESULT_SLOT));
    }

    private ItemStack countButton() {
        return named(new ItemStack(Material.PAPER, Math.max(1, Math.min(64, draft.resultCount()))),
                Component.text("Result count: " + draft.resultCount(), NamedTextColor.YELLOW),
                List.of(Component.text("Left +1 / Right -1", NamedTextColor.GRAY)));
    }

    private ItemStack numericButton(NumericField field) {
        double value = draft.number(field.key(), field.min());
        String shown = field.decimals() <= 0
                ? String.valueOf((long) value)
                : String.format("%." + field.decimals() + "f", value);
        return named(new ItemStack(Material.COMPARATOR),
                Component.text(field.label() + ": " + shown, NamedTextColor.YELLOW),
                List.of(Component.text("Left +" + field.step() + " / Right -" + field.step(), NamedTextColor.GRAY)));
    }

    private static ItemStack named(ItemStack item, Component name, List<Component> lore) {
        RecipeBookGui.rename(item, name);
        if (lore != null && !lore.isEmpty()) {
            RecipeBookGui.applyLore(item, lore);
        }
        return item;
    }
}
