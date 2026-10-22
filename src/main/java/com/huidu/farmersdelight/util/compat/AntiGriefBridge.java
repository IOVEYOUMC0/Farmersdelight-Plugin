package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.libraries.antigrieflib.AntiGriefLib;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import org.bukkit.Location;
import org.bukkit.entity.Player;

// Routes the non-WorldGuard land-plugin checks through CraftEngine's own AntiGriefLib provider, so both
// plugins enforce the same claim rules instead of this plugin bundling a second copy of the library.
// CraftEngine finishes enabling after this plugin, so the provider is resolved on first use.
final class AntiGriefBridge {

    private static volatile AntiGriefLib provider;
    private static volatile boolean unavailable;

    private AntiGriefBridge() {
    }

    static boolean isActive() {
        return !unavailable;
    }

    static boolean canPlace(Player player, Location location) {
        return test(player, location, Flag.PLACE);
    }

    static boolean canBreak(Player player, Location location) {
        return test(player, location, Flag.BREAK);
    }

    static boolean canInteract(Player player, Location location) {
        return test(player, location, Flag.INTERACT);
    }

    private static boolean test(Player player, Location location, Flag<Location> flag) {
        if (player == null || location == null) {
            return true;
        }
        AntiGriefLib lib = provider();
        if (lib == null) {
            return true;
        }
        try {
            return lib.test(player, flag, location);
        } catch (RuntimeException | LinkageError t) {
            return true;
        }
    }

    private static AntiGriefLib provider() {
        AntiGriefLib lib = provider;
        if (lib != null || unavailable) {
            return lib;
        }
        try {
            BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
            if (craftEngine == null) {
                return null;
            }
            lib = craftEngine.antiGriefProvider();
            provider = lib;
            return lib;
        } catch (RuntimeException | LinkageError t) {
            unavailable = true;
            return null;
        }
    }
}
