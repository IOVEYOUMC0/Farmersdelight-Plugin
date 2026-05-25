package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.*;

public class ChunkLoadListener implements Listener {

    private static final int STARTUP_CHUNK_LOADS_PER_TICK = 16;

    private final FarmersDelightPlugin plugin;
    private PluginTask startupLoadTask;

    public ChunkLoadListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        loadBlockEntitiesInChunk(event.getChunk().getWorld(), event.getChunk());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChunkUnloadSave(ChunkUnloadEvent event) {
        saveBlockEntitiesInChunk(event.getChunk().getWorld(), event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
            for (int i = 0; i < STARTUP_CHUNK_LOADS_PER_TICK; i++) {
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
        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage == null) return;

        Map<String, Map<String, Object>> blockData = storage.loadBlockDataInChunk(world, chunkX, chunkZ);
        for (Map<String, Object> data : blockData.values()) {
                String blockType = null;
                if (data.get("_blockType") instanceof String s) {
                    blockType = s;
                }
                int x = 0;
                if (data.get("_x") instanceof Number n) {
                    x = n.intValue();
                }
                int y = 0;
                if (data.get("_y") instanceof Number n) {
                    y = n.intValue();
                }
                int z = 0;
                if (data.get("_z") instanceof Number n) {
                    z = n.intValue();
                }
            if (blockType == null) {
                continue;
            }

            BlockPos pos = new BlockPos(x, y, z);
            switch (blockType) {
                case "cooking_pot" -> {
                    BlockPosKey posKey = new BlockPosKey(pos);
                    if (CookingPotBlockBehavior.isCookingPotBlock(world, posKey)) {
                        CookingPotBlockBehavior.migrateLegacyBlockData(world, posKey, data);
                    }
                    storage.removeBlockData(posKey.toLocation(world));
                }
                case "cutting_board" -> {
                    BlockPosKey posKey = new BlockPosKey(pos);
                    if (CuttingBoardBlockBehavior.isCuttingBoardBlock(world, posKey)) {
                        CuttingBoardBlockBehavior.migrateLegacyBlockData(world, posKey, data);
                    }
                    storage.removeBlockData(posKey.toLocation(world));
                }
                case "skillet" -> plugin.getSkilletManager().loadSkillet(world, pos, data);
                case "stove" -> plugin.getStoveManager().loadStove(world, pos, data);
                default -> plugin.getLogger().warning(
                        "Unknown block type in chunk load: " + blockType + " at " + x + "," + y + "," + z
                );
            }
        }
    }

    private void saveBlockEntitiesInChunk(World world, Chunk chunk) {
        int minX = chunk.getX() << 4;
        int minZ = chunk.getZ() << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        saveCookingPotEntities(world, minX, maxX, minZ, maxZ);
        saveCuttingBoardEntities(world, minX, maxX, minZ, maxZ);
        plugin.getSkilletManager().saveAndUnloadChunk(world, minX, maxX, minZ, maxZ);
        plugin.getStoveManager().saveAndUnloadChunk(world, minX, maxX, minZ, maxZ);
    }

    private void cleanupBlockEntitiesInChunk(World world, Chunk chunk) {
        int minX = chunk.getX() << 4;
        int minZ = chunk.getZ() << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        cleanupCookingPotEntities(world, minX, maxX, minZ, maxZ);
        cleanupCuttingBoardEntities(world, minX, maxX, minZ, maxZ);
    }

    private void saveCookingPotEntities(World world, int minX, int maxX, int minZ, int maxZ) {
        Map<BlockPosKey, CookingPotBlockEntity> entities = CookingPotBlockBehavior.getAllBlockEntities(world);
        if (entities.isEmpty()) return;

        for (Map.Entry<BlockPosKey, CookingPotBlockEntity> entry : entities.entrySet()) {
            BlockPosKey posKey = entry.getKey();
            if (posKey.x() >= minX && posKey.x() <= maxX && posKey.z() >= minZ && posKey.z() <= maxZ) {
                CookingPotBlockBehavior.saveBlockEntityData(world, posKey);
            }
        }
    }

    private void saveCuttingBoardEntities(World world, int minX, int maxX, int minZ, int maxZ) {
        Map<BlockPosKey, CuttingBoardBlockEntity> entities = CuttingBoardBlockBehavior.getAllBlockEntities(world);
        if (entities.isEmpty()) return;

        for (Map.Entry<BlockPosKey, CuttingBoardBlockEntity> entry : entities.entrySet()) {
            BlockPosKey posKey = entry.getKey();
            if (posKey.x() >= minX && posKey.x() <= maxX && posKey.z() >= minZ && posKey.z() <= maxZ) {
                CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
            }
        }
    }

    private void cleanupCookingPotEntities(World world, int minX, int maxX, int minZ, int maxZ) {
        Map<BlockPosKey, CookingPotBlockEntity> entities = CookingPotBlockBehavior.getAllBlockEntities(world);
        if (entities.isEmpty()) return;

        List<BlockPosKey> toRemove = new ArrayList<>();
        for (BlockPosKey posKey : entities.keySet()) {
            if (posKey.x() >= minX && posKey.x() <= maxX && posKey.z() >= minZ && posKey.z() <= maxZ) {
                toRemove.add(posKey);
            }
        }

        for (BlockPosKey posKey : toRemove) {
            CookingPotBlockBehavior.removeBlockEntity(world, posKey, false);
        }
    }

    private void cleanupCuttingBoardEntities(World world, int minX, int maxX, int minZ, int maxZ) {
        Map<BlockPosKey, CuttingBoardBlockEntity> entities = CuttingBoardBlockBehavior.getAllBlockEntities(world);
        if (entities.isEmpty()) return;

        List<BlockPosKey> toRemove = new ArrayList<>();
        for (BlockPosKey posKey : entities.keySet()) {
            if (posKey.x() >= minX && posKey.x() <= maxX && posKey.z() >= minZ && posKey.z() <= maxZ) {
                toRemove.add(posKey);
            }
        }

        for (BlockPosKey posKey : toRemove) {
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

