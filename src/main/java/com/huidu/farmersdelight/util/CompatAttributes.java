package com.huidu.farmersdelight.util;

import org.bukkit.attribute.Attribute;

/**
 * Internal alias for the shared attribute-compatibility constants that live in
 * com.huidu.farmersdelight.api.util.CompatAttributes. The api copy holds the only implementation, so
 * the plugin and its addons resolve the attribute registry once and identically; this class exists
 * only so internal call sites keep their short import.
 */
public final class CompatAttributes {

    public static final Attribute MAX_HEALTH =
            com.huidu.farmersdelight.api.util.CompatAttributes.MAX_HEALTH;
    public static final Attribute ATTACK_SPEED =
            com.huidu.farmersdelight.api.util.CompatAttributes.ATTACK_SPEED;

    private CompatAttributes() {
    }
}
