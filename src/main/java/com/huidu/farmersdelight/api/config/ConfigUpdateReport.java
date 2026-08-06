package com.huidu.farmersdelight.api.config;

import java.util.List;

public final class ConfigUpdateReport {

    private final List<ConfigKeyRename> migratedKeys;
    private final List<String> retiredKeys;
    private final int addedKeys;
    private final String backupError;

    ConfigUpdateReport(List<ConfigKeyRename> migratedKeys, List<String> retiredKeys, int addedKeys) {
        this(migratedKeys, retiredKeys, addedKeys, null);
    }

    ConfigUpdateReport(List<ConfigKeyRename> migratedKeys, List<String> retiredKeys, int addedKeys,
                       String backupError) {
        this.migratedKeys = List.copyOf(migratedKeys);
        this.retiredKeys = List.copyOf(retiredKeys);
        this.addedKeys = addedKeys;
        this.backupError = backupError;
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

    public boolean changed() {
        return !migratedKeys.isEmpty() || !retiredKeys.isEmpty() || addedKeys > 0;
    }

    public String backupError() {
        return backupError;
    }
}
