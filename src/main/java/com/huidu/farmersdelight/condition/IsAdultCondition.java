package com.huidu.farmersdelight.condition;

import net.momirealms.craftengine.core.entity.Entity;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.entity.Ageable;

/**
 * Matches when the entity the loot is evaluated for (the victim of an entity-death drop) is an adult.
 *
 *
 * Reads THIS_ENTITY rather than ENTITY: in CraftEngine's entity-death context
 * ENTITY is the killer, so the age of the drop source is only available on the former.
 *
 *
 * Typed to Context like every CraftEngine condition, because the condition registry only
 * accepts Condition<Context> factories; the loot system reads them back through
 * CommonConditions.fromConfig with the context type it needs.
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
