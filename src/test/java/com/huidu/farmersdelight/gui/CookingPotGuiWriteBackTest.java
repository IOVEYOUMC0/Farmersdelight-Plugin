package com.huidu.farmersdelight.gui;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the decision that a click this GUI leaves to vanilla has to be marked for write-back.
 *
 *
 * The double-click passthrough in CookingPotGui#onClick does not cancel the event, so vanilla (not
 * writeWritableSlot) is what changes the GUI slot. syncToBlockEntity and close() write dirty slots only, so a
 * slot vanilla filled has to be marked dirty or the stack is discarded together with the GUI mirror.
 *
 *
 * An empty cursor must NOT mark the slot: vanilla then leaves it at the value the cook tick last published,
 * and writing that value back would let the mirror's older stack overwrite the tick's consumption. The
 * predicate is exercised without a server because it reads only the click enums and a cursor flag; the
 * InventoryClickEvent wiring around it is covered by the manual steps in the round's notes.
 */
class CookingPotGuiWriteBackTest {

    private static boolean marks(InventoryAction action, ClickType click, boolean clickedTop, boolean cursorHasItem) {
        return CookingPotGui.marksSlotDirtyForVanillaPlacement(action, click, clickedTop, cursorHasItem);
    }

    @Test
    void doubleClickPassthroughWithAnItemOnTheCursorIsMarkedForWriteBack() {
        assertTrue(marks(InventoryAction.NOTHING, ClickType.DOUBLE_CLICK, true, true),
                "a slot vanilla may fill must be written back, or the stack is lost when the view closes");
    }

    @Test
    void doubleClickPassthroughWithAnEmptyCursorIsNotMarked() {
        assertFalse(marks(InventoryAction.NOTHING, ClickType.DOUBLE_CLICK, true, false),
                "an empty cursor leaves the slot to the cook tick; writing it back would restore a consumed ingredient");
    }

    @Test
    void otherActionsAreNotMarked() {
        assertFalse(marks(InventoryAction.PICKUP_ALL, ClickType.DOUBLE_CLICK, true, true));
        assertFalse(marks(InventoryAction.NOTHING, ClickType.LEFT, true, true));
        assertFalse(marks(InventoryAction.NOTHING, ClickType.RIGHT, true, true));
    }

    @Test
    void aClickOutsideTheTopInventoryIsNotMarked() {
        assertFalse(marks(InventoryAction.NOTHING, ClickType.DOUBLE_CLICK, false, true),
                "only a slot in this GUI's top inventory can be written back");
    }
}
