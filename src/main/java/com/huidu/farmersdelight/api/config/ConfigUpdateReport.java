package com.huidu.farmersdelight.api.config;

import java.util.List;

/**
 * What one config update did, as counts and paths. The update itself never logs and never writes a file:
 * the caller decides what to say about the result, in its own wording and its own language files, and
 * decides whether the change is worth rewriting the file for.
 */
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

    /** Renames that actually moved a value, in the order they were applied. */
    public List<ConfigKeyRename> migratedKeys() {
        return migratedKeys;
    }

    /** Retired paths that were present and have been deleted, in the order they were listed. */
    public List<String> retiredKeys() {
        return retiredKeys;
    }

    /** Number of value keys the merge added. Sections are not counted; only the values inside them are. */
    public int addedKeys() {
        return addedKeys;
    }

    /** True when anything at all changed, which is the condition for writing the file back. */
    public boolean changed() {
        return !migratedKeys.isEmpty() || !retiredKeys.isEmpty() || addedKeys > 0;
    }

    /**
     * Why the copy taken before the rewrite could not be written, or null when it was written or was not needed.
     * A failed copy does not stop the update, so this is reported rather than thrown; a caller that wants the
     * operator to know it lost the safety net logs it.
     */
    public String backupError() {
        return backupError;
    }
}
