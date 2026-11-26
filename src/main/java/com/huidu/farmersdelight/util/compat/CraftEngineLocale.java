package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.plugin.config.Config;

import java.util.Locale;

/** CraftEngine locale bridge kept out of the addon-facing API signatures. */
public final class CraftEngineLocale {

    private CraftEngineLocale() {
    }

    /** The forced CraftEngine locale, or null when CE is absent/not ready. */
    public static Locale forcedLocale() {
        try {
            return Config.forcedLocale();
        } catch (LinkageError | RuntimeException absent) {
            return null;
        }
    }
}
