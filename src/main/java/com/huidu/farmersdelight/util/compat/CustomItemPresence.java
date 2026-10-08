package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * Asks CraftEngine whether any custom item is loaded — safely, even before CraftEngine is enabled.
 *
 *
 * The live crash: an addon registers content during onLoad, which runs before CraftEngine enables, and
 * the "is content loaded" question reached BukkitItemManager.instance().loadedItems(). CraftEngine
 * 26.9.2's CraftEngineItems.loadedItems() documents (and throws/NPEs) when that instance is not
 * available yet, so the addon failed to load; 26.10 happens to tolerate it, which is exactly why this only
 * showed up on a version swap. "Nothing is loaded yet" is the correct answer in that window: the callers then
 * take their deferred path and re-apply the registration once CraftEngine is ready.
 *
 *
 * Only a positive answer is remembered. A negative one is asked again on every call on purpose, because
 * CraftEngine enables after this plugin's onLoad and remembering it would defer those registrations forever.
 * The probe is not free — it reaches CraftEngine's item registry, and one caller's path (a block-physics event)
 * asks five times per event — so a CraftEngine reload calls the invalidate() method to drop a stale positive.
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
