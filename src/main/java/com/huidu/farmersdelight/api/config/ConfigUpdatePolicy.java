package com.huidu.farmersdelight.api.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public List<ConfigKeyRename> migrations() {
        return migrations;
    }

    public List<String> retiredKeys() {
        return retiredKeys;
    }

    public List<String> registrySections() {
        return registrySections;
    }

    public static final class Builder {

        private final List<ConfigKeyRename> migrations = new ArrayList<>();
        private final List<String> retiredKeys = new ArrayList<>();
        private final List<String> registrySections = new ArrayList<>();

        private Builder() {
        }

        public Builder migrate(String oldPath, String newPath) {
            migrations.add(new ConfigKeyRename(oldPath, newPath));
            return this;
        }

        public Builder retire(String... paths) {
            for (String path : paths) {
                retiredKeys.add(Objects.requireNonNull(path, "path"));
            }
            return this;
        }

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
