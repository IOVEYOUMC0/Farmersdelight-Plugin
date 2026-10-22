package com.huidu.farmersdelight.api;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The one place the api package resolves the running plugin.
 */
@ApiStatus.Internal
public final class PluginAccess {

    private PluginAccess() {
    }

    /**
     * The running plugin, or null while it is absent or disabled.
     */
    @ApiStatus.Internal
    @Nullable
    public static FarmersDelightPlugin pluginOrNull() {
        FarmersDelightPlugin instance = FarmersDelightPlugin.getInstance();
        return instance != null && FarmersDelightPlugin.isEnabled0() ? instance : null;
    }

    /** Whether the plugin is loaded and enabled. */
    public static boolean isAvailable() {
        return FarmersDelightPlugin.getInstance() != null && FarmersDelightPlugin.isEnabled0();
    }
}
