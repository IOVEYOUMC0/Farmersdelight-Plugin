package com.huidu.farmersdelight.api.config;

import java.util.List;

public final class ConfigUpdateReport {

    private final List<ConfigKeyRename> migratedKeys;
    private final List<String> retiredKeys;
    private final int addedKeys;
    private final String backupError;
    private final int fromVersion;
    private final int toVersion;

    ConfigUpdateReport(List<ConfigKeyRename> migratedKeys, List<String> retiredKeys, int addedKeys) {
        this(migratedKeys, retiredKeys, addedKeys, null, 0, 0);
    }

    ConfigUpdateReport(List<ConfigKeyRename> migratedKeys, List<String> retiredKeys, int addedKeys,
                       String backupError) {
        this(migratedKeys, retiredKeys, addedKeys, backupError, 0, 0);
    }

    ConfigUpdateReport(List<ConfigKeyRename> migratedKeys, List<String> retiredKeys, int addedKeys,
                       String backupError, int fromVersion, int toVersion) {
        this.migratedKeys = List.copyOf(migratedKeys);
        this.retiredKeys = List.copyOf(retiredKeys);
        this.addedKeys = addedKeys;
        this.backupError = backupError;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    public List<ConfigKeyRename> migratedKeys() {
        return migratedKeys;
    }

    public List<String> retiredKeys() {
        return retiredKeys;
    }

    public int addedKeys() {
        return addedKeys;
    }

    /** The config-version found on disk before the update (0 when the file carried none). */
    public int fromVersion() {
        return fromVersion;
    }

    /** The config-version this build ships, stamped onto the file after a successful update. */
    public int toVersion() {
        return toVersion;
    }

    public boolean versionChanged() {
        return fromVersion != toVersion;
    }

    /**
     * True when the file on disk declares a NEWER version than this build ships, i.e. the server was
     * downgraded. Key-level diffing cannot see this, and silently "adding missing keys" to a
     * newer-generation file is how a downgrade corrupts a config.
     */
    public boolean downgraded() {
        return fromVersion > toVersion;
    }

    public boolean changed() {
        return !migratedKeys.isEmpty() || !retiredKeys.isEmpty() || addedKeys > 0 || versionChanged();
    }

    public String backupError() {
        return backupError;
    }
}
