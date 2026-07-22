package com.huidu.farmersdelight.api.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The per-plugin data a config update runs against: which paths were renamed, which are no longer read,
 * and which sections list content rather than settings.
 *
 * The machinery in ConfigFileUpdater carries no knowledge of any particular config file; every plugin
 * that uses it supplies its own tables here. A policy is immutable once built and can be reused for as
 * many files as the plugin bootstraps, so a plugin that keeps one policy per file keeps the differences
 * between those files in one readable place.
 */
public final class ConfigUpdatePolicy {

    private final List<ConfigKeyRename> migrations;
    private final List<String> retiredKeys;
    private final List<String> registrySections;

    private ConfigUpdatePolicy(Builder builder) {
        this.migrations = List.copyOf(builder.migrations);
        this.retiredKeys = List.copyOf(builder.retiredKeys);
        this.registrySections = List.copyOf(builder.registrySections);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Renames to apply, in the order they were added. */
    public List<ConfigKeyRename> migrations() {
        return migrations;
    }

    /** Paths to delete from the operator's file because nothing reads them any more. */
    public List<String> retiredKeys() {
        return retiredKeys;
    }

    /**
     * Sections whose children are content entries rather than fixed settings. Deleting an entry there is
     * how an operator disables it, so the merge only creates such a section when the operator's file has
     * none of it yet, and never fills in individual entries afterwards.
     */
    public List<String> registrySections() {
        return registrySections;
    }

    public static final class Builder {

        private final List<ConfigKeyRename> migrations = new ArrayList<>();
        private final List<String> retiredKeys = new ArrayList<>();
        private final List<String> registrySections = new ArrayList<>();

        private Builder() {
        }

        /**
         * Adds a rename. Order is load-bearing: entries are applied in the order they are added, which is
         * what lets one path be carried across two renames in the same pass.
         */
        public Builder migrate(String oldPath, String newPath) {
            migrations.add(new ConfigKeyRename(oldPath, newPath));
            return this;
        }

        /** Adds paths that are deleted from the operator's file because no code reads them any more. */
        public Builder retire(String... paths) {
            for (String path : paths) {
                retiredKeys.add(Objects.requireNonNull(path, "path"));
            }
            return this;
        }

        /** Adds sections the merge must treat as content registries rather than as settings. */
        public Builder registrySection(String... paths) {
            for (String path : paths) {
                registrySections.add(Objects.requireNonNull(path, "path"));
            }
            return this;
        }

        public ConfigUpdatePolicy build() {
            return new ConfigUpdatePolicy(this);
        }
    }
}
