package com.huidu.farmersdelight.api.util;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * The api facade over the internal region-protection checks. Addons compile against this class (the
 * api-only jar ships it); the implementation lives inside the real plugin, which is why the addon never
 * touches FarmersDelight internals. Behind the scenes the checks cover WorldGuard's master flag plus an
 * addon's own registered flag (or the FD built-in feature flags), and every AntiGriefLib-backed land
 * plugin.
 *
 * <p>An addon that owns custom blocks should:
 * <ol>
 *   <li>call registerCustomFlag(String) from its onLoad with its own flag name (e.g.
 *       "myaddon-station");</li>
 *   <li>pass that same name to canBuild/canUse when placing / breaking / interacting
 *       with its blocks (CraftEngine fake blocks bypass the vanilla events land plugins listen to, so the
 *       addon must gate them itself, exactly like FarmersDelight does for its own blocks).</li>
 * </ol>
 * A null flagName queries only the master flag.
 */
public final class ProtectionCompat {

    private ProtectionCompat() {
    }

    /**
     * Registers an addon-owned WorldGuard StateFlag so admins can gate the addon's features per region.
     * Idempotent; safe to call from onLoad even when WorldGuard is absent.
     */
    public static void registerCustomFlag(String flagName) {
        com.huidu.farmersdelight.util.compat.ProtectionCompat.registerCustomFlag(flagName);
    }

    public static boolean canBuild(Player player, Location location, String flagName) {
        return com.huidu.farmersdelight.util.compat.ProtectionCompat.canBuild(player, location, flagName);
    }

    public static boolean canBuild(Player player, Block block, String flagName) {
        return block == null || canBuild(player, block.getLocation(), flagName);
    }

    public static boolean canBreak(Player player, Location location, String flagName) {
        return com.huidu.farmersdelight.util.compat.ProtectionCompat.canBreak(player, location, flagName);
    }

    public static boolean canBreak(Player player, Block block, String flagName) {
        return block == null || canBreak(player, block.getLocation(), flagName);
    }

    public static boolean canUse(Player player, Location location, String flagName) {
        return com.huidu.farmersdelight.util.compat.ProtectionCompat.canUse(player, location, flagName);
    }

    public static boolean canUse(Player player, Block block, String flagName) {
        return block == null || canUse(player, block.getLocation(), flagName);
    }
}
