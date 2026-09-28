package com.huidu.farmersdelight.api;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The one place the api package resolves the running plugin.
 *
 * <p>Every api facade used to read FarmersDelightPlugin.getInstance() itself and then repeat the
 * "and it is enabled" test, which is the same question asked in a dozen slightly different spellings — some
 * call sites checked isEnabled0() and some only checked for null. Routing them through here makes the
 * availability rule single-sourced, gives the future dependency-injection migration one seam to change
 * instead of forty, and keeps the static lookup out of the facades themselves.
 *
 * <p>Public only because the api facades live in subpackages. Addons must not call it: use
 * FarmersDelightApi#isAvailable() for the availability question, and the facade that owns the
 * operation otherwise.
 */
@ApiStatus.Internal
public final class PluginAccess {

    private PluginAccess() {
    }

    /**
     * The running plugin, or null while it is absent or disabled.
     *
     * <p>A disabled plugin is treated as absent: its managers are torn down during disable, so facades that
     * kept using the instance would hit null fields instead of degrading.
     *
     * <p>Marked internal on the member as well as on the class: the return type is the plugin itself, so this
     * is the one api method that exposes an internal type, and the marker is what states that it is not part
     * of the addon-facing surface. tools/check_api_boundary.py enforces that distinction.
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
