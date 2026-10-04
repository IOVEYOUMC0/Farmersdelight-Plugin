package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.api.gui.GuiLayout;
import com.huidu.farmersdelight.api.gui.GuiLayouts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cooking-pot/skillet layout seen through the shared read-only view.
 *
 *
 * The config constructor only walks the grid and the legend, so this runs without a server — no item is built
 * here. It also pins the coexistence the design calls out: the normalising reader calls an undrawn cell
 * background, while this class's own per-slot lookup keeps answering null for it.
 */
class GuiConfigLayoutTest {

    private static final Map<Character, String> LEGEND =
            Map.of('I', "ingredient", 'X', GuiLayout.BACKGROUND, 'O', "output");

    @Test
    void theInterfaceViewMatchesTheGrid() {
        GuiConfig config = config();

        assertEquals(3, config.rows());
        assertEquals(27, config.size());
        assertEquals(config.getSize(), config.size());
        assertTrue(config.contains(0));
        assertTrue(config.contains(26));
        assertFalse(config.contains(-1));
        assertFalse(config.contains(27));
        assertTrue(config.isFunctional(0));
        assertFalse(config.isFunctional(9), "a background cell is not functional");
        assertTrue(config.isFunctional(22), "an output cell is functional but not interactive");
        assertFalse(config.isFunctional(27), "a slot outside the inventory is not functional");
        assertFalse(config.isFunctional(-1), "and asking about one must not read a negative column");
        assertFalse(config.isInteractiveSlot(22));
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 22}, config.functionalSlots());
        assertArrayEquals(new int[]{22}, config.slotsOf("output"));
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, config.slotsOf("ingredient"));
        assertArrayEquals(new int[0], config.slotsOf("lure"), "an unknown type is empty, not null");
        assertArrayEquals(new int[0], config.slotsOf(null));
        assertEquals(22, config.firstSlotOf("output"));
        assertEquals(-1, config.firstSlotOf("lure"));
    }

    @Test
    void anUndrawnCellIsNullForTheSlotLookupAndBackgroundForTheReader() {
        GuiConfig config = new GuiConfig("title", "", "", true, 2,
                List.of("IIIII", "XXXXX"),
                Map.of('I', "ingredient", 'X', GuiLayout.BACKGROUND),
                Map.of(), List.of());

        assertNull(config.getSlotType(5), "a column this row does not draw");
        assertNull(config.getSlotType(18), "a row the grid does not draw");
        assertFalse(config.isFunctional(5));
        assertFalse(config.isFunctional(18));
        assertFalse(config.contains(18), "the layout is only two rows tall");
        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, config.functionalSlots());
        assertEquals(GuiLayout.BACKGROUND,
                GuiLayouts.cellTypes(2, List.of("IIIII", "XXXXX"),
                        Map.of('I', "ingredient", 'X', GuiLayout.BACKGROUND))[5],
                "the normalising reader calls the undrawn cell background instead");
    }

    @Test
    void theViewsArraysAreCopies() {
        GuiConfig config = config();

        int[] functional = config.functionalSlots();
        int[] output = config.slotsOf("output");
        functional[0] = 99;
        output[0] = 99;

        assertEquals(0, config.functionalSlots()[0], "a caller cannot change the layout through the array");
        assertEquals(22, config.slotsOf("output")[0]);
    }

    /** Three rows: nine ingredients, a background row, then an output cell under the background. */
    private static GuiConfig config() {
        return new GuiConfig("title", "", "", true, 3,
                List.of("IIIIIIIII", "XXXXXXXXX", "XXXXOXXXX"),
                LEGEND, Map.of(), List.of());
    }
}
