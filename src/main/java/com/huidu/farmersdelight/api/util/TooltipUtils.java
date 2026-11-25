package com.huidu.farmersdelight.api.util;

import net.momirealms.craftengine.core.item.Item;

/**
 * Compatibility delegate for ce.TooltipUtils.
 *
 * @deprecated use the CraftEngine-pinned api.compat.ce class for new code.
 */
@Deprecated
public final class TooltipUtils {

    private TooltipUtils() {
    }

    public static void hideDurabilityLine(Item wrapped) {
        com.huidu.farmersdelight.api.compat.ce.TooltipUtils.hideDurabilityLine(wrapped);
    }
}
