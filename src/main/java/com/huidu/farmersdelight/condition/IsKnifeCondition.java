package com.huidu.farmersdelight.condition;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.CeItemInterop;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;

/**
 * Matches when the item the loot is evaluated with is one of the configured knives.
 */
public final class IsKnifeCondition implements Condition<Context> {

    public static final ConditionFactory<Context, IsKnifeCondition> FACTORY = section -> new IsKnifeCondition();

    @Override
    public boolean test(Context context) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return false;
        }
        return context.getOptionalParameter(DirectContextParameters.ITEM_IN_HAND)
                .filter(item -> !item.isEmpty())
                .map(CeItemInterop::toBukkitStack)
                .map(plugin::isKnife)
                .orElse(false);
    }
}
