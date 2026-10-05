package com.huidu.farmersdelight.api.gui;

import org.jetbrains.annotations.Nullable;

/**
 * Binds one legend type to a contiguous range of the store.
 *
 *
 * A container GUI is a set of configured cells on one side and a flat array of stored values on the other;
 * this record is the join between them. {@code type} picks the cells ({@link GuiLayout#slotsOf(String)}),
 * {@code storeStart} is the store index the first of those cells maps to, and {@code count} caps how many of
 * them carry values — a layout that draws more cells of a type than the store has room for leaves the extra
 * cells decorative.
 *
 *
 * {@code iconType} names the placeholder item painted into a cell whose stored value is empty, or null when
 * an empty cell is simply cleared. The item itself comes from the controller, because only the station knows
 * where its placeholder comes from.
 */
public record GuiSlotGroup(String type, int storeStart, int count, @Nullable String iconType) {

    /** The store index the cell at {@code offset} within this group maps to. */
    public int storeIndex(int offset) {
        return storeStart + offset;
    }
}
