package com.huidu.farmersdelight.block.behavior;

enum CuttingBoardStoredItemPose {
    FLAT(false),
    INSERTED_TOOL(true);

    private final boolean itemCarved;

    CuttingBoardStoredItemPose(boolean itemCarved) {
        this.itemCarved = itemCarved;
    }

    boolean itemCarved() {
        return itemCarved;
    }

    static CuttingBoardStoredItemPose fromCarved(boolean itemCarved) {
        return itemCarved ? INSERTED_TOOL : FLAT;
    }
}
