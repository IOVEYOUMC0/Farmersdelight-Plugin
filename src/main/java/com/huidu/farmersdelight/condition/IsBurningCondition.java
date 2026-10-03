package com.huidu.farmersdelight.condition;

import net.momirealms.craftengine.core.entity.Entity;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;

/**
 * Matches when the entity the loot is evaluated for is on fire, which is how the knife-drop rules swap
 * ham for smoked ham.
 *
 *
 * Reads THIS_ENTITY because ENTITY is the killer in CraftEngine's entity-death context,
 * and falls back to the FIRE_TICKS context parameter for contexts that only expose the count.
 */
public final class IsBurningCondition implements Condition<Context> {

    public static final ConditionFactory<Context, IsBurningCondition> FACTORY = section -> new IsBurningCondition();

    @Override
    public boolean test(Context context) {
        return context.getOptionalParameter(DirectContextParameters.THIS_ENTITY)
                .map(Entity::fireTicks)
                .map(ticks -> ticks > 0)
                .or(() -> context.getOptionalParameter(DirectContextParameters.FIRE_TICKS).map(ticks -> ticks > 0))
                .orElse(false);
    }
}
