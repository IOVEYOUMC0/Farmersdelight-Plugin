package com.huidu.farmersdelight.api.gui;

import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;

/**
 * A read-only view of a character-grid GUI layout.
 */
@ApiStatus.OverrideOnly
public interface GuiLayout {

    /** The legend type of a decoration cell; every layout in this plugin family spells it this way. */
    String BACKGROUND = "background";

    /** The configured row count. Shipped GUIs use 1-6 rows. */
    int rows();

    /** The inventory size this layout describes: rows * 9. */
    default int size() {
        return rows() * 9;
    }

    /** True when the raw slot belongs to this GUI's own inventory rather than the viewer's. */
    default boolean contains(int rawSlot) {
        return rawSlot >= 0 && rawSlot < size();
    }

    /**
     * True when the cell is part of the layout's contents rather than decoration.
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
