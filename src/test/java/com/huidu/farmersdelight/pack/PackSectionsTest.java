package com.huidu.farmersdelight.pack;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the group map out of a pack file is the layer above AdvancedTagGroups: it decides what the
 * declared shape means before anything gets resolved, so a pack written as a scalar, a list, or something else
 * entirely is handled here rather than surprising the resolver later.
 */
class PackSectionsTest {

    private static YamlConfiguration pack(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yaml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return configuration;
    }

    @Test
    void readsListsAndScalars() {
        YamlConfiguration configuration = pack("""
                advanced_tags:
                  meats:
                    - minecraft:beef
                    - minecraft:porkchop
                  single: minecraft:chicken
                """);
        Map<String, List<String>> out = new LinkedHashMap<>();

        PackSections.collectGroups(configuration, "advanced_tags", out);

        assertEquals(List.of("minecraft:beef", "minecraft:porkchop"), out.get("meats"));
        assertEquals(List.of("minecraft:chicken"), out.get("single"), "one member may be written without a list");
    }

    @Test
    void theFirstDeclarationOfAGroupWins() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        PackSections.collectGroups(pack("advanced_tags:\n  meats:\n    - minecraft:beef\n"), "advanced_tags", out);
        PackSections.collectGroups(pack("advanced_tags:\n  meats:\n    - minecraft:porkchop\n"), "advanced_tags", out);

        assertEquals(List.of("minecraft:beef"), out.get("meats"),
                "a later pack must not quietly replace an earlier group");
    }

    @Test
    void aValueThatIsNeitherAListNorAScalarIsSkipped() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        PackSections.collectGroups(pack("advanced_tags:\n  broken:\n    nested: minecraft:beef\n"), "advanced_tags", out);

        assertFalse(out.containsKey("broken"), "the shape is reported instead of guessed at");
    }

    @Test
    void aMissingSectionOrRootReadsNothing() {
        Map<String, List<String>> out = new LinkedHashMap<>();

        PackSections.collectGroups(null, "advanced_tags", out);
        PackSections.collectGroups(pack("something_else:\n  meats:\n    - minecraft:beef\n"), "advanced_tags", out);
        PackSections.collectGroups(pack("advanced_tags: minecraft:beef\n"), "advanced_tags", out);

        assertTrue(out.isEmpty());
    }

    @Test
    void theTagSectionIsClaimedOnItsOwn() {
        assertTrue(PackSection.ADVANCED_TAGS.separateClaim(),
                "a conflict on this id must not be able to disable the recipe sections");
        for (PackSection section : PackSection.values()) {
            if (section != PackSection.ADVANCED_TAGS) {
                assertFalse(section.separateClaim(), section + " belongs to the shared claim");
            }
        }
    }

    @Test
    void blankEntriesAreKeptForTheResolverToReject() {
        // Blanks are dropped while resolving, not while reading: the reader's job is to report the shape.
        Map<String, List<String>> out = new LinkedHashMap<>();
        PackSections.collectGroups(pack("advanced_tags:\n  meats:\n    - 'minecraft:beef'\n    - ''\n"), "advanced_tags", out);

        assertEquals(List.of("minecraft:beef", ""), out.get("meats"));
    }
}
