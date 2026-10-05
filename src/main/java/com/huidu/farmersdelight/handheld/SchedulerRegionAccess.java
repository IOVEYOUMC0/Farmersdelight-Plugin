package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The production SkewerHeatProbe.RegionAccess: every ownership question and every hand-off goes
 * through the plugin's own SchedulerAdapter, so the skewer follows exactly the same Folia rules as
 * the rest of the plugin. Bukkit.getScheduler is never called here.
 *
 * owns(int, int, int) asks the scheduler whether the calling thread owns the region of that
 * block column, so the probe reads a block inline only when that is safe and dispatches every other one
 * through SchedulerAdapter#runAt(Location, Runnable). The heat-source test itself is injected — the
 * caller owns the tag/definition knowledge, this class only owns the region rules.
 */
public final class SchedulerRegionAccess implements SkewerHeatProbe.RegionAccess {

    private final SchedulerAdapter scheduler;
    private final World world;
    private final Player player;
    private final Predicate<Location> heatSource;

    public SchedulerRegionAccess(SchedulerAdapter scheduler, Player player, Predicate<Location> heatSource) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.player = Objects.requireNonNull(player, "player");
        this.world = player.getWorld();
        this.heatSource = Objects.requireNonNull(heatSource, "heatSource");
    }

    @Override
    public boolean owns(int blockX, int blockZ) {
        // The column is enough: a region owns whole chunks, so ownership is the same for every y in it.
        return scheduler.isOwnedByCurrentRegion(location(blockX, player.getLocation().getBlockY(), blockZ));
    }

    @Override
    public boolean isHeatSource(int blockX, int blockY, int blockZ) {
        // Only ever called for positions the probe already knows this thread owns.
        return heatSource.test(location(blockX, blockY, blockZ));
    }

    @Override
    public void runAt(int blockX, int blockZ, Runnable read) {
        scheduler.runAt(location(blockX, player.getLocation().getBlockY(), blockZ), read);
    }

    @Override
    public boolean isOnFire() {
        return player.getFireTicks() > 0;
    }

    private Location location(int blockX, int blockY, int blockZ) {
        return new Location(world, blockX + 0.5, blockY, blockZ + 0.5);
    }
}
