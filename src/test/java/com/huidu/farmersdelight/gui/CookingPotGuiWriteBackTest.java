package com.huidu.farmersdelight.gui;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Double-click placement is handled by the same synchronous transaction as ordinary placement. */
class CookingPotGuiWriteBackTest {

    private static boolean places(InventoryAction action, ClickType click, boolean clickedTop, boolean cursorHasItem) {
        return CookingPotGui.isDoubleClickPlacement(action, click, clickedTop, cursorHasItem);
    }

    @Test
    void doubleClickPlacementWithAnItemOnTheCursorIsHandledSynchronously() {
        assertTrue(places(InventoryAction.NOTHING, ClickType.DOUBLE_CLICK, true, true),
                "double-click placement must use the synchronous top-slot transaction");
    }

    @Test
    void doubleClickPlacementWithAnEmptyCursorIsNotPlaced() {
        assertFalse(places(InventoryAction.NOTHING, ClickType.DOUBLE_CLICK, true, false),
                "an empty cursor leaves the slot to the cook tick; writing it back would restore a consumed ingredient");
    }

    @Test
    void otherActionsAreNotPlaced() {
        assertFalse(places(InventoryAction.PICKUP_ALL, ClickType.DOUBLE_CLICK, true, true));
        assertFalse(places(InventoryAction.NOTHING, ClickType.LEFT, true, true));
        assertFalse(places(InventoryAction.NOTHING, ClickType.RIGHT, true, true));
    }

    @Test
    void aClickOutsideTheTopInventoryIsNotPlaced() {
        assertFalse(places(InventoryAction.NOTHING, ClickType.DOUBLE_CLICK, false, true),
                "only a slot in this GUI's top inventory can be written back");
    }
}
