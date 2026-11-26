package com.huidu.farmersdelight.api.block;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable read-only snapshot of a live FarmersDelight crop.
 *
 * <p>This is a query value; mutations must go through the owning FD/CE behavior.</p>
 */
public record CropSnapshot(
        String blockId,
        int age,
        int maxAge,
        @Nullable String seedItemId,
        boolean tall,
        boolean resetsOnHarvest,
        boolean mature) {

    @Override
    public String toString() {
        return "CropSnapshot[" + blockId + ", age=" + age + ", mature=" + mature + "]";
    }
}
