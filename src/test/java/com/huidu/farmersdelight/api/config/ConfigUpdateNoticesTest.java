package com.huidu.farmersdelight.api.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The notices one config update is worth. The expected values are the four lines every plugin in the family
 * wrote out by hand before the sequence moved here: a failed backup at warning level, one line per migrated
 * key, one line naming the retired keys, and one line for the added count — each only when it has something
 * to say.
 */
class ConfigUpdateNoticesTest {

    private static final String FILE = "config.yml";

    @Test
    void theNoticesAreTheLinesThePluginsWroteOut() {
        ConfigUpdateReport report = new ConfigUpdateReport(
                List.of(new ConfigKeyRename("old.one", "new.one"), new ConfigKeyRename("old.two", "new.two")),
                List.of("retired.one", "retired.two"), 3, "disk full");

        assertEquals(List.of(
                        ConfigUpdateNotices.backupFailed(FILE, "disk full"),
                        ConfigUpdateNotices.migrated("old.one", "new.one"),
                        ConfigUpdateNotices.migrated("old.two", "new.two"),
                        ConfigUpdateNotices.retired(List.of("retired.one", "retired.two")),
                        ConfigUpdateNotices.added(FILE, 3)),
                ConfigUpdateNotices.notices(FILE, report));
    }

    @Test
    void anUnchangedReportIsWorthNoNotice() {
        ConfigUpdateReport report = new ConfigUpdateReport(List.of(), List.of(), 0);

        assertFalse(report.changed(), "nothing moved, nothing was retired and nothing was added");
        assertTrue(ConfigUpdateNotices.notices(FILE, report).isEmpty());
    }

    @Test
    void eachLineKeepsItsLevelKeyAndArguments() {
        assertEquals(new ConfigUpdateNotices.Notice(true, "plugin.config_backup_failed",
                        List.of("file", FILE, "error", "disk full")),
                ConfigUpdateNotices.backupFailed(FILE, "disk full"));
        assertEquals(new ConfigUpdateNotices.Notice(false, "plugin.config_key_migrated",
                        List.of("old", "a", "new", "b")),
                ConfigUpdateNotices.migrated(new ConfigKeyRename("a", "b")));
        assertEquals(new ConfigUpdateNotices.Notice(false, "plugin.config_keys_retired",
                        List.of("count", 2, "keys", "x, y")),
                ConfigUpdateNotices.retired(List.of("x", "y")));
        assertEquals(new ConfigUpdateNotices.Notice(false, "plugin.config_keys_added",
                        List.of("file", FILE, "count", 7)),
                ConfigUpdateNotices.added(FILE, 7));
    }

    @Test
    void aCountOfZeroIsNotAnnounced() {
        ConfigUpdateReport retiredOnly = new ConfigUpdateReport(List.of(), List.of("gone"), 0);

        assertEquals(List.of(ConfigUpdateNotices.retired(List.of("gone"))),
                ConfigUpdateNotices.notices(FILE, retiredOnly));
    }

    @Test
    void aReportWithNoBackupErrorStartsWithItsFirstMigration() {
        ConfigUpdateReport report = new ConfigUpdateReport(
                List.of(new ConfigKeyRename("a", "b")), List.of(), 0);

        assertEquals(List.of(ConfigUpdateNotices.migrated("a", "b")),
                ConfigUpdateNotices.notices(FILE, report));
    }

    @Test
    void theOneConsoleChannelIsTheApiMessageResolver() throws IOException {
        String flat = Files.readString(Path.of("src", "main", "java", "com", "huidu", "farmersdelight", "api",
                        "config", "ConfigUpdateNotices.java"))
                .replaceAll("\\s+", " ");

        assertTrue(flat.contains("FarmersDelightApi.consoleMessage(notice.key(), notice.args().toArray())"),
                "every notice is formatted by the family's one console resolver");
        assertTrue(flat.contains("logger.warning(message)"), "a notice that warns is logged as a warning");
        assertTrue(flat.contains("logger.info(message)"), "and the rest are logged as info");
    }
}
