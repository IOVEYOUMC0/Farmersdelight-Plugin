package com.huidu.farmersdelight.api.util;

import org.bukkit.attribute.Attribute;
import org.jetbrains.annotations.ApiStatus;

/** Attribute aliases retained for existing API consumers. */
@ApiStatus.Internal
@ApiStatus.NonExtendable
public final class CompatAttributes {
    public static final Attribute MAX_HEALTH = Attribute.MAX_HEALTH;
    public static final Attribute ATTACK_SPEED = Attribute.ATTACK_SPEED;

    private CompatAttributes() { }
}
