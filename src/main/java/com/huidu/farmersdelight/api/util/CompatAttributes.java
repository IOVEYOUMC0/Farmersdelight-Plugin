package com.huidu.farmersdelight.api.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.jetbrains.annotations.ApiStatus;

/**
 * Attribute constants that survive the attribute-registry rename between Minecraft versions.
 *
 *
 * Internal, and only safe to name from inside a method body. These are static fields, so referencing one
 * from an addon's own field initializer, static block or constant makes this class load during that addon's
 * class initialization; an addon runs under its own class loader, and a failure there (NoClassDefFoundError)
 * aborts that initializer and silently leaves whatever the addon was wiring up disabled. A method body keeps
 * the load on a path the addon can handle, and for a lookup this small a self-contained copy in the addon is
 * the safer answer still.
 */
@ApiStatus.Internal
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
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        for (String field : new String[]{modernField, legacyField}) {
            try {
                return (Attribute) Attribute.class.getField(field).get(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
        return null;
    }
}
