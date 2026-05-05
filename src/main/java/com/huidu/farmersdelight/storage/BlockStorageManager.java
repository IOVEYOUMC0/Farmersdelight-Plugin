package com.huidu.farmersdelight.storage;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BlockStorageManager {

    private final FarmersDelightPlugin plugin;
    private final File storageFile;
    private final Map<String, Map<String, BlockData>> worldData = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, Map<String, BlockData>>> chunkIndex = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();
    private final AtomicLong dataVersion = new AtomicLong();
    private final AtomicLong lastSavedVersion = new AtomicLong();
    private BukkitTask autoSaveTask;

    public BlockStorageManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "block_storage.yml");
        loadData();
        startAutoSave();
    }

    private void startAutoSave() {
        int saveInterval = plugin.getConfig().getInt("storage.auto-save-interval", 300);
        if (saveInterval > 0) {
            autoSaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll,
                    saveInterval * 20L, saveInterval * 20L);
        }
    }

    public void saveAllAsync() {
        Bukkit.getScheduler().runTask(plugin, this::saveAll);
    }

    public void saveAllSync() {
        saveAll();
    }

    private String posToKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private String locationToKey(World world, int x, int y, int z) {
        return world.getUID() + ":" + posToKey(x, y, z);
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private long chunkKeyForBlock(int x, int z) {
        return chunkKey(x >> 4, z >> 4);
    }

    private ParsedPos parsePosKey(String posKey) {
        String[] parts = posKey.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new ParsedPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> toBlockDataView(BlockData blockData, ParsedPos pos) {
        Map<String, Object> view = new HashMap<>(blockData.data);
        view.put("_blockType", blockData.blockType);
        view.put("_x", pos.x);
        view.put("_y", pos.y);
        view.put("_z", pos.z);
        return view;
    }

    private void indexBlockData(String worldId, String posKey, BlockData data) {
        ParsedPos pos = parsePosKey(posKey);
        if (pos == null) {
            return;
        }
        chunkIndex
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKeyForBlock(pos.x, pos.z), ignored -> new ConcurrentHashMap<>())
                .put(posKey, data);
    }

    private void deindexBlockData(String worldId, String posKey) {
        ParsedPos pos = parsePosKey(posKey);
        if (pos == null) {
            return;
        }
        Map<Long, Map<String, BlockData>> worldChunks = chunkIndex.get(worldId);
        if (worldChunks == null) {
            return;
        }
        long chunkKey = chunkKeyForBlock(pos.x, pos.z);
        Map<String, BlockData> chunkBlocks = worldChunks.get(chunkKey);
        if (chunkBlocks == null) {
            return;
        }
        chunkBlocks.remove(posKey);
        if (chunkBlocks.isEmpty()) {
            worldChunks.remove(chunkKey);
        }
        if (worldChunks.isEmpty()) {
            chunkIndex.remove(worldId);
        }
    }

    public String locationToKey(Location loc) {
        return locationToKey(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public void saveBlockData(Location location, String blockType, Map<String, Object> data) {
        if (location == null || location.getWorld() == null) return;

        String worldId = location.getWorld().getUID().toString();
        String posKey = posToKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        dataLock.writeLock().lock();
        try {
            BlockData blockData = new BlockData(blockType, data);
            worldData.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>())
                    .put(posKey, blockData);
            indexBlockData(worldId, posKey, blockData);
            dataVersion.incrementAndGet();
        } finally {
            dataLock.writeLock().unlock();
        }
    }

    public Map<String, Object> loadBlockData(Location location, String expectedType) {
        if (location == null || location.getWorld() == null) return null;

        String worldId = location.getWorld().getUID().toString();
        String posKey = posToKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        Map<String, BlockData> worldBlocks = worldData.get(worldId);
        if (worldBlocks == null) return null;

        BlockData data = worldBlocks.get(posKey);
        if (data == null) return null;

        if (expectedType != null && !expectedType.equals(data.blockType)) {
            return null;
        }

        return data.data;
    }

    public void removeBlockData(Location location) {
        if (location == null || location.getWorld() == null) return;

        String worldId = location.getWorld().getUID().toString();
        String posKey = posToKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        dataLock.writeLock().lock();
        try {
            Map<String, BlockData> worldBlocks = worldData.get(worldId);
            if (worldBlocks != null) {
                worldBlocks.remove(posKey);
                if (worldBlocks.isEmpty()) {
                    worldData.remove(worldId);
                }
            }
            deindexBlockData(worldId, posKey);
            dataVersion.incrementAndGet();
        } finally {
            dataLock.writeLock().unlock();
        }
    }

    public void saveAll() {
        long currentVersion = dataVersion.get();
        if (currentVersion == lastSavedVersion.get()) {
            return;
        }

        Map<String, Map<String, BlockData>> snapshot;
        dataLock.readLock().lock();
        try {
            snapshot = new HashMap<>();
            for (Map.Entry<String, Map<String, BlockData>> entry : worldData.entrySet()) {
                snapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
        } finally {
            dataLock.readLock().unlock();
        }

        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, Map<String, BlockData>> worldEntry : snapshot.entrySet()) {
            String worldId = worldEntry.getKey();
            ConfigurationSection worldSection = config.createSection("worlds." + worldId);

            for (Map.Entry<String, BlockData> posEntry : worldEntry.getValue().entrySet()) {
                String posKey = posEntry.getKey();
                BlockData blockData = posEntry.getValue();

                ConfigurationSection blockSection = worldSection.createSection(posKey);
                blockSection.set("type", blockData.blockType);

                for (Map.Entry<String, Object> dataEntry : blockData.data.entrySet()) {
                    try {
                        Object value = dataEntry.getValue();
                        if (value instanceof ItemStack itemStack) {
                            ConfigurationSection itemSection = blockSection.createSection("items." + dataEntry.getKey());
                            itemSection.set("material", itemStack.getType().name());
                            itemSection.set("amount", itemStack.getAmount());
                            if (itemStack.hasItemMeta()) {
                                itemSection.set("meta", serializeItemMeta(itemStack));
                            }
                        } else if (value instanceof Integer || value instanceof Double || value instanceof Float
                                || value instanceof Long || value instanceof Boolean || value instanceof String) {
                            blockSection.set("data." + dataEntry.getKey(), value);
                        } else {
                            blockSection.set("data." + dataEntry.getKey(), value.toString());
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to save data " + dataEntry.getKey() + ": " + e.getMessage());
                    }
                }
            }
        }

        File tempFile = new File(plugin.getDataFolder(), "block_storage.tmp");
        File backupFile = new File(plugin.getDataFolder(), "block_storage.bak");

        try {
            config.save(tempFile);

            if (!tempFile.exists()) {
                throw new IOException("Temp file was not created");
            }

            if (storageFile.exists()) {
                if (backupFile.exists()) {
                    if (!backupFile.delete()) {
                        plugin.getLogger().warning("Failed to delete old backup file");
                    }
                }
                if (!storageFile.renameTo(backupFile)) {
                    throw new IOException("Failed to create backup file");
                }
            }

            if (!tempFile.renameTo(storageFile)) {
                throw new IOException("Failed to rename temp file to storage file");
            }
            lastSavedVersion.set(currentVersion);

            plugin.getLogger().fine("Block storage saved successfully");

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save block storage: " + e.getMessage());

            boolean restored = false;
            if (backupFile.exists() && !storageFile.exists()) {
                if (backupFile.renameTo(storageFile)) {
                    restored = true;
                    plugin.getLogger().info("Restored block storage from backup");
                } else {
                    plugin.getLogger().severe("Failed to restore block storage from backup!");
                }
            }

            if (!restored && tempFile.exists()) {
                plugin.getLogger().warning("Attempting to preserve temp file for manual recovery: " + tempFile.getAbsolutePath());
            }
        } finally {
            if (tempFile.exists() && storageFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void loadData() {
        if (!storageFile.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection == null) return;

        for (String worldId : worldsSection.getKeys(false)) {
            ConfigurationSection worldSection = worldsSection.getConfigurationSection(worldId);
            if (worldSection == null) continue;

            Map<String, BlockData> worldBlocks = new ConcurrentHashMap<>();

            for (String posKey : worldSection.getKeys(false)) {
                ConfigurationSection blockSection = worldSection.getConfigurationSection(posKey);
                if (blockSection == null) continue;

                String blockType = blockSection.getString("type");
                Map<String, Object> data = new HashMap<>();

                ConfigurationSection itemsSection = blockSection.getConfigurationSection("items");
                if (itemsSection != null) {
                    for (String itemKey : itemsSection.getKeys(false)) {
                        ConfigurationSection itemSection = itemsSection.getConfigurationSection(itemKey);
                        if (itemSection != null) {
                            ItemStack item = deserializeItemStack(itemSection);
                            if (item != null) {
                                data.put(itemKey, item);
                            }
                        }
                    }
                }

                ConfigurationSection dataSection = blockSection.getConfigurationSection("data");
                if (dataSection != null) {
                    for (String dataKey : dataSection.getKeys(false)) {
                        Object value = dataSection.get(dataKey);
                        if (value instanceof Number num) {
                            if (dataKey.contains("Experience") || dataKey.contains("experience")) {
                                data.put(dataKey, num.floatValue());
                            } else if (dataKey.contains("Progress") || dataKey.contains("progress")
                                    || dataKey.contains("Time") || dataKey.contains("time")
                                    || dataKey.contains("Duration") || dataKey.contains("duration")) {
                                data.put(dataKey, num.intValue());
                            } else {
                                data.put(dataKey, value);
                            }
                        } else {
                            data.put(dataKey, value);
                        }
                    }
                }

                worldBlocks.put(posKey, new BlockData(blockType, data));
            }

            worldData.put(worldId, worldBlocks);
            Map<Long, Map<String, BlockData>> worldChunks = new ConcurrentHashMap<>();
            for (Map.Entry<String, BlockData> entry : worldBlocks.entrySet()) {
                String posKey = entry.getKey();
                ParsedPos pos = parsePosKey(posKey);
                if (pos == null) {
                    continue;
                }
                worldChunks
                        .computeIfAbsent(chunkKeyForBlock(pos.x, pos.z), ignored -> new ConcurrentHashMap<>())
                        .put(posKey, entry.getValue());
            }
            if (!worldChunks.isEmpty()) {
                chunkIndex.put(worldId, worldChunks);
            }
        }
        long loadedVersion = dataVersion.incrementAndGet();
        lastSavedVersion.set(loadedVersion);
    }

    private String serializeItemMeta(ItemStack item) {
        YamlConfiguration temp = new YamlConfiguration();
        temp.set("item", item);
        return temp.saveToString();
    }

    private ItemStack deserializeItemStack(ConfigurationSection section) {
        try {
            String materialName = section.getString("material");
            if (materialName == null) return null;

            org.bukkit.Material material = org.bukkit.Material.valueOf(materialName);
            int amount = section.getInt("amount", 1);

            String metaStr = section.getString("meta");
            if (metaStr != null && !metaStr.isEmpty()) {
                YamlConfiguration temp = new YamlConfiguration();
                temp.loadFromString(metaStr);
                ItemStack loadedItem = temp.getItemStack("item");
                if (loadedItem != null) {
                    loadedItem.setAmount(amount);
                    return loadedItem;
                }
            }

            return new ItemStack(material, amount);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to deserialize ItemStack: " + e.getMessage());
            return null;
        }
    }

    public void cleanupWorld(UUID worldId) {
        String worldIdStr = worldId.toString();
        Map<String, BlockData> worldBlocks = worldData.get(worldIdStr);
        if (worldBlocks == null || worldBlocks.isEmpty()) {
            worldData.remove(worldIdStr);
            chunkIndex.remove(worldIdStr);
            return;
        }

        saveWorldDataToFile(worldIdStr, worldBlocks);

        worldData.remove(worldIdStr);
        chunkIndex.remove(worldIdStr);
        dataVersion.incrementAndGet();
    }

    private void saveWorldDataToFile(String worldIdStr, Map<String, BlockData> worldBlocks) {
        YamlConfiguration config = new YamlConfiguration();

        try {
            YamlConfiguration existingConfig = YamlConfiguration.loadConfiguration(storageFile);
            for (String key : existingConfig.getKeys(false)) {
                config.set(key, existingConfig.get(key));
            }
        } catch (Exception e) {
            plugin.getLogger().fine("No existing config to merge: " + e.getMessage());
        }

        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection == null) {
            worldsSection = config.createSection("worlds");
        }

        // Rebuild the world section from the in-memory snapshot so removed blocks
        // do not linger on disk and get resurrected on the next load.
        worldsSection.set(worldIdStr, null);
        ConfigurationSection worldSection = worldsSection.createSection(worldIdStr);

        for (Map.Entry<String, BlockData> posEntry : worldBlocks.entrySet()) {
            String posKey = posEntry.getKey();
            BlockData blockData = posEntry.getValue();

            ConfigurationSection blockSection = worldSection.createSection(posKey);
            blockSection.set("type", blockData.blockType);

            for (Map.Entry<String, Object> dataEntry : blockData.data.entrySet()) {
                try {
                    Object value = dataEntry.getValue();
                    if (value instanceof ItemStack itemStack) {
                        ConfigurationSection itemSection = blockSection.createSection("items." + dataEntry.getKey());
                        itemSection.set("material", itemStack.getType().name());
                        itemSection.set("amount", itemStack.getAmount());
                        if (itemStack.hasItemMeta()) {
                            itemSection.set("meta", serializeItemMeta(itemStack));
                        }
                    } else if (value instanceof Integer || value instanceof Double || value instanceof Float
                            || value instanceof Long || value instanceof Boolean || value instanceof String) {
                        blockSection.set("data." + dataEntry.getKey(), value);
                    } else {
                        blockSection.set("data." + dataEntry.getKey(), value.toString());
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save data " + dataEntry.getKey() + ": " + e.getMessage());
                }
            }
        }

        try {
            config.save(storageFile);
            lastSavedVersion.set(dataVersion.get());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save block storage during world cleanup: " + e.getMessage());
        }
    }

    public void cleanupAll() {
        worldData.clear();
        chunkIndex.clear();
        dataVersion.incrementAndGet();
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        saveAll();
    }

    public Map<String, Map<String, Object>> loadBlockDataInChunk(World world, int chunkX, int chunkZ) {
        if (world == null) return Map.of();

        String worldId = world.getUID().toString();
        Map<Long, Map<String, BlockData>> worldChunks = chunkIndex.get(worldId);
        if (worldChunks == null) return Map.of();

        Map<String, BlockData> chunkBlocks = worldChunks.get(chunkKey(chunkX, chunkZ));
        if (chunkBlocks == null || chunkBlocks.isEmpty()) return Map.of();

        Map<String, Map<String, Object>> result = new HashMap<>();

        for (Map.Entry<String, BlockData> entry : chunkBlocks.entrySet()) {
            String posKey = entry.getKey();
            ParsedPos pos = parsePosKey(posKey);
            if (pos == null) {
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().fine("Invalid position format in block data: " + posKey);
                }
                continue;
            }
            result.put(worldId + ":" + posKey, toBlockDataView(entry.getValue(), pos));
        }

        return result;
    }

    public Map<String, Map<String, Object>> getAllBlockDataInWorld(World world) {
        if (world == null) return Map.of();

        String worldId = world.getUID().toString();
        Map<String, BlockData> worldBlocks = worldData.get(worldId);
        if (worldBlocks == null) return Map.of();

        Map<String, Map<String, Object>> result = new HashMap<>();

        for (Map.Entry<String, BlockData> entry : worldBlocks.entrySet()) {
            String posKey = entry.getKey();
            ParsedPos pos = parsePosKey(posKey);
            if (pos == null) {
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().fine("Invalid position format in block data: " + posKey);
                }
                continue;
            }
            result.put(worldId + ":" + posKey, toBlockDataView(entry.getValue(), pos));
        }

        return result;
    }

    private record ParsedPos(int x, int y, int z) {
    }

    private record BlockData(String blockType, Map<String, Object> data) {
    }
}
