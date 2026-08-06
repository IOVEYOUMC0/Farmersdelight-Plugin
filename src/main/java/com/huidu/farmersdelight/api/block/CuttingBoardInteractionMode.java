package com.huidu.farmersdelight.api.block;

import java.util.Locale;

public enum CuttingBoardInteractionMode {
    STACKING,
    OFFHAND;

    public static CuttingBoardInteractionMode parse(String value) {
        if (value == null) return STACKING;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "offhand" -> OFFHAND;
            default -> STACKING;
        };
    }

    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
