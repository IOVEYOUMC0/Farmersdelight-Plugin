package com.huidu.farmersdelight.api.advancement;

import java.util.Locale;

/**
 * Why the advancement system is or is not running, as evaluated by FarmersDelight.
 *
 *
 * Addons report this instead of repeating the individual checks, so a tab that was never registered
 * names its actual cause. The conditions are ordered: the first one that fails decides the result.
 */
public enum AdvancementAvailability {

    /** The advancement system is running. */
    AVAILABLE,

    /** FarmersDelight is not loaded yet, or is shutting down. */
    PLUGIN_UNAVAILABLE,

    /** FarmersDelight's configuration disables advancements. */
    DISABLED_BY_CONFIG,

    /** UltimateAdvancementAPI is missing or disabled. */
    API_NOT_INSTALLED,

    /** UltimateAdvancementAPI is installed but lacks the advancement layout this plugin registers through. */
    API_PATCH_MISSING;

    /**
     * Stable identifier for log messages, e.g. api_patch_missing. Addons pass this as their
     * {reason} placeholder; they do not switch over the constants, so a new cause needs no change
     * on their side and no translation per addon.
     */
    public String logName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
