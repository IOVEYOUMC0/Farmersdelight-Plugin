package com.huidu.farmersdelight.util.compat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ProtectionCompat {

    public enum Feature {
        CUTTING_BOARD("farmersdelight-cutting-board"),
        SKILLET("farmersdelight-skillet"),
        STOVE("farmersdelight-stove"),
        ROPE("farmersdelight-rope"),
        RICE("farmersdelight-rice"),
        RICH_SOIL("farmersdelight-rich-soil"),
        TOMATO("farmersdelight-tomato"),
        MUSHROOM_COLONY("farmersdelight-mushroom-colony"),
        COOKING_POT("farmersdelight-cooking-pot"),
        FOOD_BLOCK("farmersdelight-food-block");

        private final String flagName;

        Feature(String flagName) {
            this.flagName = flagName;
        }

        String flagName() {
            return flagName;
        }
    }

    private static volatile boolean antiGriefReady;

    private ProtectionCompat() {
    }

    public static void registerFlags() {
        WorldGuardCompat.registerFlags();
    }

    /**
     * Registers an addon-owned WorldGuard StateFlag (e.g. "barbequesdelight-station") so admins can gate
     * addon features per region like the FD built-in features. Idempotent; safe to call from onLoad even
     * when WorldGuard is absent. The addon then queries with ProtectionCompat.canBuild/canUse and the same
     * flag name (null = master flag only).
     */
    public static void registerCustomFlag(String flagName) {
        WorldGuardCompat.registerCustomFlag(flagName);
    }

    public static void init(JavaPlugin plugin) {
        try {
            AntiGriefBridge.init(plugin);
            antiGriefReady = AntiGriefBridge.isActive();
        } catch (Throwable t) {
            // antigrieflib missing / Flag class absent / build failure: WorldGuard-only, no regression.
            antiGriefReady = false;
        }
    }

    // Master-only overloads (no flag): still respect WorldGuard's master flag + all other land plugins.
    public static boolean canBuild(Player player, Block block) {
        return canBuild(player, block, (String) null);
    }

    public static boolean canBuild(Player player, Location location) {
        return canBuild(player, location, (String) null);
    }

    // Feature-aware overloads.
    public static boolean canBuild(Player player, Block block, Feature feature) {
        return canBuild(player, block, feature == null ? null : feature.flagName());
    }

    public static boolean canBuild(Player player, Location location, Feature feature) {
        return canBuild(player, location, feature == null ? null : feature.flagName());
    }

    // Addon flag-aware overloads (flagName registered via registerCustomFlag).
    public static boolean canBuild(Player player, Block block, String flagName) {
        return block == null || canBuild(player, block.getLocation(), flagName);
    }

    public static boolean canBuild(Player player, Location location, String flagName) {
        return WorldGuardCompat.canBuild(player, location, flagName)
                && (!antiGriefReady || AntiGriefBridge.canPlace(player, location));
    }

    public static boolean canUse(Player player, Block block, Feature feature) {
        return canUse(player, block, feature == null ? null : feature.flagName());
    }

    public static boolean canUse(Player player, Location location, Feature feature) {
        return canUse(player, location, feature == null ? null : feature.flagName());
    }

    public static boolean canUse(Player player, Block block, String flagName) {
        return block == null || canUse(player, block.getLocation(), flagName);
    }

    public static boolean canUse(Player player, Location location, String flagName) {
        return WorldGuardCompat.canUse(player, location, flagName)
                && (!antiGriefReady || AntiGriefBridge.canInteract(player, location));
    }
}
