package com.huidu.farmersdelight.api.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.jetbrains.annotations.ApiStatus;

/**
 * Resolves Bukkit attributes across the attribute-registry rename so a plugin runs on Minecraft
 * 1.21 through current builds. Older servers expose the enum constant GENERIC_MAX_HEALTH under the
 * registry key generic.max_health; newer servers renamed these to MAX_HEALTH and max_health.
 * Resolution goes through the attribute registry by key first, then falls back to a reflective field
 * lookup, and never references a version-specific enum constant directly, so this class compiles and
 * loads on every supported version.
 *
 * This is the single implementation shared by FarmersDelight and its addons; addons should use
 * these constants instead of keeping their own copy. A constant is null when the running server
 * exposes neither spelling, so callers must null-check before passing it to getAttribute.
 */
@ApiStatus.NonExtendable
public final class CompatAttributes {

    public static final Attribute MAX_HEALTH =
            resolve("max_health", "generic.max_health", "MAX_HEALTH", "GENERIC_MAX_HEALTH");
    public static final Attribute ATTACK_SPEED =
            resolve("attack_speed", "generic.attack_speed", "ATTACK_SPEED", "GENERIC_ATTACK_SPEED");

    private CompatAttributes() {
    }

    private static Attribute resolve(String modernKey, String legacyKey, String modernField, String legacyField) {
        for (String key : new String[]{modernKey, legacyKey}) {
            try {
                Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                if (attribute != null) {
                    return attribute;
                }
            } catch (Throwable ignored) {
            }
        }
        for (String field : new String[]{modernField, legacyField}) {
            try {
                return (Attribute) Attribute.class.getField(field).get(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
