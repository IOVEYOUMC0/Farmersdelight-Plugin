package com.huidu.farmersdelight.config;

import java.util.Locale;
import java.util.Optional;

/** One opt-in diagnostic category; the ids are what `debug.categories` names. */
public enum DebugCategory {
    STARTUP("startup"),
    RECIPE("recipe"),
    RELOAD("reload"),
    LOOT("loot"),
    COOKING_POT("cooking_pot"),
    STOVE("stove"),
    SKILLET("skillet"),
    TRAY("tray"),
    GUI("gui"),
    INTERACT("interact"),
    CONFIG("config"),
    RESOURCE("resource"),
    ENCHANT("enchant"),
    HANDHELD("handheld"),
    TAGS("tags"),
    BLOCK("block");

    private final String id;

    DebugCategory(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<DebugCategory> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (DebugCategory category : values()) {
            if (category.id.equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
