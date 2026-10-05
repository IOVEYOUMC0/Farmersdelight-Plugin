package com.huidu.farmersdelight.handheld;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the handheld cooking mapping: it cooks exactly what the config declares, and never invents a result
 * for an unknown raw id or for an empty table.
 */
class SkewerResultTableTest {

    private static final String RAW_MEAT = "farmersdelight:meat_skewer";
    private static final String COOKED_MEAT = "farmersdelight:cooked_meat_skewer";

    @Test
    void aDeclaredSkewerCooksIntoItsDeclaredResult() {
        SkewerResultTable table = new SkewerResultTable(Map.of(
                RAW_MEAT, COOKED_MEAT,
                "farmersdelight:vegetable_skewer", "farmersdelight:cooked_vegetable_skewer"));

        assertEquals(COOKED_MEAT, table.resolve(RAW_MEAT));
        assertEquals("farmersdelight:cooked_vegetable_skewer", table.resolve("farmersdelight:vegetable_skewer"));
        assertEquals(2, table.size());
        assertTrue(table.cooks(RAW_MEAT));
    }

    @Test
    void anUnknownRawIdNeverCooks() {
        SkewerResultTable table = new SkewerResultTable(Map.of(RAW_MEAT, COOKED_MEAT));

        assertNull(table.resolve("farmersdelight:skillet"));
        assertNull(table.resolve("minecraft:stick"));
        assertFalse(table.cooks("farmersdelight:skillet"), "an unknown item must not start a session");
        assertNull(table.resolve(null));
        assertNull(table.resolve("   "), "a blank id is not a mapping");
        assertFalse(table.cooks(null));
    }

    @Test
    void aMissingOrEmptyTableCooksNothingInsteadOfGuessing() {
        SkewerResultTable empty = new SkewerResultTable(null);
        assertTrue(empty.isEmpty());
        assertNull(empty.resolve(RAW_MEAT), "no configured mapping means no cooking, never a default");
        assertFalse(empty.cooks(RAW_MEAT));

        SkewerResultTable blank = new SkewerResultTable(Map.of("farmersdelight:vegetable_skewer", "  "));
        assertTrue(blank.isEmpty(), "a blank result is not a usable mapping");
    }

    @Test
    void theFirstDeclarationWinsAndTheTableIsUnaffectedByLaterMutation() {
        // A Map cannot hold the same key twice, so the second declaration differs only by whitespace: both
        // normalise to RAW_MEAT, which is exactly the collision the first-wins rule is about.
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("  " + RAW_MEAT + " ", COOKED_MEAT);
        configured.put(RAW_MEAT, "farmersdelight:something_else");
        SkewerResultTable table = new SkewerResultTable(configured);

        assertEquals(COOKED_MEAT, table.resolve(RAW_MEAT), "the first declaration wins, like the id tables");

        configured.clear();
        assertEquals(COOKED_MEAT, table.resolve(RAW_MEAT), "the table has to be a snapshot, not a view");
        assertEquals(1, table.size(), "the colliding declarations are one mapping");
    }

    @Test
    void surroundingWhitespaceInTheConfigIsTolerated() {
        SkewerResultTable table = new SkewerResultTable(Map.of("  " + RAW_MEAT + " ", " " + COOKED_MEAT));

        assertEquals(COOKED_MEAT, table.resolve(RAW_MEAT));
        assertEquals(COOKED_MEAT, table.resolve("  " + RAW_MEAT + "  "));
    }
}
