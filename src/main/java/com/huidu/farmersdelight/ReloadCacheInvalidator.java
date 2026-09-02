package com.huidu.farmersdelight;

import com.huidu.farmersdelight.gui.GuiCacheInvalidator;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;

/** Invalidates caches derived from CraftEngine registries and reloadable presentation data. */
final class ReloadCacheInvalidator {

    private ReloadCacheInvalidator() {
    }

    static void clear() {
        ItemUtils.clearItemCache();
        SoundUtils.clearCache();
        GuiCacheInvalidator.clearConfigCaches();
    }
}
