package com.huidu.farmersdelight.api.config;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The console lines a config update reports, in one place.
 */
public final class ConfigUpdateNotices {

    /** One console line: the level it is logged at, the message key, and that key's arguments in order. */
    public record Notice(boolean warning, String key, List<Object> args) {
    }

    private static final String KEY_BACKUP_FAILED = "plugin.config_backup_failed";
    private static final String KEY_MIGRATED = "plugin.config_key_migrated";
    private static final String KEY_RETIRED = "plugin.config_keys_retired";
    private static final String KEY_ADDED = "plugin.config_keys_added";

    private ConfigUpdateNotices() {
    }

    /** The backup that failed before the file was rewritten. */
    public static Notice backupFailed(String file, String error) {
        return new Notice(true, KEY_BACKUP_FAILED, List.of("file", file, "error", error));
    }

    /** One key moved from its old path to its new one. */
    public static Notice migrated(String oldPath, String newPath) {
        return new Notice(false, KEY_MIGRATED, List.of("old", oldPath, "new", newPath));
    }

    /** One key moved, named by the rename the updater reported. */
    public static Notice migrated(ConfigKeyRename rename) {
        return migrated(rename.oldPath(), rename.newPath());
    }

    /** The keys a newer build no longer reads, named in one line. */
    public static Notice retired(List<String> keys) {
        return new Notice(false, KEY_RETIRED, List.of("count", keys.size(), "keys", String.join(", ", keys)));
    }

    /** The settings a newer build added to a file while keeping every existing value. */
    public static Notice added(String file, int count) {
        return new Notice(false, KEY_ADDED, List.of("file", file, "count", count));
    }

    /** Every notice one update report is worth, in the order they are logged. */
    public static List<Notice> notices(String file, ConfigUpdateReport report) {
        List<Notice> notices = new ArrayList<>();
        if (report.backupError() != null) {
            notices.add(backupFailed(file, report.backupError()));
        }
        for (ConfigKeyRename rename : report.migratedKeys()) {
            notices.add(migrated(rename));
        }
        if (!report.retiredKeys().isEmpty()) {
            notices.add(retired(report.retiredKeys()));
        }
        if (report.addedKeys() > 0) {
            notices.add(added(file, report.addedKeys()));
        }
        return List.copyOf(notices);
    }

    /** Logs one notice through the family's single console channel. */
    public static void log(Plugin plugin, Notice notice) {
        Logger logger = plugin == null ? null : plugin.getLogger();
        if (logger == null) {
            return;
        }
        String message = FarmersDelightApi.consoleMessage(notice.key(), notice.args().toArray());
        if (notice.warning()) {
            logger.warning(message);
        } else {
            logger.info(message);
        }
    }

    /** Logs everything one report is worth. */
    public static void logAll(Plugin plugin, String file, ConfigUpdateReport report) {
        for (Notice notice : notices(file, report)) {
            log(plugin, notice);
        }
    }
}
