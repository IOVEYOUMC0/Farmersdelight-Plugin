package com.huidu.farmersdelight.api.block;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
public enum FarmersDelightStation {

    COOKING_POT("farmersdelight:cooking_pot"),
    CUTTING_BOARD("farmersdelight:cutting_board"),
    SKILLET("farmersdelight:skillet"),
    STOVE("farmersdelight:stove");

    private final String defaultBlockId;

    FarmersDelightStation(String defaultBlockId) {
        this.defaultBlockId = defaultBlockId;
    }

    public String defaultBlockId() {
        return defaultBlockId;
    }
}
