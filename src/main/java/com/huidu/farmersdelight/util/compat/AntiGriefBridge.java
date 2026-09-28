package com.huidu.farmersdelight.util.compat;

import net.momirealms.antigrieflib.AntiGriefLib;
import net.momirealms.antigrieflib.Flag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

// Isolates every antigrieflib reference so ProtectionCompat can survive the library (or its Flag class)
// being absent at runtime — the case on CraftEngine builds that no longer ship antigrieflib. This class is
// only touched after init() confirms the classes resolve; ProtectionCompat short-circuits past it otherwise.
final class AntiGriefBridge {

    private static volatile AntiGriefLib antiGrief;

    private AntiGriefBridge() {
    }

    static void init(JavaPlugin plugin) {
        // Force-resolve Flag first so a missing/incompatible antigrieflib fails here (the caller then disables
        // the bridge) instead of later at a block interaction, where it would abort the event.
        Flag<Location> probe = Flag.INTERACT;
        if (probe != null) {
            antiGrief = AntiGriefLib.builder(plugin)
                    // Operators already bypass WorldGuard's BUILD/region checks. Keep the same
                    // administrator semantics for the other claim providers; otherwise an OP can
                    // still be denied while breaking a CraftEngine block in a protected region.
                    .ignoreOP(true)
                    .suppressErrors(false)
                    .exclude(other -> "WorldGuard".equals(other.getName()))
                    .build();
        }
    }

    static boolean isActive() {
        return antiGrief != null;
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
        AntiGriefLib agl = antiGrief;
        if (agl == null || player == null || location == null) {
            return true;
        }
        try {
            return agl.test(player, flag, location);
        } catch (RuntimeException | LinkageError t) {
            return true;
        }
    }
}
