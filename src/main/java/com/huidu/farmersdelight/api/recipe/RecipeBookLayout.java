package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.api.gui.GuiLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ApiStatus.OverrideOnly
public interface RecipeBookLayout extends GuiLayout {

    Component title();

    int rows();

    List<String> layout();

    Map<Character, String> legend();

    default Map<String, ItemStack> decorations() {
        return Map.of();
    }

    default int size() {
        return rows() * 9;
    }

    default List<Integer> slotsByType(String role) {
        List<Integer> slots = new ArrayList<>();
        List<String> rows = layout();
        Map<Character, String> legend = legend();
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            for (int col = 0; col < line.length() && col < 9; col++) {
                if (role.equals(legend.get(line.charAt(col)))) {
                    slots.add(row * 9 + col);
                }
            }
        }
        return slots;
    }

    default int firstSlotByType(String role) {
        List<Integer> slots = slotsByType(role);
        return slots.isEmpty() ? -1 : slots.getFirst();
    }

    /**
     * The normalised legend type of one cell, the primitive the shared {@link GuiLayout} view is derived
     * from: a cell outside the drawn grid, and a legend entry that is null or blank, both read as
     * {@link GuiLayout#BACKGROUND}.
     *
     *
     * The blank normalisation is what keeps this view and {@code GuiLayouts.cellTypes} agreeing. The config
     * reader copies a legend value straight out of gui.yml, so a key written without a value arrives as null;
     * treating that as a type of its own would make the cell functional here while the normalising reader
     * calls the same cell background.
     */
    default String slotType(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        List<String> rows = layout();
        if (slot < 0 || rows == null || row >= rows.size()) {
            return GuiLayout.BACKGROUND;
        }
        String line = rows.get(row);
        if (line == null || column >= line.length()) {
            return GuiLayout.BACKGROUND;
        }
        Map<Character, String> legend = legend();
        String type = legend == null ? null : legend.get(line.charAt(column));
        return type == null || type.isBlank() ? GuiLayout.BACKGROUND : type;
    }

    @Override
    default boolean isFunctional(int slot) {
        return slot >= 0 && slot < size() && !GuiLayout.BACKGROUND.equals(slotType(slot));
    }

    /**
     * {@inheritDoc}
     *
     *
     * Answered from the grid, like {@link #slotsByType(String)} but as the shared view's array: a cell the
     * grid does not draw reads as {@link GuiLayout#BACKGROUND}, so asking for that type lists the undrawn
     * cells as well.
     */
    @Override
    default int[] slotsOf(String type) {
        if (type == null) {
            return new int[0];
        }
        int size = size();
        int[] found = new int[size];
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            if (type.equals(slotType(slot))) {
                found[count++] = slot;
            }
        }
        return Arrays.copyOf(found, count);
    }
}
