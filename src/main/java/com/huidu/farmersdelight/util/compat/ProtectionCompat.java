package com.huidu.farmersdelight.util.compat;

import net.momirealms.antigrieflib.AntiGriefLib;
import net.momirealms.antigrieflib.Flag;
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

    private static volatile AntiGriefLib antiGrief;

    private ProtectionCompat() {
    }

    public static void registerFlags() {
        WorldGuardCompat.registerFlags();
    }

    public static void init(JavaPlugin plugin) {
        try {
            antiGrief = AntiGriefLib.builder(plugin)
                    .ignoreOP(true)
                    .suppressErrors(false)
                    .exclude(other -> "WorldGuard".equals(other.getName()))
                    .build();
        } catch (Throwable t) {
            // AntiGriefLib missing / build failure: fall back to WorldGuard-only, no regression.
            antiGrief = null;
        }
    }

    // Master-only overloads (no feature): still respect WorldGuard's master flag + all other land plugins.
    public static boolean canBuild(Player player, Block block) {
        return canBuild(player, block, null);
    }

    public static boolean canBuild(Player player, Location location) {
        return canBuild(player, location, null);
    }

    // Feature-aware overloads.
    public static boolean canBuild(Player player, Block block, Feature feature) {
        return block == null || canBuild(player, block.getLocation(), feature);
    }

    public static boolean canBuild(Player player, Location location, Feature feature) {
        return WorldGuardCompat.canBuild(player, location, feature)
                && antiGriefAllows(player, location, Flag.PLACE);
    }

    public static boolean canUse(Player player, Block block, Feature feature) {
        return block == null || canUse(player, block.getLocation(), feature);
    }

    public static boolean canUse(Player player, Location location, Feature feature) {
        return WorldGuardCompat.canUse(player, location, feature)
                && antiGriefAllows(player, location, Flag.INTERACT);
    }

    private static boolean antiGriefAllows(Player player, Location location, Flag<Location> flag) {
        AntiGriefLib agl = antiGrief;
        if (agl == null || player == null || location == null) {
            return true;
        }
        try {
            return agl.test(player, flag, location);
        } catch (Throwable t) {
            return true;
        }
    }
}
