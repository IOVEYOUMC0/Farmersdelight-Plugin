package com.huidu.farmersdelight.api.content;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentIdTest {

    @Test
    void parsesVanillaAndCustomIds() {
        ContentId stone = ContentId.parse("minecraft:stone");
        assertEquals("minecraft", stone.namespace());
        assertEquals("stone", stone.path());
        assertEquals("minecraft:stone", stone.value());

        ContentId knives = ContentId.parse("farmersdelight:tools/knives");
        assertEquals("farmersdelight", knives.namespace());
        assertEquals("tools/knives", knives.path());
    }

    @Test
    void trimsTheWholeInputButNotTheParts() {
        ContentId id = ContentId.parse("  minecraft:stone  ");
        assertEquals("minecraft:stone", id.value());
    }

    @Test
    void parsesNullAsNullOnlyInTheOptionalHelper() {
        assertNull(ContentId.parseOrNull(null));
        assertNull(ContentId.parseOrNull("  "));
        assertNull(ContentId.parseOrNull("stone"));
        assertNull(ContentId.parseOrNull("Minecraft:stone"));
        assertEquals("minecraft:stone", ContentId.parseOrNull("minecraft:stone").value());
    }

    @Test
    void rejectsMalformedIds() {
        for (String invalid : List.of("", "stone", ":stone", "minecraft:", "a:b:c",
                "Minecraft:stone", "minecraft:Stone", "minecraft:st one")) {
            assertThrows(IllegalArgumentException.class, () -> ContentId.parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> ContentId.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ContentId.of("", "stone"));
        assertThrows(IllegalArgumentException.class, () -> ContentId.of("minecraft", ""));
    }

    @Test
    void equalityHashAndOrderingAreCanonical() {
        ContentId a = ContentId.of("minecraft", "stone");
        ContentId b = ContentId.parse("minecraft:stone");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(0, a.compareTo(b));

        List<ContentId> ids = new ArrayList<>(List.of(
                ContentId.parse("minecraft:stone"),
                ContentId.parse("farmersdelight:tools/knives"),
                ContentId.parse("minecraft:apple")));
        ids.sort(ContentId::compareTo);
        assertEquals(List.of("farmersdelight:tools/knives", "minecraft:apple", "minecraft:stone"),
                ids.stream().map(ContentId::value).toList());
    }

    @Test
    void distinctIdsAreNotEqual() {
        assertNotEquals(ContentId.parse("minecraft:stone"), ContentId.parse("minecraft:dirt"));
        assertNotEquals(ContentId.parse("minecraft:stone"), ContentId.parse("farmersdelight:stone"));
        assertFalse(ContentId.parse("minecraft:stone").equals(null));
    }

    @Test
    void toStringIsTheCanonicalValue() {
        assertEquals("farmersdelight:tools/knives", ContentId.parse("farmersdelight:tools/knives").toString());
        assertTrue(ContentId.parse("farmersdelight:tools/knives").value().startsWith("farmersdelight:"));
    }
}
