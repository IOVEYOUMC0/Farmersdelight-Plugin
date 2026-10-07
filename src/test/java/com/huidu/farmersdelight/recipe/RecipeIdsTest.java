package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The id spelling rules a command relies on: the stored form wins, the other spelling of the same recipe
 * resolves to it, and the displayed form is namespaced whatever the source of the id called it.
 */
class RecipeIdsTest {

    private static final String POT = "farmersdelight:cooking_pot";
    private static final String SKEWERING = "barbequesdelight:skewering";
    // The plugin's own files store the YAML key; a station that derives its ids from item ids stores a
    // namespaced one.
    private static final Set<String> OWN_FILE_IDS = Set.of("beef_stew", "vegetable_soup");
    private static final Set<String> DERIVED_IDS = Set.of("barbequesdelight:raw_beef_skewer");

    @Test
    void bothSpellingsResolveToTheStoredId() {
        assertEquals("beef_stew", RecipeIds.canonical(POT, "beef_stew", OWN_FILE_IDS));
        assertEquals("beef_stew", RecipeIds.canonical(POT, "farmersdelight:beef_stew", OWN_FILE_IDS),
                "the namespaced spelling of a plugin-file recipe has to find it");
    }

    @Test
    void aStoredNamespacedIdResolvesFromItsBareSpellingToo() {
        assertEquals("barbequesdelight:raw_beef_skewer",
                RecipeIds.canonical(SKEWERING, "barbequesdelight:raw_beef_skewer", DERIVED_IDS));
        assertEquals("barbequesdelight:raw_beef_skewer",
                RecipeIds.canonical(SKEWERING, "raw_beef_skewer", DERIVED_IDS),
                "a bare token takes the type's namespace");
    }

    @Test
    void unknownAndForeignIdsStayUnknown() {
        assertNull(RecipeIds.canonical(POT, "nope", OWN_FILE_IDS));
        assertNull(RecipeIds.canonical(POT, "farmersdelight:nope", OWN_FILE_IDS));
        assertNull(RecipeIds.canonical(POT, "brewinandchewin:beer", OWN_FILE_IDS),
                "another station's recipe is not this one's");
        assertNull(RecipeIds.canonical(POT, null, OWN_FILE_IDS));
        assertNull(RecipeIds.canonical(POT, "beef_stew", Set.of()));
    }

    @Test
    void theStoredSpellingIsWhatPersistedStateKeepsUsing() {
        // Persisted keys are built from this id, so a bare key written by an earlier build stays the id for
        // every later command line, whichever spelling it uses.
        assertEquals("beef_stew", RecipeIds.canonical(POT, "beef_stew", OWN_FILE_IDS));
        assertEquals("vegetable_soup", RecipeIds.canonical(POT, "farmersdelight:vegetable_soup", OWN_FILE_IDS));
    }

    @Test
    void displayedIdsAreNamespaced() {
        assertEquals("farmersdelight:beef_stew", RecipeIds.displayId(POT, "beef_stew"));
        assertEquals("barbequesdelight:raw_beef_skewer",
                RecipeIds.displayId(SKEWERING, "barbequesdelight:raw_beef_skewer"),
                "an id that is already namespaced is shown unchanged");
        assertEquals("brewinandchewin:beer", RecipeIds.displayId("brewinandchewin:keg", "beer"),
                "a pack section's bare key is shown under its own namespace");
        assertNull(RecipeIds.displayId(POT, null));
    }

    @Test
    void theNamespaceComesFromTheType() {
        assertEquals("farmersdelight", RecipeIds.namespaceOf(POT));
        assertEquals("barbequesdelight", RecipeIds.namespaceOf(SKEWERING));
        assertEquals("farmersdelight", RecipeIds.namespaceOf("pot"),
                "a bare station keyword belongs to the plugin's own namespace");
        assertEquals("farmersdelight", RecipeIds.namespaceOf(null));
    }
}
