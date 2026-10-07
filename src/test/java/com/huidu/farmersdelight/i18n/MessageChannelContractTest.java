package com.huidu.farmersdelight.i18n;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One event, one visible line. A reload started by the command answers its sender with the styled receipt,
 * so the console line for the same phase has to drop to the detail switch; a reload started anywhere else
 * (the public reload hook, the recipe editor) has no receipt and keeps its console line.
 *
 * Player-visible text has to come from the language files, and the detail switch has to stay invisible by
 * default. All three rules are about the source shape, so they are pinned on the source text.
 */
class MessageChannelContractTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "huidu", "farmersdelight");
    private static final Path PLUGIN = MAIN.resolve("FarmersDelightPlugin.java");
    private static final Path RELOAD_COMMAND = MAIN.resolve("command").resolve("ReloadSubCommand.java");
    private static final Path DEBUG_TOOLS = MAIN.resolve("command").resolve("DebugToolsSubCommand.java");
    private static final Path I18N = MAIN.resolve("i18n").resolve("I18n.java");

    private static final List<String> RELOAD_PHASE_KEYS = List.of(
            "plugin.configuration_reloaded",
            "plugin.main_configuration_reloaded",
            "plugin.gui_configuration_reloaded",
            "plugin.language_files_reloaded",
            "plugin.recipe_files_reloaded",
            "plugin.advancement_data_reloaded",
            "plugin.tags_reloaded");

    private static String flat(Path path) throws IOException {
        return Files.readString(path).replaceAll("\\s+", " ");
    }

    @Test
    void aCommandStartedReloadCannotReportTheSameEventTwice() throws IOException {
        String plugin = flat(PLUGIN);
        for (String key : RELOAD_PHASE_KEYS) {
            assertFalse(plugin.contains("I18n.logInfo(\"" + key + "\")"),
                    key + " must not go straight to the console: a command-started pass already told its sender");
            assertTrue(plugin.contains("logReloadEvent(\"" + key + "\"")
                            || plugin.contains("logReloadEvent(\"" + key + "\", "),
                    key + " has to be reported through the pass-aware reporter");
        }
        assertTrue(plugin.contains("private void logReloadEvent(String key, Object... args) {"),
                "the reporter that decides between receipt and console has to exist");
        assertTrue(plugin.contains("if (reloadDrivenByCommand) { I18n.logDetail(\"reload\", key, args); }"),
                "a command-started pass reports on the detail channel, which is off by default");
        assertTrue(plugin.contains("I18n.logInfo(key, args);"),
                "a pass started elsewhere keeps its visible console line");
        assertTrue(plugin.contains("reloadDrivenByCommand = true;"),
                "beginReloadPass marks the pass the command started");
        assertTrue(plugin.contains("public void endReloadPass() { reloadDrivenByCommand = false; }"),
                "the flag has to be cleared when the pass is over");

        String command = flat(RELOAD_COMMAND);
        assertTrue(command.contains("plugin.endReloadPass();"),
                "the command clears the flag in its finally block");
        assertTrue(command.contains("I18n.getComponent(\"general.config_reloaded\""),
                "the sender still gets the styled receipt, which is the one visible line for the event");
        assertTrue(command.contains("I18n.getComponent(\"command.reload_report\""),
                "the timing report stays with the receipt");
    }

    @Test
    void noVisibleTextIsHardcoded() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(path);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.contains("sendMessage(\"") || line.contains("sendMessage(MINI.deserialize(\"<")) {
                        offenders.add(MAIN.relativize(path) + ":" + (index + 1));
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "player-visible text has to come from the language files: " + offenders);
        String debugTools = flat(DEBUG_TOOLS);
        assertTrue(debugTools.contains("I18n.getComponent(\"command.debug_tools_failed\")"),
                "the debug-tools execution failure reports through its language key");
        assertFalse(debugTools.contains("<red>Debug tools are not available in this build.</red>"),
                "its misleading hardcoded English is gone");
    }

    @Test
    void theDetailChannelStaysInvisibleByDefault() throws IOException {
        String i18n = flat(I18N);
        assertTrue(i18n.contains("if (category != null && pluginInstance.isDebugEnabled(category)) {"
                        + " pluginInstance.getLogger().info(message); } else {"
                        + " pluginInstance.getLogger().fine(message); }"),
                "logDetailMessage prints at INFO only while the category's switch is on, and at FINE otherwise");
        assertTrue(i18n.contains("public static void logDetail(String category, String key, Object... args) {"
                        + " logDetailMessage(category, formatConsole(key, args)); }"),
                "logDetail is the entry point that keeps the detail lines behind that switch");
    }

    @Test
    void theMigratedWarningsComeFromTheLanguageFiles() throws IOException {
        String plugin = flat(PLUGIN);
        assertFalse(plugin.contains("Backstab enchantment disabled: another enchantment plugin is present"),
                "the backstab warning is a language entry now");
        assertEquals(2, count(plugin, "I18n.logWarning(\"plugin.backstab_disabled_conflict\", \"conflict\", conflict)"),
                "both backstab call sites report through the same key");

        String updater = flat(MAIN.resolve("api/config/ConfigFileUpdater.java"));
        assertFalse(updater.contains("Leaving it untouched."),
                "the config-version warning is a language entry now");
        assertTrue(updater.contains("I18n.logWarning(\"plugin.config_version_downgraded\", \"from\", "
                        + "report.fromVersion(), \"to\", report.toVersion())"),
                "it reports through its key with named placeholders");

        String resources = flat(MAIN.resolve("api/resource/CraftEngineResources.java"));
        assertFalse(resources.contains("Failed to release CraftEngine resources for "),
                "the resource-release warning is a language entry now");
        assertTrue(resources.contains("I18n.logWarning(\"plugin.craftengine_release_failed\", \"namespace\", "
                        + "namespace, \"error\", e.getMessage())"),
                "it reports through its key with named placeholders");

        for (String name : List.of("en_us.yml", "zh_cn.yml")) {
            Map<String, String> values = leaves(Path.of("src", "main", "resources", "lang", name));
            for (String key : List.of("console.plugin.config_version_downgraded",
                    "console.plugin.craftengine_release_failed", "console.plugin.backstab_disabled_conflict")) {
                assertTrue(values.containsKey(key), name + " is missing " + key);
            }
        }
    }

    private static Map<String, String> leaves(Path file) throws IOException {
        Map<String, String> flat = new LinkedHashMap<>();
        try (InputStream input = Files.newInputStream(file)) {
            flatten("", new Yaml().load(input), flat);
        }
        return flat;
    }

    private static void flatten(String trail, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = trail.isEmpty() ? String.valueOf(entry.getKey()) : trail + "." + entry.getKey();
                flatten(key, entry.getValue(), out);
            }
        } else if (node != null) {
            out.put(trail, String.valueOf(node));
        }
    }

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }
}
