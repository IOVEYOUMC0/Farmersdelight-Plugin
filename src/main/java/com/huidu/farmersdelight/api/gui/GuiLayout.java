package com.huidu.farmersdelight.api.gui;

import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;

/**
 * A read-only view of a character-grid GUI layout.
 *
 *
 * The grid decides which cells belong to the GUI and which are decoration: a cell whose legend type is
 * {@link #BACKGROUND} (or a cell outside the grid) is decoration, every other cell holds contents. Nothing
 * here can change the layout, and no implementation hands out its own arrays, so a caller may keep or sort
 * what it gets back.
 *
 *
 * Implementations are the plugin's own layout readers (the cooking-pot/skillet gui.yml reader, the addon
 * layout readers) and addons that build a layout of their own. This is the view a shared GUI engine reads;
 * it carries no station knowledge, no titles and no items.
 */
@ApiStatus.OverrideOnly
public interface GuiLayout {

    /** The legend type of a decoration cell; every layout in this plugin family spells it this way. */
    String BACKGROUND = "background";

    /** The configured row count. Shipped GUIs use 1-6 rows. */
    int rows();

    /** The inventory size this layout describes: {@code rows * 9}. */
    default int size() {
        return rows() * 9;
    }

    /** True when the raw slot belongs to this GUI's own inventory rather than the viewer's. */
    default boolean contains(int rawSlot) {
        return rawSlot >= 0 && rawSlot < size();
    }

    /**
     * True when the cell is part of the layout's contents rather than decoration.
     *
     *
     * This says the cell is addressable, not that it is writable: a station's display-only cells (progress,
     * output, meal) are functional here and still must not be written back by a GUI. Kept as the primitive
     * because implementations hold different structures — one answers from its icon table, another scans its
     * precomputed cell array.
     */
    boolean isFunctional(int slot);

    /** Every functional cell in ascending slot order, as a fresh array; a hot caller should cache it. */
    default int[] functionalSlots() {
        int size = size();
        int[] found = new int[size];
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            if (isFunctional(slot)) {
                found[count++] = slot;
            }
        }
        return Arrays.copyOf(found, count);
    }

    /**
     * The cells whose legend type equals the given type, in ascending slot order, as a fresh array. An
     * unknown or null type returns an empty array, never null.
     */
    int[] slotsOf(String type);

    /** The first cell of a type, or -1 when the layout has none. */
    default int firstSlotOf(String type) {
        int[] slots = slotsOf(type);
        return slots.length == 0 ? -1 : slots[0];
    }
}
