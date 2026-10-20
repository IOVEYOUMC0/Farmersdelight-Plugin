package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * Asks CraftEngine whether any custom item is loaded — safely, even before CraftEngine is enabled.
 */
public final class CustomItemPresence {

    private static final BooleanSupplier CRAFT_ENGINE = () -> !CraftEngineItems.loadedItems().isEmpty();

    private static volatile BooleanSupplier probe = CRAFT_ENGINE;
    private static volatile boolean loaded;

    private CustomItemPresence() {
    }

    /** True only when CraftEngine is up and reports at least one custom item; false when it is not up yet. */
    public static boolean anyLoaded() {
        if (loaded) {
            return true;
        }
        try {
            if (!probe.getAsBoolean()) {
                return false;
            }
        } catch (RuntimeException | LinkageError notReady) {
            // CraftEngine is not enabled yet (or is still starting): defer, never fail the caller's load.
            return false;
        }
        loaded = true;
        return true;
    }

    /**
     * Forgets a remembered positive answer, so the next anyLoaded() asks CraftEngine again. Called when a
     * CraftEngine reload can have taken the items away; a reload that loads items instead needs no call,
     * because a negative answer was never remembered.
     */
    @ApiStatus.Internal
    public static void invalidate() {
        loaded = false;
    }

    /** Swaps the CraftEngine question for a test double, and drops the answer the previous probe produced. */
    @ApiStatus.Internal
    public static void installProbe(@Nullable BooleanSupplier replacement) {
        invalidate();
        probe = replacement == null ? CRAFT_ENGINE : replacement;
    }
}
