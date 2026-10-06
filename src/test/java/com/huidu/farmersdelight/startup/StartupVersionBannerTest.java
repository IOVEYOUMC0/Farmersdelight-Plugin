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
 * Locks the startup banner line, the version floor and the once-only warning. Losing any of the four banner
 * fields, accepting a version below the floor, or warning twice has to fail here.
 */
class StartupVersionBannerTest {

    @Test
    void theBannerNamesAllFourVersionsInAFixedOrder() {
        String banner = StartupVersionBanner.format("1.0.3", "26.9.2", "Paper 1.21.8", "1.21.5");

        assertEquals("FarmersDelight 1.0.3 | CraftEngine 26.9.2 | server Paper 1.21.8 | api 1.21.5", banner);
        assertTrue(banner.startsWith("FarmersDelight "), "the plugin name has to lead the line");
        assertTrue(banner.contains(" | CraftEngine "), "the CraftEngine field is what makes a jar mix visible");
        assertTrue(banner.contains(" | server "));
        assertTrue(banner.contains(" | api "));
    }

    @Test
    void missingVersionsBecomeUnknownInsteadOfBeingDropped() {
        String banner = StartupVersionBanner.format(null, "  ", null, "1.21.5");

        assertTrue(banner.contains("FarmersDelight unknown"));
        assertTrue(banner.contains("CraftEngine unknown"));
        assertTrue(banner.contains("server unknown"));
        assertTrue(banner.contains("api 1.21.5"));
        assertEquals(4, banner.split(" \\| ").length, "all four fields stay present");
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
                "the summary line builds the four version fields exactly once");
        assertTrue(summary.contains("\"banner\""), "and passes them into the line");
        assertTrue(summary.contains("ToolRegistry.all().size()"), "the tool count comes from the registry");

        String plugin = readSource("FarmersDelightPlugin.java");
        assertEquals(1, plugin.split("warnIfUnsupported\\(", -1).length - 1,
                "the version floor warning is asked for exactly once, while the plugin enables");
        assertFalse(plugin.contains("getLogger().info(StartupVersionBanner"),
                "no second log line: the versions ride on the existing summary line");
        assertTrue(plugin.contains("resolveCraftEngineVersion()"),
                "the CraftEngine version comes from its API or its description, never from a literal");
    }

    /** The line has to name the four versions and the counts that matter, in every shipped language. */
    @Test
    void theSummaryTemplatesCarryTheVersionsAndTheKeyCounts() throws Exception {
        for (String locale : new String[]{"lang/en_us.yml", "lang/zh_cn.yml"}) {
            String language = readSource(locale);
            String line = language.lines().filter(text -> text.trim().startsWith("content_summary:"))
                    .findFirst().orElse(null);
            assertNotNull(line, "content_summary is missing from " + locale);
            for (String field : new String[]{"{banner}", "{cooking_pot}", "{cutting_board}", "{drop_rules}",
                    "{advancements}", "{tools}", "-- "}) {
                assertTrue(line.contains(field), locale + " has to carry " + field + ": " + line);
            }
        }
    }

    /**
     * The startup noise is startup detail now: the tool count, the settings dump, the active locale and the block
     * state occupancy each used to be an INFO line of its own, which is what made one boot a dozen lines long.
     */
    @Test
    void theNoiseLinesMovedUnderTheStartupCategory() throws Exception {
        String tools = readSource("tool/ToolRegistry.java");
        assertFalse(tools.contains("logInfo(\"plugin.tool.loaded\""),
                "the tool count must not be a line of its own");
        assertTrue(tools.contains("logDetail(\"startup\", \"plugin.tool.loaded\""),
                "it stays reachable under the startup category");

        String plugin = readSource("FarmersDelightPlugin.java");
        assertFalse(plugin.contains("logInfo(\"plugin.config_summary\""), "the settings dump is not INFO");
        assertTrue(plugin.contains("logDetail(\"startup\", \"plugin.config_summary\""));

        String i18n = readSource("i18n/I18n.java");
        assertFalse(i18n.contains("logInfo(\"i18n.loaded\""), "the active locale is not a line of its own");

        String usage = readSource("compat/CraftEngineStateUsageMonitor.java");
        assertFalse(usage.contains("plugin.getLogger().info(message)"), "state occupancy is not INFO");
        assertTrue(usage.contains("logDetailMessage(\"startup\""), "it stays under the startup category");
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
