package com.huidu.farmersdelight.util;

import net.momirealms.antigrieflib.AntiGriefLib;
import net.momirealms.antigrieflib.Flag;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Single protection gate for FarmersDelight's custom-block placement/interaction paths (the R-SEC-001 sites
 * that intercept vanilla and call CraftEngineBlocks.place). A location is allowed only if BOTH pass:
 * - WorldGuard, via WorldGuardCompat, which keeps FD's master farmersdelight-use flag plus one per-Feature
 *   StateFlag (fine-grained per-station control WorldGuard-side);
 * - every other installed land/claim plugin, via AntiGriefLib, which abstracts 24+ backends (GriefPrevention,
 *   Lands, Towny, Residence, PlotSquared, Factions, HuskClaims, and more) behind one query. WorldGuard is
 *   excluded from AntiGriefLib so FD's own granular WG path stays authoritative for it.
 * Never fails closed: when a backend is absent or a hook errors, the check allows (protection is opt-in, and a
 * buggy third-party hook must not block legitimate interactions).
 */
public final class ProtectionCompat {

    /** Per-station protection features. Each maps to a WorldGuard StateFlag (fine-grained WG-side); the other
     *  land plugins get the generic place/interact gate (they have no notion of FD features). */
    public enum Feature {
        CUTTING_BOARD("farmersdelight-cutting-board"),
        SKILLET("farmersdelight-skillet"),
        STOVE("farmersdelight-stove"),
        ROPE("farmersdelight-rope"),
        RICE("farmersdelight-rice"),
        RICH_SOIL("farmersdelight-rich-soil"),
        TOMATO("farmersdelight-tomato"),
        MUSHROOM_COLONY("farmersdelight-mushroom-colony");

        private final String flagName;

        Feature(String flagName) {
            this.flagName = flagName;
        }

        /** The WorldGuard StateFlag name registered for this feature. */
        String flagName() {
            return flagName;
        }
    }

    private static volatile AntiGriefLib antiGrief;

    private ProtectionCompat() {
    }

    /** Register FarmersDelight's WorldGuard region flags. MUST run during onLoad — WorldGuard locks its
     *  flag registry the moment it enables. No-op when WorldGuard is absent. */
    public static void registerFlags() {
        WorldGuardCompat.registerFlags();
    }

    /** Build the AntiGriefLib facade over every installed land plugin EXCEPT WorldGuard (handled by FD's own
     *  granular WorldGuardCompat). MUST run in onEnable — AntiGriefLib detects installed plugins
     *  at build time, so the land plugins must have enabled first (declare them as softdepend). */
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

    // ── Master-only overloads (no feature): still respect WorldGuard's master flag + all other land plugins. ──
    public static boolean canBuild(Player player, Block block) {
        return canBuild(player, block, null);
    }

    public static boolean canBuild(Player player, Location location) {
        return canBuild(player, location, null);
    }

    // ── Feature-aware overloads. ──
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

    /** Query the non-WorldGuard land plugins through AntiGriefLib. Allows (true) when the facade is absent or a
     *  backend hook throws — protection never fails closed on our account. */
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
