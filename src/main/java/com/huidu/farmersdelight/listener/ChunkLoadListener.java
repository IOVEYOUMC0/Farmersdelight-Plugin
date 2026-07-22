package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntityController;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntityController;
import com.huidu.farmersdelight.block.behavior.SkilletBlockEntityController;
import com.huidu.farmersdelight.block.behavior.StoveBlockEntityController;
import com.huidu.farmersdelight.manager.HandleManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.*;

public class ChunkLoadListener implements Listener {

    private static final int DEFAULT_STARTUP_CHUNK_LOADS_PER_TICK = 16;

    private final FarmersDelightPlugin plugin;
    // Written on enable/disable (main thread) and read + self-nulled inside the repeating task body
    // (global-region thread). volatile gives the needed happens-before so disable's cancel isn't
    // missed and the body never sees a stale handle. R-CONC-002.
    private volatile PluginTask startupLoadTask;

    public ChunkLoadListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    // ChunkLoadEvent and ChunkUnloadEvent do not implement Cancellable, so ignoreCancelled has no effect
    // on them; the priorities carry all the ordering these handlers rely on.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        loadBlockEntitiesInChunk(event.getChunk().getWorld(), event.getChunk());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChunkUnloadSave(ChunkUnloadEvent event) {
        saveBlockEntitiesInChunk(event.getChunk().getWorld(), event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnloadCleanup(ChunkUnloadEvent event) {
        cleanupBlockEntitiesInChunk(event.getChunk().getWorld(), event.getChunk());
    }

    public void loadAlreadyLoadedChunks() {
        if (startupLoadTask != null) {
            startupLoadTask.cancel();
        }

        Deque<StartupChunk> chunksToLoad = new ArrayDeque<>();
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                chunksToLoad.addLast(new StartupChunk(chunk.getWorld(), chunk.getX(), chunk.getZ()));
            }
        }

        if (chunksToLoad.isEmpty()) {
            return;
        }

        startupLoadTask = plugin.scheduler().runRepeating(() -> {
            int chunksPerTick = Math.max(1, plugin.getConfigInt(DEFAULT_STARTUP_CHUNK_LOADS_PER_TICK,
                    "performance.startup-chunk-loads-per-tick"));
            for (int i = 0; i < chunksPerTick; i++) {
                StartupChunk chunk = chunksToLoad.pollFirst();
                if (chunk == null) {
                    PluginTask task = startupLoadTask;
                    startupLoadTask = null;
                    if (task != null) {
                        task.cancel();
                    }
                    return;
                }
                plugin.scheduler().runAt(chunk.world(), chunk.chunkX(), chunk.chunkZ(), () -> {
                    if (chunk.world().isChunkLoaded(chunk.chunkX(), chunk.chunkZ())) {
                        loadBlockEntitiesInChunk(chunk.world(), chunk.chunkX(), chunk.chunkZ());
                    }
                });
            }
        }, 1L, 1L);
    }

    private void loadBlockEntitiesInChunk(World world, Chunk chunk) {
        loadBlockEntitiesInChunk(world, chunk.getX(), chunk.getZ());
    }

    private void loadBlockEntitiesInChunk(World world, int chunkX, int chunkZ) {
        loadCraftEngineBlockEntitiesInChunk(world, chunkX, chunkZ);
        sweepOrphanFurnitureInChunk(world, chunkX, chunkZ);
    }

    /**
     * Runs the tray and handle orphan sweeps over ONE walk of the chunk's entity list. Both sweeps used
     * to fetch and iterate that list separately, paying the chunk entity lookup twice per chunk load for
     * two filters over the same entities.
     *
     * The sweeps stay unconditional: both exist precisely to remove furniture whose owning block is gone,
     * so gating them on an owner being present would defeat their purpose. Order is preserved (trays,
     * then handles) and both run on the region owning the chunk, which on Paper is the calling thread.
     * Sharing one snapshot is safe because the tray sweep only removes tray furniture, which the handle
     * sweep rejects anyway — by furniture id, and by the registry lookup returning null once removed.
     */
    private void sweepOrphanFurnitureInChunk(World world, int chunkX, int chunkZ) {
        TrayManager trayManager = plugin.getTrayManager();
        HandleManager handleManager = plugin.getHandleManager();
        if (trayManager == null && handleManager == null) {
            return;
        }
        plugin.scheduler().runAt(world, chunkX, chunkZ, () -> {
            if (world == null || !world.isChunkLoaded(chunkX, chunkZ)) {
                return;
            }
            List<Entity> entities = Arrays.asList(world.getChunkAt(chunkX, chunkZ).getEntities());
            if (trayManager != null) {
                trayManager.cleanupInvalidAutoTraysIn(world, entities);
            }
            if (handleManager != null) {
                handleManager.sweepOrphansIn(world, entities);
            }
        });
    }

    private void loadCraftEngineBlockEntitiesInChunk(World world, int chunkX, int chunkZ) {
        CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
        if (ceWorld == null) {
            return;
        }
        CEChunk chunk = ceWorld.getChunkAtIfLoaded(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }

        for (BlockEntity blockEntity : chunk.blockEntities()) {
            blockEntity.controller.let(CookingPotBlockEntityController.class,
                    CookingPotBlockEntityController::loadPendingDataIfReady);
            blockEntity.controller.let(CuttingBoardBlockEntityController.class,
                    CuttingBoardBlockEntityController::loadPendingDataIfReady);
            blockEntity.controller.let(SkilletBlockEntityController.class,
                    SkilletBlockEntityController::loadPendingDataIfReady);
            blockEntity.controller.let(StoveBlockEntityController.class,
                    StoveBlockEntityController::loadPendingDataIfReady);
        }
    }

    private void saveBlockEntitiesInChunk(World world, Chunk chunk) {
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        // Cooking pots / cutting boards use a per-chunk index, scanning only the unloading chunk instead of the whole world.
        saveCookingPotEntities(world, chunkX, chunkZ);
        saveCuttingBoardEntities(world, chunkX, chunkZ);
        // Skillet / stove still handled by coordinate range (outside this scope).
        plugin.getSkilletManager().saveAndUnloadChunk(world, minX, maxX, minZ, maxZ);
        plugin.getStoveManager().saveAndUnloadChunk(world, minX, maxX, minZ, maxZ);
    }

    private void cleanupBlockEntitiesInChunk(World world, Chunk chunk) {
        // Cooking pots / cutting boards use a per-chunk index, scanning only the unloading chunk instead of the whole world.
        cleanupCookingPotEntities(world, chunk.getX(), chunk.getZ());
        cleanupCuttingBoardEntities(world, chunk.getX(), chunk.getZ());
    }

    private void saveCookingPotEntities(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CookingPotBlockEntity> entities =
                CookingPotBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
        if (entities.isEmpty()) return;

        for (BlockPosKey posKey : entities.keySet()) {
            // A viewer can keep a pot GUI open long after walking out of range; close it before the
            // MONITOR cleanup orphans the entity, or its clicks would dupe (take) / lose (insert) items
            // against an entity nothing persists anymore. close() also commits the GUI's final state.
            com.huidu.farmersdelight.gui.CookingPotGui.closeOpenGuisAt(world, posKey.x(), posKey.y(), posKey.z());
            // Snapshot into the controller (not just a plain save): the snapshot survives the MONITOR
            // cleanup and re-hydrates the entity if the chunk reloads out of CraftEngine's chunk cache,
            // where loadCustomData never re-runs.
            CookingPotBlockBehavior.passivateBlockEntityData(world, posKey);
        }
    }

    private void saveCuttingBoardEntities(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CuttingBoardBlockEntity> entities =
                CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
        if (entities.isEmpty()) return;

        for (BlockPosKey posKey : entities.keySet()) {
            CuttingBoardBlockBehavior.passivateBlockEntityData(world, posKey);
        }
    }

    private void cleanupCookingPotEntities(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CookingPotBlockEntity> entities =
                CookingPotBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
        if (entities.isEmpty()) return;
        // getBlockEntitiesInChunk already returns a fresh HashMap snapshot (see its impl), and
        // removeBlockEntity mutates the authoritative chunkIndex/worldBlockEntities, not `entities`.
        // The old outer `new ArrayList<>(entities.keySet())` was a redundant double-snapshot.
        for (BlockPosKey posKey : entities.keySet()) {
            CookingPotBlockBehavior.removeBlockEntity(world, posKey, false);
        }
    }

    private void cleanupCuttingBoardEntities(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CuttingBoardBlockEntity> entities =
                CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
        if (entities.isEmpty()) return;
        for (BlockPosKey posKey : entities.keySet()) {
            CuttingBoardBlockBehavior.removeBlockEntity(world, posKey, false);
        }
    }

    public void shutdown() {
        if (startupLoadTask != null) {
            startupLoadTask.cancel();
            startupLoadTask = null;
        }
    }

    private record StartupChunk(World world, int chunkX, int chunkZ) {
    }
}
