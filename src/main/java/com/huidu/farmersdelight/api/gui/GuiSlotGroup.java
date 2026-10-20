package com.huidu.farmersdelight.api.gui;

import org.jetbrains.annotations.Nullable;

/**
 * Binds one legend type to a contiguous range of the store.
 */
public record GuiSlotGroup(String type, int storeStart, int count, @Nullable String iconType) {

    /** The store index the cell at offset within this group maps to. */
    public int storeIndex(int offset) {
        return storeStart + offset;
    }
}
