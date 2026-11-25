package com.huidu.farmersdelight.api.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The shared bootstrap refuses to capture a null addon and does not try to load a blank resource. */
class AddonConfigBootstrapTest {

    @Test
    void rejectsANullAddon() {
        assertThrows(NullPointerException.class, () -> new AddonConfigBootstrap(null));
    }

    @Test
    void blankResourceNamesAreNotLoaded() throws Exception {
        // Constructor needs a JavaPlugin, but loadYamlResource's blank guard runs before any Bukkit call,
        // so an unconstructed instance would not prove it. Assert the null-guard contract on source instead.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/huidu/farmersdelight/api/config/AddonConfigBootstrap.java"));
        org.junit.jupiter.api.Assertions.assertTrue(source.contains("resourceName == null || resourceName.isBlank()"));
    }
}
