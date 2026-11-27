package com.huidu.farmersdelight.startup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the startup banner line, the version floor and the once-only warning. Losing the plugin or
 * CraftEngine version, accepting a version below the floor, or warning twice has to fail here.
 */
class StartupVersionBannerTest {

    @Test
    void theBannerNamesThePluginAndCraftEngineVersions() {
        String banner = StartupVersionBanner.format("1.0.3", "26.9.2");

        assertEquals("FarmersDelight 1.0.3 | CraftEngine 26.9.2", banner);
        assertTrue(banner.startsWith("FarmersDelight "), "the plugin name has to lead the line");
        assertTrue(banner.contains(" | CraftEngine "), "the CraftEngine field is what makes a jar mix visible");
        assertEquals(2, banner.split(" \\| ").length, "the boot line stays down to two fields");
        assertFalse(banner.contains("server "), "the server version is visible from the server itself");
        assertFalse(banner.contains("api "), "the plugin API version is not boot-line material");
    }

    @Test
    void missingVersionsBecomeUnknownInsteadOfBeingDropped() {
        String banner = StartupVersionBanner.format(null, "  ");

        assertEquals("FarmersDelight unknown | CraftEngine unknown", banner);
        assertEquals(2, banner.split(" \\| ").length, "both fields stay present");
    }

    @Test
    void versionsAtOrAboveTheFloorAreVerified() {
        assertTrue(StartupVersionBanner.isVerified("26.8.2"), "the floor itself is verified");
        assertTrue(StartupVersionBanner.isVerified("26.9.1"));
        assertTrue(StartupVersionBanner.isVerified("26.9.2"));
        assertTrue(StartupVersionBanner.isVerified("26.10-SNAPSHOT"), "a snapshot suffix is tolerated");
        assertTrue(StartupVersionBanner.isVerified("27.0.0"), "newer majors are not a warning");
    }

    @Test
    void olderOrUnreadableVersionsAreNotVerified() {
        assertFalse(StartupVersionBanner.isVerified("26.7.4"));
        assertFalse(StartupVersionBanner.isVerified("26.8.1"));
        assertFalse(StartupVersionBanner.isVerified("unknown"));
        assertFalse(StartupVersionBanner.isVerified(null));
        assertFalse(StartupVersionBanner.isVerified(""));
        assertNull(StartupVersionBanner.parse(null));
        assertNull(StartupVersionBanner.parse("unknown"), "a word is not a version");
    }

    @Test
    void anUnsupportedVersionWarnsExactlyOnce() {
        List<String> warnings = new ArrayList<>();
        StartupVersionBanner banner = new StartupVersionBanner(warnings::add);

        assertTrue(banner.warnIfUnsupported("26.7.4"), "an old version has to warn");
        assertFalse(banner.warnIfUnsupported("26.7.4"), "and never warn twice");
        assertFalse(banner.warnIfUnsupported("unknown"), "not even for another bad value");
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("26.8.2"), "the warning names the verified floor");
        assertTrue(banner.hasWarned());
    }

    @Test
    void anUnreadableVersionWarnsOnceAndASupportedOneNeverWarns() {
        List<String> warnings = new ArrayList<>();
        StartupVersionBanner banner = new StartupVersionBanner(warnings::add);

        assertTrue(banner.warnIfUnsupported(null), "a version we cannot read is a warning");
        assertFalse(banner.warnIfUnsupported("26.9.2"));
        assertEquals(1, warnings.size());

        List<String> quiet = new ArrayList<>();
        StartupVersionBanner supported = new StartupVersionBanner(quiet::add);
        assertFalse(supported.warnIfUnsupported("26.9.2"));
        assertFalse(supported.warnIfUnsupported("26.10-SNAPSHOT"));
        assertTrue(quiet.isEmpty(), "a supported or newer version must stay silent");
        assertFalse(supported.hasWarned());
    }

    /**
     * The wiring: the versions ride on the enable-time summary line, and the single version warning is asked for
     * while the plugin enables. Asserted on the sources, because the only other way to see this is a live server.
     */
    @Test
    void theSummaryLineCarriesTheVersionsAndTheOneWarningStays() throws Exception {
        String summary = readSource("StartupSummary.java");
        assertEquals(1, summary.split("StartupVersionBanner\\.format\\(", -1).length - 1,
                "the summary line builds the version fields exactly once");
        assertTrue(summary.contains("\"banner\""), "and passes them into the line");
        assertTrue(summary.contains("logInfo(\"plugin.enabled_summary\""),
                "the boot line is the short enabled summary");
        assertTrue(summary.contains("logDetail(DebugCategory.STARTUP, key"),
                "the counts stay under the startup debug category");
        assertFalse(summary.contains("logInfo(key"), "the counts are never printed at INFO");
        assertTrue(summary.contains("ToolRegistry.all().size()"), "the tool count comes from the registry");

        String plugin = readSource("FarmersDelightPlugin.java");
        assertEquals(1, plugin.split("warnIfUnsupported\\(", -1).length - 1,
                "the version floor warning is asked for exactly once, while the plugin enables");
        assertFalse(plugin.contains("getLogger().info(StartupVersionBanner"),
                "no second log line: the versions ride on the existing summary line");
        assertTrue(plugin.contains("resolveCraftEngineVersion()"),
                "the CraftEngine version comes from its API or its description, never from a literal");
    }

    /**
     * The boot line only carries the versions; the counts are debug detail. Both templates have to agree with
     * that split in every shipped language.
     */
    @Test
    void theBootLineIsShortAndTheCountsAreDetail() throws Exception {
        for (String locale : new String[]{"lang/en_us.yml", "lang/zh_cn.yml"}) {
            String language = readSource(locale);
            String boot = language.lines().filter(text -> text.trim().startsWith("enabled_summary:"))
                    .findFirst().orElse(null);
            assertNotNull(boot, "enabled_summary is missing from " + locale);
            assertTrue(boot.contains("{banner}"), locale + " boot line has to carry the versions: " + boot);
            for (String count : new String[]{"{cooking_pot}", "{cutting_board}", "{drop_rules}",
                    "{advancements}", "{tools}"}) {
                assertFalse(boot.contains(count), locale + " boot line must not carry " + count + ": " + boot);
            }

            String summary = language.lines().filter(text -> text.trim().startsWith("content_detail:"))
                    .findFirst().orElse(null);
            assertNotNull(summary, "content_detail is missing from " + locale);
            for (String field : new String[]{"{cooking_pot}", "{cutting_board}", "{drop_rules}",
                    "{advancements}", "{tools}"}) {
                assertTrue(summary.contains(field), locale + " detail line has to carry " + field + ": " + summary);
            }
            assertFalse(summary.contains("{banner}"), locale + " detail line repeats no versions: " + summary);
        }
    }

    /**
     * Startup noise stays under the startup detail category: the tool count, the settings dump, the active locale
     * and the block state occupancy are not INFO lines.
     */
    @Test
    void theNoiseLinesMovedUnderTheStartupCategory() throws Exception {
        String tools = readSource("tool/ToolRegistry.java");
        assertFalse(tools.contains("logInfo(\"plugin.tool.loaded\""),
                "the tool count must not be a line of its own");
        assertTrue(tools.contains("logDetail(DebugCategory.STARTUP, \"plugin.tool.loaded\""),
                "it stays reachable under the startup category");

        String plugin = readSource("FarmersDelightPlugin.java");
        assertFalse(plugin.contains("logInfo(\"plugin.config_summary\""), "the settings dump is not INFO");
        assertTrue(plugin.contains("logDetail(DebugCategory.STARTUP, \"plugin.config_summary\""));

        String i18n = readSource("i18n/I18n.java");
        assertFalse(i18n.contains("logInfo(\"i18n.loaded\""), "the active locale is not a line of its own");

        String usage = readSource("compat/CraftEngineStateUsageMonitor.java");
        assertFalse(usage.contains("plugin.getLogger().info(message)"), "state occupancy is not INFO");
        assertTrue(usage.contains("logDetailMessage(DebugCategory.STARTUP"), "it stays under the startup category");
    }

    private static String readSource(String relative) throws IOException {
        Path found = locate(relative);
        if (found == null) {
            throw new AssertionError("source has to be reachable from the test working directory: " + relative);
        }
        return Files.readString(found);
    }

    private static Path locate(String relative) {
        String[] prefixes = {"FarmersDelight/src/main/java/com/huidu/farmersdelight/",
                "src/main/java/com/huidu/farmersdelight/", "FarmersDelight/src/main/resources/",
                "src/main/resources/"};
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String prefix : prefixes) {
                Path candidate = cursor.resolve(prefix + relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            cursor = cursor.getParent();
        }
        return null;
    }
}
