package com.huidu.farmersdelight.api.tag;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the knife-tag query: the tag match itself, the safe answers, and the "no definition, no action"
 * guard that keeps a future Java drop path from firing on blocks CraftEngine already handles.
 */
class KnifeMineableBlocksTest {

    private static final Key PACK_TAG = Key.of(FarmersDelightTags.BLOCK_MINEABLE_WITH_KNIFE);
    private static final Key COMMON_TAG = Key.of(FarmersDelightTags.COMMON_MINEABLE_WITH_KNIFE);

    @AfterEach
    void reset() {
        KnifeMineableBlocks.setSource(null);
    }

    @Test
    void bothKnifeTagsAreRecognised() {
        assertTrue(KnifeMineableBlocks.matchesKnifeTag(Set.of(PACK_TAG)));
        assertTrue(KnifeMineableBlocks.matchesKnifeTag(Set.of(COMMON_TAG)));
        assertTrue(KnifeMineableBlocks.matchesKnifeTag(Set.of(Key.of("minecraft:mineable/axe"), PACK_TAG)));
    }

    @Test
    void otherTagsAndEmptySetsAreNotKnifeMineable() {
        assertFalse(KnifeMineableBlocks.matchesKnifeTag(Set.of(Key.of("minecraft:mineable/axe"))));
        assertFalse(KnifeMineableBlocks.matchesKnifeTag(Set.of()));
        assertFalse(KnifeMineableBlocks.matchesKnifeTag(null));
        Set<Key> withNull = new HashSet<>();
        withNull.add(null);
        assertFalse(KnifeMineableBlocks.matchesKnifeTag(withNull), "a null entry is not a tag match");
    }

    @Test
    void aBlockWithoutADefinitionOrANullIdIsNeverKnifeMineable() {
        KnifeMineableBlocks.setSource(blockId -> Set.of());

        assertFalse(KnifeMineableBlocks.isKnifeMineable(null), "a null id cannot be a definition");
        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:not_a_block")),
                "no definition means no declared tags, so the Java path must not act");
    }

    @Test
    void aDeclaredTagIsReportedForTheDefinitionThatDeclaresIt() {
        KnifeMineableBlocks.setSource(blockId -> "farmersdelight:straw_bale".equals(blockId.toString())
                ? Set.of(PACK_TAG)
                : Set.of());

        assertTrue(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")));
        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:cooked_rice")));
    }

    @Test
    void aFailingLookupAnswersFalseInsteadOfThrowing() {
        KnifeMineableBlocks.setSource(blockId -> {
            throw new IllegalStateException("CraftEngine is reloading");
        });

        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")),
                "a definition lookup that is not available yet must not break the caller");
    }

    @Test
    void theDefaultSourceIsRestoredByANullSource() {
        KnifeMineableBlocks.setSource(blockId -> Set.of(PACK_TAG));
        KnifeMineableBlocks.setSource(null);
        // After the reset the production lookup is back, and without CraftEngine it cannot answer, which has
        // to read as false. A source that was not actually reset would still report the stub's pack tag, so
        // asking about a real id (not null, which short-circuits before the source is read) is what bites.
        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")),
                "resetting the source has to go back to the production lookup instead of keeping the stub");
    }
}
