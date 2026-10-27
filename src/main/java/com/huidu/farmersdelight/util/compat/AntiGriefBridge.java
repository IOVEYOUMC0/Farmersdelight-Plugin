package com.huidu.farmersdelight.util.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.libraries.antigrieflib.AntiGriefLib;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

// Routes the non-WorldGuard land-plugin checks through CraftEngine's own AntiGriefLib provider, so both
// plugins enforce the same claim rules instead of this plugin bundling a second copy of the library.
// CraftEngine finishes enabling after this plugin, so the provider is resolved on first use.
final class AntiGriefBridge {

    private static volatile AntiGriefLib provider;
    private static volatile boolean unavailable;
    private static final AtomicBoolean providerFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean testFailureLogged = new AtomicBoolean();

    private AntiGriefBridge() {
    }

    static void reset() {
        provider = null;
        unavailable = false;
        providerFailureLogged.set(false);
        testFailureLogged.set(false);
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
        } catch (RuntimeException | LinkageError failure) {
            // Fail-open keeps workstations usable on a broken provider, but the degradation must be
            // visible: without this line a CE update could silently disable every land check.
            if (testFailureLogged.compareAndSet(false, true)) {
                warn("AntiGriefLib land check failed; allowing this action and continuing with "
                        + "WorldGuard-only protection (further failures are not logged)", failure);
            }
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
        } catch (RuntimeException | LinkageError failure) {
            unavailable = true;
            if (providerFailureLogged.compareAndSet(false, true)) {
                warn("Could not resolve CraftEngine's AntiGriefLib provider; non-WorldGuard land checks "
                        + "are disabled until the next CraftEngine reload", failure);
            }
            return null;
        }
    }

    private static void warn(String message, Throwable failure) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null) {
            plugin.getLogger().log(Level.WARNING, message, failure);
        } else {
            Bukkit.getLogger().log(Level.WARNING, message, failure);
        }
    }
}
