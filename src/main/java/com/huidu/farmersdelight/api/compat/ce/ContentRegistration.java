package com.huidu.farmersdelight.api.compat.ce;

import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.loot.function.LootFunctionFactory;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.util.Key;
import org.jetbrains.annotations.ApiStatus;

import java.util.Set;

/**
 * CraftEngine-pinned registry for addon block/item behaviour factories, functions, conditions and
 * loot functions. This is the advanced compatibility surface; the neutral API does not depend on it.
 *
 * <p>Behaviour: ids must be namespaced and outside {@code minecraft} and {@code farmersdelight};
 * late registration requires CraftEngine to reread content; unregistering stops reapplication but
 * CraftEngine retains the type until restart (use a new id to replace it).</p>
 */
@ApiStatus.Experimental
public final class ContentRegistration {

    public enum Kind {
        BLOCK_BEHAVIOR,
        ITEM_BEHAVIOR,
        FUNCTION,
        CONDITION,
        LOOT_FUNCTION;

        com.huidu.farmersdelight.api.registry.ContentRegistration.Kind legacy() {
            return com.huidu.farmersdelight.api.registry.ContentRegistration.Kind.valueOf(name());
        }
    }

    private ContentRegistration() {
    }

    public static void registerBlockBehavior(Key id, BlockBehaviorFactory<?> factory) {
        com.huidu.farmersdelight.api.registry.ContentRegistration.registerBlockBehavior(id, factory);
    }

    public static void registerItemBehavior(Key id, ItemBehaviorFactory<?> factory) {
        com.huidu.farmersdelight.api.registry.ContentRegistration.registerItemBehavior(id, factory);
    }

    public static <T extends Function<Context>> void registerFunction(Key id, FunctionFactory<Context, T> factory) {
        com.huidu.farmersdelight.api.registry.ContentRegistration.registerFunction(id, factory);
    }

    public static <T extends Condition<Context>> void registerCondition(Key id, ConditionFactory<Context, T> factory) {
        com.huidu.farmersdelight.api.registry.ContentRegistration.registerCondition(id, factory);
    }

    public static void registerLootFunction(Key id, LootFunctionFactory<?> factory) {
        com.huidu.farmersdelight.api.registry.ContentRegistration.registerLootFunction(id, factory);
    }

    public static boolean unregister(Key id) {
        return com.huidu.farmersdelight.api.registry.ContentRegistration.unregister(id);
    }

    public static boolean isRegistered(Key id) {
        return com.huidu.farmersdelight.api.registry.ContentRegistration.isRegistered(id);
    }

    public static Set<Key> registeredIds() {
        return com.huidu.farmersdelight.api.registry.ContentRegistration.registeredIds();
    }

    public static int apply(Kind kind) {
        return com.huidu.farmersdelight.api.registry.ContentRegistration.apply(kind.legacy());
    }
}
