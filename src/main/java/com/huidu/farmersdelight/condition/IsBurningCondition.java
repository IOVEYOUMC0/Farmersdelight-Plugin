package com.huidu.farmersdelight.condition;

import com.huidu.farmersdelight.loot.BurningDeathTracker;
import net.momirealms.craftengine.core.entity.Entity;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;

/**
 * Matches when the entity the loot is evaluated for is on fire, which is how the knife-drop rules swap
 * ham for smoked ham. A lethal Fire Aspect hit also counts: the victim can die before its fire-tick field is
 * updated, but vanilla still treats that kill as cooked/smoked for the loot condition.
 */
public final class IsBurningCondition implements Condition<Context> {

    public static final ConditionFactory<Context, IsBurningCondition> FACTORY = section -> new IsBurningCondition();

    @Override
    public boolean test(Context context) {
        boolean entityBurning = context.getOptionalParameter(DirectContextParameters.THIS_ENTITY)
                .map(entity -> entity.fireTicks() > 0 || BurningDeathTracker.wasBurning(entity.uuid()))
                .orElse(false);
        return entityBurning
                || context.getOptionalParameter(DirectContextParameters.FIRE_TICKS).map(ticks -> ticks > 0).orElse(false);
    }
}
