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
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.storage.LegacyBlockStorageManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.*;

public class ChunkLoadListener implements Listener {

    private static final int DEFAULT_STARTUP_CHUNK_LOADS_PER_TICK = 16;

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

        LegacyBlockStorageManager storage = plugin.getLegacyBlockStorageManager();
        if (storage != null) {
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
                        // 仅在确认 CE 方块存在并完成迁移后，才删除旧的 legacy 数据条目。
                        if (CookingPotBlockBehavior.isCookingPotBlock(world, posKey)) {
                            CookingPotBlockBehavior.migrateLegacyBlockData(world, posKey, data);
                            storage.removeBlockData(posKey.toLocation(world));
                        }
                    }
                    case "cutting_board" -> {
                        BlockPosKey posKey = new BlockPosKey(pos);
                        if (CuttingBoardBlockBehavior.isCuttingBoardBlock(world, posKey)) {
                            CuttingBoardBlockBehavior.migrateLegacyBlockData(world, posKey, data);
                            storage.removeBlockData(posKey.toLocation(world));
                        }
                    }
                    case "skillet" -> {
                        BlockPosKey posKey = new BlockPosKey(pos);
                        plugin.getSkilletManager().loadSkillet(world, posKey, data);
                        storage.removeBlockData(posKey.toLocation(world));
                    }
                    case "stove" -> {
                        BlockPosKey posKey = new BlockPosKey(pos);
                        plugin.getStoveManager().loadStove(world, posKey, data);
                        storage.removeBlockData(posKey.toLocation(world));
                    }
                    default -> plugin.getLogger().warning(I18n.formatConsole("chunk_load.unknown_block_type",
                            "type", blockType,
                            "x", x,
                            "y", y,
                            "z", z));
                }
            }
        }

        TrayManager trayManager = plugin.getTrayManager();
        if (trayManager != null) {
            trayManager.cleanupInvalidAutoTraysInChunk(world, chunkX, chunkZ);
        }
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

        // 厨锅 / 砧板改用按区块索引,只查正在卸载的那个区块,避免对整世界做线性扫描。
        saveCookingPotEntities(world, chunkX, chunkZ);
        saveCuttingBoardEntities(world, chunkX, chunkZ);
        // skillet / stove 仍按原坐标范围处理(超出本次范围)。
        plugin.getSkilletManager().saveAndUnloadChunk(world, minX, maxX, minZ, maxZ);
        plugin.getStoveManager().saveAndUnloadChunk(world, minX, maxX, minZ, maxZ);
    }

    private void cleanupBlockEntitiesInChunk(World world, Chunk chunk) {
        // 厨锅 / 砧板改用按区块索引,只查正在卸载的那个区块,避免对整世界做线性扫描。
        cleanupCookingPotEntities(world, chunk.getX(), chunk.getZ());
        cleanupCuttingBoardEntities(world, chunk.getX(), chunk.getZ());
    }

    private void saveCookingPotEntities(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CookingPotBlockEntity> entities =
                CookingPotBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
        if (entities.isEmpty()) return;

        for (BlockPosKey posKey : entities.keySet()) {
            CookingPotBlockBehavior.saveBlockEntityData(world, posKey);
        }
    }

    private void saveCuttingBoardEntities(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CuttingBoardBlockEntity> entities =
                CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
        if (entities.isEmpty()) return;

        for (BlockPosKey posKey : entities.keySet()) {
            CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
        }
    }

    private void cleanupCookingPotEntities(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CookingPotBlockEntity> entities =
                CookingPotBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
        if (entities.isEmpty()) return;

        // 先快照位置再移除,避免在遍历过程中改动权威 map / 索引。
        List<BlockPosKey> toRemove = new ArrayList<>(entities.keySet());
        for (BlockPosKey posKey : toRemove) {
            CookingPotBlockBehavior.removeBlockEntity(world, posKey, false);
        }
    }

    private void cleanupCuttingBoardEntities(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CuttingBoardBlockEntity> entities =
                CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
        if (entities.isEmpty()) return;

        // 先快照位置再移除,避免在遍历过程中改动权威 map / 索引。
        List<BlockPosKey> toRemove = new ArrayList<>(entities.keySet());
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
