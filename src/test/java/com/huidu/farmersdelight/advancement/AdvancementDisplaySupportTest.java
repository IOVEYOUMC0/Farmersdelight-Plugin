package com.huidu.farmersdelight.advancement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementDisplaySupportTest {

    @Test
    void theCompileDependencyCarriesTheComponentConstructor() {
        // The plugin builds component displays unconditionally here; if this ever fails, the jar in libs/ was
        // replaced by a build without the fork's component constructor and the English fallback is all that runs.
        assertTrue(AdvancementDisplaySupport.isComponentDisplaySupported());
    }

    @Test
    void aDisplayClassWithoutTheConstructorIsUnsupported() {
        assertFalse(AdvancementDisplaySupport.hasComponentConstructor(String.class),
                "a class without the component constructor must fall back to text");
    }
}
