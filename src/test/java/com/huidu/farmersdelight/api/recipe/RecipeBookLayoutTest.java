package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.api.gui.GuiLayout;
import com.huidu.farmersdelight.api.gui.GuiLayouts;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recipe-book layout seen through the shared read-only view, which it now extends.
 *
 *
 * The point of interest is a legend key written without a value: the config reader copies it as null, and a
 * reader that treated null as a type of its own would call that cell functional while the normalising grid
 * reader calls it background. These tests pin both answers to the same cell.
 */
class RecipeBookLayoutTest {

    private static RecipeBookLayout layout() {
        // Row one ends in a legend key with no value (the blank one), row two is a full recipe row.
        return new SimpleRecipeBookLayout(Component.text("title"), 2,
                List.of("RXXrXXXXX", "RRRRRRRRR"),
                Map.of('R', "recipe", 'X', GuiLayout.BACKGROUND, 'r', ""),
                Map.of());
    }

    @Test
    void aLegendValueThatIsNullOrBlankReadsAsBackground() {
        RecipeBookLayout layout = layout();

        assertEquals(GuiLayout.BACKGROUND, layout.slotType(3), "a key with no value is background");
        assertFalse(layout.isFunctional(3));
        assertArrayEquals(new int[0], layout.slotsOf(""));
    }

    @Test
    void theDrawnTypesAreReadFromTheGrid() {
        RecipeBookLayout layout = layout();

        assertEquals("recipe", layout.slotType(0));
        assertTrue(layout.isFunctional(0));
        assertArrayEquals(new int[]{0, 9, 10, 11, 12, 13, 14, 15, 16, 17}, layout.slotsOf("recipe"));
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8}, layout.slotsOf(GuiLayout.BACKGROUND));
        assertEquals(0, layout.firstSlotOf("recipe"));
        assertEquals(-1, layout.firstSlotOf("bait"));
        assertEquals(0, layout.slotsOf(null).length);
    }

    @Test
    void theSharedViewAgreesWithTheNormalisingGridReader() {
        RecipeBookLayout layout = layout();
        String[] cells = GuiLayouts.cellTypes(2, List.of("RXXrXXXXX", "RRRRRRRRR"),
                Map.of('R', "recipe", 'X', GuiLayout.BACKGROUND, 'r', ""));

        for (int slot = 0; slot < layout.size(); slot++) {
            assertEquals(cells[slot], layout.slotType(slot), "slot " + slot);
            assertEquals(!"background".equals(cells[slot]), layout.isFunctional(slot), "slot " + slot);
        }
    }

    @Test
    void cellsTheGridDoesNotDrawAreBackgroundAndNotFunctional() {
        RecipeBookLayout layout = new SimpleRecipeBookLayout(Component.text("title"), 3,
                List.of("RRR"), Map.of('R', "recipe"), Map.of());

        assertEquals(3, layout.rows());
        assertEquals(27, layout.size());
        assertEquals("recipe", layout.slotType(0));
        assertEquals(GuiLayout.BACKGROUND, layout.slotType(3), "a column the row does not draw");
        assertEquals(GuiLayout.BACKGROUND, layout.slotType(20), "a row the grid does not draw");
        assertFalse(layout.isFunctional(3));
        assertFalse(layout.isFunctional(-1), "asking about a slot before the grid must not read a negative column");
        assertFalse(layout.isFunctional(27));
        assertEquals(3, layout.slotsOf("recipe").length);
    }

    @Test
    void theLegacyListLookupStillAnswersAsBefore() {
        RecipeBookLayout layout = layout();

        List<Integer> legacy = layout.slotsByType("recipe");
        List<Integer> shared = new ArrayList<>();
        for (int slot : layout.slotsOf("recipe")) {
            shared.add(slot);
        }

        assertEquals(legacy, shared, "the list and array lookups describe the same cells");
        assertEquals(0, layout.firstSlotByType("recipe"));
        assertEquals(-1, layout.firstSlotByType("bait"));
    }
}
