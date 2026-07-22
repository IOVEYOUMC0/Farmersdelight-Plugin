package com.huidu.farmersdelight.api.config;

import java.util.Objects;

/**
 * One renamed configuration path. The value at the old path is moved to the new path, child sections
 * included, and the old path is then cleared.
 *
 * Renames are applied in the order the policy lists them, so two entries can chain: an entry that moves
 * a to b followed by an entry that moves b to c carries a file still using a all the way to c in a single
 * pass.
 */
public record ConfigKeyRename(String oldPath, String newPath) {

    public ConfigKeyRename {
        Objects.requireNonNull(oldPath, "oldPath");
        Objects.requireNonNull(newPath, "newPath");
    }
}
