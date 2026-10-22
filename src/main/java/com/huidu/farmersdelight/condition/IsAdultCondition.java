package com.huidu.farmersdelight.condition;

import net.momirealms.craftengine.core.entity.Entity;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.entity.Ageable;

/**
 * Matches when the entity the loot is evaluated for (the victim of an entity-death drop) is an adult.
 */
public final class IsAdultCondition implements Condition<Context> {

    public static final ConditionFactory<Context, IsAdultCondition> FACTORY = section -> new IsAdultCondition();

    @Override
    public boolean test(Context context) {
        return context.getOptionalParameter(DirectContextParameters.THIS_ENTITY)
                .map(Entity::platformEntity)
                .filter(Ageable.class::isInstance)
                .map(Ageable.class::cast)
                .map(Ageable::isAdult)
                .orElse(false);
    }
}
