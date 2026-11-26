package com.huidu.farmersdelight.api.compat.ce;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonPackSectionsTest {

    @Test
    void compatFacadeWrapsAnUnregisteredTestingClaim() {
        AddonPackSections claim = AddonPackSections.createForTesting(Map.of("recipes", "recipes"));
        assertFalse(claim.registered());
        assertFalse(claim.hasPublished());
        assertEquals(List.of("recipes"), claim.claimedIds());
        assertTrue(claim.sections("recipes").isEmpty());
        assertNotNull(claim.type());
        assertTrue(AddonPackSections.entries(claim, "recipes", "recipes", null).isEmpty());
    }
}
