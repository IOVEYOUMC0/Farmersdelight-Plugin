package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CraftEngine skips useOnBlock for a sneaking player who holds an item unless the behavior opts in via
 * canUseOnBlockIfSecondaryUseActive. The sneak gestures of the stations (carve a board, open a pot with
 * open-while-sneaking, place food on a stove, pass a held skillet through) live inside useOnBlock, so these
 * behaviors have to opt in or the gestures silently become unreachable.
 */
class SecondaryUseOptInContractTest {

    @Test
    void stationBehaviorsOptInToSecondaryUse() {
        for (Class<?> type : new Class<?>[]{
                CuttingBoardBlockBehavior.class,
                CookingPotBlockBehavior.class,
                StoveCookingBlockBehavior.class,
                SkilletBlockBehavior.class}) {
            assertTrue(declaresOptIn(type),
                    type.getSimpleName() + " must override canUseOnBlockIfSecondaryUseActive");
        }
    }

    private static boolean declaresOptIn(Class<?> type) {
        try {
            type.getDeclaredMethod("canUseOnBlockIfSecondaryUseActive", UseOnContext.class, ImmutableBlockState.class);
            return true;
        } catch (NoSuchMethodException inherited) {
            return false;
        }
    }
}
