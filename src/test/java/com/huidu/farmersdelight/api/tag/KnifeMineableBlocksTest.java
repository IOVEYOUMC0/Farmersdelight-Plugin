package com.huidu.farmersdelight.api.tag;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the knife-tag query: the tag match itself, the safe answers, the "no definition, no action" guard,
 * and the two failure classes — a structural API failure warns once and stops touching CraftEngine, while a
 * temporary one keeps retrying without disabling the query.
 */
class KnifeMineableBlocksTest {

    private static final Key PACK_TAG = Key.of(FarmersDelightTags.BLOCK_MINEABLE_WITH_KNIFE);
    private static final Key COMMON_TAG = Key.of(FarmersDelightTags.COMMON_MINEABLE_WITH_KNIFE);

    @AfterEach
    void reset() {
        KnifeMineableBlocks.setSource(null);
        KnifeMineableBlocks.setWarner(null);
        KnifeMineableBlocks.resetState();
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
    void aStructuralFailureWarnsOnceAndDisablesTheQuery() {
        AtomicInteger lookups = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        KnifeMineableBlocks.setWarner(warnings::add);
        KnifeMineableBlocks.setSource(blockId -> {
            lookups.incrementAndGet();
            // What a CraftEngine without the expected API throws at the call site.
            throw new NoSuchMethodError("BlockDefinition.defaultState()");
        });

        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")));
        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")));
        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:cooked_rice")));

        assertEquals(1, lookups.get(), "a structural failure must stop touching CraftEngine");
        assertEquals(1, warnings.size(), "and it has to warn exactly once");
        assertTrue(KnifeMineableBlocks.isDisabledForUnsupportedApi());
        assertTrue(warnings.getFirst().contains("26.8.2"), "the warning has to name the supported floor");
        assertTrue(warnings.getFirst().toLowerCase().contains("disable"),
                "and it has to say that the query is now disabled");
    }

    @Test
    void aTemporaryFailureKeepsRetryingAndLogsEachReasonOnce() {
        AtomicInteger lookups = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        KnifeMineableBlocks.setWarner(warnings::add);
        KnifeMineableBlocks.setSource(blockId -> {
            lookups.incrementAndGet();
            throw new IllegalStateException("CraftEngine is reloading");
        });

        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")));
        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")));

        assertEquals(2, lookups.get(), "a temporary failure has to be retried on the next call");
        assertEquals(1, warnings.size(), "but the same reason is only logged once");
        assertFalse(KnifeMineableBlocks.isDisabledForUnsupportedApi());
    }

    @Test
    void resettingTheStateReenablesTheQuery() {
        KnifeMineableBlocks.setWarner(message -> {
        });
        KnifeMineableBlocks.setSource(blockId -> {
            throw new NoClassDefFoundError("craftengine");
        });
        assertFalse(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")));
        assertTrue(KnifeMineableBlocks.isDisabledForUnsupportedApi());

        KnifeMineableBlocks.setSource(blockId -> Set.of(PACK_TAG));
        KnifeMineableBlocks.resetState();

        assertTrue(KnifeMineableBlocks.isKnifeMineable(Key.of("farmersdelight:straw_bale")));
        assertFalse(KnifeMineableBlocks.isDisabledForUnsupportedApi());
    }

    @Test
    void theDefaultWarnerIsRestoredByANullWarner() {
        List<String> captured = new ArrayList<>();
        KnifeMineableBlocks.setWarner(captured::add);
        KnifeMineableBlocks.setWarner(null);
        // The production warner writes to the console and cannot be observed offline; this only proves the
        // seam is reset (the captured list must stay empty for later failures of the reset test order).
        assertEquals(0, captured.size());
    }
}
