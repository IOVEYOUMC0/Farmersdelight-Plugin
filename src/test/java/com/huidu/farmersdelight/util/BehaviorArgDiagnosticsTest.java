package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorArgDiagnosticsTest {

    private final List<String> warnings = new ArrayList<>();
    private final List<String> details = new ArrayList<>();

    @AfterEach
    void restoreChannels() {
        BehaviorArgDiagnostics.setWarner(null);
        BehaviorArgDiagnostics.setDetailer(null);
        BehaviorArgDiagnostics.resetReported();
    }

    private void capture() {
        BehaviorArgDiagnostics.setWarner(warnings::add);
        BehaviorArgDiagnostics.setDetailer(details::add);
        BehaviorArgDiagnostics.resetReported();
    }

    private static ConfigSection section(Map<String, Object> values) {
        return ConfigSection.of("behaviors", values);
    }

    @Test
    void anOptionThatResemblesAReadOneIsWarnedWithASuggestion() {
        capture();
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("open_while_sneaking", true);
        BehaviorArgDiagnostics.reportUnread(null, Key.of("farmersdelight:cooking_pot"),
                section(declared), Set.of("open-while-sneaking"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("open_while_sneaking"));
        assertTrue(warnings.get(0).contains("did you mean 'open-while-sneaking'?"), warnings.get(0));
        assertTrue(details.isEmpty(), "a typo must not be downgraded to the detail channel");
    }

    @Test
    void anOptionThatResemblesNothingIsOnlyLoggedAtDetailLevel() {
        capture();
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("something_this_version_never_had", 3);
        BehaviorArgDiagnostics.reportUnread(null, Key.of("farmersdelight:rope"), section(declared),
                Set.of("connection-mode"));
        assertTrue(warnings.isEmpty());
        assertEquals(1, details.size());
        assertTrue(details.get(0).contains("something_this_version_never_had"));
    }

    @Test
    void anOptionThatWasReadIsNeverReported() {
        capture();
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("eject", true);
        declared.put("transfer-cooldown", 20);
        BehaviorArgDiagnostics.reportUnread(null, Key.of("farmersdelight:basket"), section(declared),
                Set.of("eject", "transfer-cooldown"));
        assertTrue(warnings.isEmpty());
        assertTrue(details.isEmpty());
    }

    @Test
    void eachOptionIsReportedOncePerRun() {
        capture();
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("open_while_sneaking", true);
        for (int i = 0; i < 3; i++) {
            BehaviorArgDiagnostics.reportUnread(null, Key.of("farmersdelight:cooking_pot"),
                    section(declared), Set.of("open-while-sneaking"));
        }
        assertEquals(1, warnings.size(), "a reload or a second block must not repeat the warning");
    }

    @Test
    void anEmptyOrAbsentSectionReportsNothing() {
        capture();
        BehaviorArgDiagnostics.reportUnread(null, Key.of("farmersdelight:basket"), null, Set.of());
        BehaviorArgDiagnostics.reportUnread(null, Key.of("farmersdelight:basket"), section(Map.of()), Set.of());
        assertTrue(warnings.isEmpty());
        assertTrue(details.isEmpty());
    }

    @Test
    void theSuggestionSearchIsBoundedToNearMisses() {
        assertEquals("open-while-sneaking", BehaviorArgDiagnostics.closest("open_while_sneaking",
                Set.of("open-while-sneaking", "place-tray-on-open")));
        assertNull(BehaviorArgDiagnostics.closest("totally-different-option", Set.of("eject", "transfer-cooldown")));
        assertEquals(1, BehaviorArgDiagnostics.distance("eject", "eject2"));
        assertEquals(0, BehaviorArgDiagnostics.distance("same", "same"));
    }
}
