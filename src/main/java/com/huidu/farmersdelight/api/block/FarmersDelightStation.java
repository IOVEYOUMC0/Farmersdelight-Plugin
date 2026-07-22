package com.huidu.farmersdelight.api.block;

import org.jetbrains.annotations.ApiStatus;

/**
 * The four FarmersDelight workstations a third-party plugin can identify and read through
 * FarmersDelightBlocks. A station is recognised by the CraftEngine block behavior attached to
 * the block, not by its block id, so a server that re-skins a station under its own id (or an addon
 * that reuses a station behavior) is still recognised.
 *
 * defaultBlockId() is the id FarmersDelight ships the station under. It is NOT what
 * FarmersDelightBlocks#stationIdOf returns — that returns the id of the block actually in the
 * world, which may differ. Compare block ids only when you specifically mean "the stock station".
 */
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

    /** The CraftEngine block id FarmersDelight ships this station under, e.g. farmersdelight:cooking_pot. */
    public String defaultBlockId() {
        return defaultBlockId;
    }
}
