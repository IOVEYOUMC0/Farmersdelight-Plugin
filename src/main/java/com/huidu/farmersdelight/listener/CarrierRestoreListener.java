package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.CarrierRestorer;
import com.huidu.farmersdelight.util.PeriodicStagger;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Keeps CarrierRestorer in step with the world.
 *
 *
 * Placement, removal and the neighbour updates that reconnect fences are the events that can create or
 * invalidate a restored block once a chunk is resident; chunk load and unload own the lifetime of a
 * chunk's displays, and the maintenance task covers everything the events cannot see (world generation,
 * external plugins, or a display removed behind the restorer's back).
 */
public final class CarrierRestoreListener implements Listener {

    private static final int DEFAULT_TICK_INTERVAL = 10;
    private static final BlockFace[] NEIGHBOURS = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN
    };

    private final FarmersDelightPlugin plugin;
    private final CarrierRestorer restorer;
    private PluginTask task;

    public CarrierRestoreListener(FarmersDelightPlugin plugin, CarrierRestorer restorer) {
        this.plugin = plugin;
        this.restorer = restorer;
    }

    /** Starts the maintenance task; safe to call once per lifecycle. */
    public void start() {
        stop();
        int interval = Math.max(1, plugin.getConfigInt(DEFAULT_TICK_INTERVAL,
                "performance.budgets.carrier-restore-tick-interval"));
        this.task = plugin.scheduler().runRepeating(restorer::tick,
                PeriodicStagger.initialDelay("carrier-restore", interval), interval);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        refreshWithNeighbours(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        restorer.forget(event.getBlock());
        for (BlockFace face : NEIGHBOURS) {
            restorer.updateAcrossRegions(event.getBlock().getRelative(face));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        // A fence's connections are rewritten by neighbour updates, and both the block whose data changed
        // and the neighbours that triggered it can end up in a different borrowed state.
        refreshWithNeighbours(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        // Only the coordinates are queued: the chunk itself belongs to the region that owns it and must not
        // be handed to the global thread that drives the maintenance task.
        restorer.queueChunkScan(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        restorer.unloadChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        restorer.unloadWorld(event.getWorld());
    }

    /** Removes displays a previous session left behind. */
    public void onWorldLoad(World world) {
        restorer.sweepOrphans(world);
    }

    /**
     * Re-reads the changed block and its neighbours. A neighbour across a region border is handed to the
     * region that owns it instead of being dropped, so a fence whose state another region changes is still
     * redrawn immediately.
     */
    private void refreshWithNeighbours(Block block) {
        if (block == null) {
            return;
        }
        restorer.updateAcrossRegions(block);
        for (BlockFace face : NEIGHBOURS) {
            restorer.updateAcrossRegions(block.getRelative(face));
        }
    }
}
