package com.huidu.farmersdelight.api.config;

import java.util.Objects;

public record ConfigKeyRename(String oldPath, String newPath) {

    public ConfigKeyRename {
        Objects.requireNonNull(oldPath, "oldPath");
        Objects.requireNonNull(newPath, "newPath");
    }
}
