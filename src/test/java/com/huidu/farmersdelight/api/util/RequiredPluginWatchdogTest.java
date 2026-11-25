package com.huidu.farmersdelight.api.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared watchdog must match dependency names exactly and default to the family's hard dependencies. */
class RequiredPluginWatchdogTest {

    @Test
    void matchesOnlyTheConfiguredNames() {
        Set<String> required = Set.of("FarmersDelight", "CraftEngine");
        assertTrue(RequiredPluginWatchdog.requires(required, "FarmersDelight"));
        assertTrue(RequiredPluginWatchdog.requires(required, "CraftEngine"));
        assertFalse(RequiredPluginWatchdog.requires(required, "farmersdelight"));
        assertFalse(RequiredPluginWatchdog.requires(required, "OtherPlugin"));
        assertFalse(RequiredPluginWatchdog.requires(required, null));
        assertFalse(RequiredPluginWatchdog.requires(null, "FarmersDelight"));
    }
}
