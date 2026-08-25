package com.huidu.farmersdelight.api.event;

import java.util.Locale;

public enum ReloadTarget {
    ALL("all"),
    CONFIG("config"),
    GUI("gui"),
    LANGUAGE("lang", "language", "languages"),
    RECIPES("recipes", "recipe"),
    ADVANCEMENTS("advancements", "advancement"),
    LOOT("loot", "lootreload"),
    ENCHANT("enchant", "enchantment"),
    DAMAGE("damage", "damagetype", "damage-type"),
    TAGS("tags", "tag");

    private final String[] aliases;

    ReloadTarget(String... aliases) { this.aliases = aliases; }

    public boolean isAll() { return this == ALL; }
    public String[] aliases() { return aliases.clone(); }
    public String eventReason() { return aliases[0]; }

    public static ReloadTarget fromCommand(String token) {
        if (token == null) return null;
        String lower = token.toLowerCase(Locale.ROOT);
        for (ReloadTarget t : values()) {
            for (String alias : t.aliases) {
                if (alias.equals(lower)) return t;
            }
        }
        return null;
    }
}
