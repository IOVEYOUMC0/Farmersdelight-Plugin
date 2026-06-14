package com.huidu.farmersdelight.storage;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LegacyBlockStorageManager {

    private final FarmersDelightPlugin plugin;
    private final File storageFile;
    private final Map<String, Map<String, BlockData>> worldData = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, Map<String, BlockData>>> chunkIndex = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();
    private final AtomicLong dataVersion = new AtomicLong();
    private final AtomicLong lastSavedVersion = new AtomicLong();
    private PluginTask autoSaveTask;
    private volatile boolean asyncSaveRunning;
    private boolean legacyItemFormatLoaded;

    public LegacyBlockStorageManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "block_storage.yml");
        loadData();
        startAutoSave();
    }

    private void startAutoSave() {
        int saveInterval = plugin.getConfig().getInt("storage.auto-save-interval", 300);
        if (saveInterval > 0) {
            autoSaveTask = plugin.scheduler().runRepeating(this::saveAllAsync,
                    saveInterval * 20L, saveInterval * 20L);
        }
    }

    public void saveAllAsync() {
        if (asyncSaveRunning) {
            return;
        }

        SaveSnapshot snapshot = createSaveSnapshot();
        if (snapshot == null) {
            return;
        }

        asyncSaveRunning = true;
        plugin.scheduler().runAsync(() -> {
            try {
                writeSnapshot(snapshot);
            } finally {
                asyncSaveRunning = false;
            }
        });
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

    private Map<String, Object> copyDataMap(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>();
        if (source == null || source.isEmpty()) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyStoredValue(entry.getValue()));
        }
        return copy;
    }

    private Map<String, Object> copyBlockDataMap(BlockData blockData) {
        return copyDataMap(blockData.data);
    }

    private Object copyStoredValue(Object value) {
        if (value instanceof ItemStack itemStack) {
            return itemStack.clone();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), copyStoredValue(entry.getValue()));
                }
            }
            return copy;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> copy = new java.util.ArrayList<>();
            for (Object item : iterable) {
                copy.add(copyStoredValue(item));
            }
            return copy;
        }
        return value;
    }

    private Map<String, Object> toBlockDataView(BlockData blockData, ParsedPos pos) {
        Map<String, Object> view = copyBlockDataMap(blockData);
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

    private boolean deindexBlockData(String worldId, String posKey) {
        ParsedPos pos = parsePosKey(posKey);
        if (pos == null) {
            return false;
        }
        Map<Long, Map<String, BlockData>> worldChunks = chunkIndex.get(worldId);
        if (worldChunks == null) {
            return false;
        }
        long chunkKey = chunkKeyForBlock(pos.x, pos.z);
        Map<String, BlockData> chunkBlocks = worldChunks.get(chunkKey);
        if (chunkBlocks == null) {
            return false;
        }
        boolean removed = chunkBlocks.remove(posKey) != null;
        if (chunkBlocks.isEmpty()) {
            worldChunks.remove(chunkKey);
        }
        if (worldChunks.isEmpty()) {
            chunkIndex.remove(worldId);
        }
        return removed;
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
            BlockData blockData = new BlockData(blockType, copyDataMap(data));
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

        dataLock.readLock().lock();
        try {
            Map<String, BlockData> worldBlocks = worldData.get(worldId);
            if (worldBlocks == null) return null;

            BlockData data = worldBlocks.get(posKey);
            if (data == null) return null;

            if (expectedType != null && !expectedType.equals(data.blockType)) {
                return null;
            }

            return copyBlockDataMap(data);
        } finally {
            dataLock.readLock().unlock();
        }
    }

    /**
     * 廉价判断某位置是否存有遗留方块数据(只取读锁,不修改)。供破坏方块的热路径在真正调用
     * removeBlockData(写锁)前做前置判断,避免对普通方块也白白获取全局写锁。
     * chunkIndex 始终与 worldData 同步写入/删除,因此查 worldData 即为权威判断。
     */
    public boolean hasBlockData(Location location) {
        if (location == null || location.getWorld() == null) return false;

        String worldId = location.getWorld().getUID().toString();
        String posKey = posToKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        dataLock.readLock().lock();
        try {
            Map<String, BlockData> worldBlocks = worldData.get(worldId);
            return worldBlocks != null && worldBlocks.containsKey(posKey);
        } finally {
            dataLock.readLock().unlock();
        }
    }

    public void removeBlockData(Location location) {
        if (location == null || location.getWorld() == null) return;

        String worldId = location.getWorld().getUID().toString();
        String posKey = posToKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        dataLock.writeLock().lock();
        try {
            boolean removed = false;
            Map<String, BlockData> worldBlocks = worldData.get(worldId);
            if (worldBlocks != null) {
                removed = worldBlocks.remove(posKey) != null;
                if (worldBlocks.isEmpty()) {
                    worldData.remove(worldId);
                }
            }
            removed |= deindexBlockData(worldId, posKey);
            if (removed) {
                dataVersion.incrementAndGet();
            }
        } finally {
            dataLock.writeLock().unlock();
        }
    }

    public void saveAll() {
        SaveSnapshot snapshot = createSaveSnapshot();
        if (snapshot == null) {
            return;
        }
        writeSnapshot(snapshot);
    }

    private SaveSnapshot createSaveSnapshot() {
        long currentVersion = dataVersion.get();
        if (currentVersion == lastSavedVersion.get()) {
            return null;
        }

        Map<String, Map<String, SerializedBlockData>> snapshot;
        dataLock.readLock().lock();
        try {
            snapshot = new HashMap<>();
            for (Map.Entry<String, Map<String, BlockData>> entry : worldData.entrySet()) {
                Map<String, SerializedBlockData> worldSnapshot = new HashMap<>();
                for (Map.Entry<String, BlockData> blockEntry : entry.getValue().entrySet()) {
                    worldSnapshot.put(blockEntry.getKey(), serializeBlockData(blockEntry.getValue()));
                }
                snapshot.put(entry.getKey(), worldSnapshot);
            }
        } finally {
            dataLock.readLock().unlock();
        }
        return new SaveSnapshot(currentVersion, snapshot);
    }

    private SerializedBlockData serializeBlockData(BlockData blockData) {
        Map<String, SerializedItemData> items = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        for (Map.Entry<String, Object> entry : blockData.data().entrySet()) {
            Object value = entry.getValue();
            try {
                if (value instanceof ItemStack itemStack) {
                    items.put(entry.getKey(), new SerializedItemData(
                            itemStack.getType().name(),
                            itemStack.getAmount(),
                            serializeItemStack(itemStack)
                    ));
                } else if (value instanceof Integer || value instanceof Double || value instanceof Float
                        || value instanceof Long || value instanceof Boolean || value instanceof String) {
                    data.put(entry.getKey(), value);
                } else if (value != null) {
                    data.put(entry.getKey(), value.toString());
                }
            } catch (Exception e) {
                I18n.logWarning("legacy_storage.save_data_failed",
                        "key", entry.getKey(),
                        "error", e.getMessage());
            }
        }
        return new SerializedBlockData(blockData.blockType(), items, data);
    }

    private synchronized void writeSnapshot(SaveSnapshot snapshot) {
        long currentVersion = snapshot.version();

        if (snapshot.worldData().isEmpty()) {
            deleteStorageFiles(currentVersion);
            return;
        }

        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, Map<String, SerializedBlockData>> worldEntry : snapshot.worldData().entrySet()) {
            String worldId = worldEntry.getKey();
            ConfigurationSection worldSection = config.createSection("worlds." + worldId);

            for (Map.Entry<String, SerializedBlockData> posEntry : worldEntry.getValue().entrySet()) {
                String posKey = posEntry.getKey();
                SerializedBlockData blockData = posEntry.getValue();

                ConfigurationSection blockSection = worldSection.createSection(posKey);
                blockSection.set("type", blockData.blockType);

                for (Map.Entry<String, SerializedItemData> itemEntry : blockData.items().entrySet()) {
                    SerializedItemData item = itemEntry.getValue();
                    ConfigurationSection itemSection = blockSection.createSection("items." + itemEntry.getKey());
                    itemSection.set("material", item.material());
                    itemSection.set("amount", item.amount());
                    itemSection.set("bytes", item.bytes());
                }

                for (Map.Entry<String, Object> dataEntry : blockData.data().entrySet()) {
                    blockSection.set("data." + dataEntry.getKey(), dataEntry.getValue());
                }
            }
        }

        File tempFile = new File(plugin.getDataFolder(), "block_storage.tmp");
        File backupFile = new File(plugin.getDataFolder(), "block_storage.bak");

        boolean preserveTemp = false;
        try {
            config.save(tempFile);

            if (!tempFile.exists()) {
                throw new IOException("Temp file was not created");
            }

            if (storageFile.exists()) {
                    if (backupFile.exists()) {
                        if (!backupFile.delete()) {
                            I18n.logWarning("legacy_storage.delete_old_backup_failed");
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

            plugin.getLogger().fine(I18n.formatConsole("legacy_storage.save_success"));

        } catch (IOException e) {
            I18n.logSevere("legacy_storage.save_failed", "error", e.getMessage());

            boolean restored = false;
            if (backupFile.exists() && !storageFile.exists()) {
                if (backupFile.renameTo(storageFile)) {
                    restored = true;
                    I18n.logInfo("legacy_storage.restored_backup");
                } else {
                    I18n.logSevere("legacy_storage.restore_backup_failed");
                }
            }

            if (!restored && tempFile.exists()) {
                // 我们有意保留刚写入的临时文件以便手动恢复；
                // finally 代码块绝不能删除它（它是该数据唯一的最新副本）。
                preserveTemp = true;
                I18n.logWarning("legacy_storage.preserve_temp", "path", tempFile.getAbsolutePath());
            }
        } finally {
            if (!preserveTemp && tempFile.exists() && storageFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void deleteStorageFiles(long currentVersion) {
        File tempFile = new File(plugin.getDataFolder(), "block_storage.tmp");
        File backupFile = new File(plugin.getDataFolder(), "block_storage.bak");
        deleteFileIfExists(tempFile);
        deleteFileIfExists(backupFile);
        deleteFileIfExists(storageFile);
        lastSavedVersion.set(currentVersion);
    }

    private void deleteFileIfExists(File file) {
        if (file.exists() && !file.delete()) {
            I18n.logWarning("legacy_storage.delete_file_failed", "file", file.getName());
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
        lastSavedVersion.set(legacyItemFormatLoaded ? -1L : loadedVersion);
    }

    private String serializeItemStack(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private ItemStack deserializeItemStack(ConfigurationSection section) {
        try {
            String bytes = section.getString("bytes");
            if (bytes != null && !bytes.isEmpty()) {
                return deserializeModernItemStack(bytes);
            }

            String materialName = section.getString("material");
            if (materialName == null) return null;

            org.bukkit.Material material = org.bukkit.Registry.MATERIAL.get(
                    org.bukkit.NamespacedKey.minecraft(materialName.toLowerCase(java.util.Locale.ROOT)));
            if (material == null) return null;
            int amount = section.getInt("amount", 1);

            String metaStr = section.getString("meta");
            if (metaStr != null && !metaStr.isEmpty()) {
                legacyItemFormatLoaded = true;
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
            plugin.getLogger().fine(I18n.formatConsole("legacy_storage.deserialize_item_failed",
                    "error", e.getMessage()));
            return null;
        }
    }

    private ItemStack deserializeModernItemStack(String encodedBytes) throws IOException {
        byte[] raw = Base64.getDecoder().decode(encodedBytes);

        // 如果将来需要，支持一种带长度前缀的封装格式，
        // 同时保持当前直接的 ItemStack.serializeAsBytes() 数据简单。
        if (looksLikeLengthPrefixedPayload(raw)) {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw))) {
                int len = dis.readInt();
                if (len > 0 && len <= raw.length - Integer.BYTES) {
                    byte[] itemBytes = new byte[len];
                    dis.readFully(itemBytes);
                    return ItemStack.deserializeBytes(itemBytes);
                }
            }
        }

        return ItemStack.deserializeBytes(raw);
    }

    private boolean looksLikeLengthPrefixedPayload(byte[] raw) {
        if (raw.length <= Integer.BYTES) {
            return false;
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw))) {
            int len = dis.readInt();
            return len > 0 && len <= raw.length - Integer.BYTES;
        } catch (IOException ignored) {
            return false;
        }
    }

    public void cleanupWorld(UUID worldId) {
        String worldIdStr = worldId.toString();
        Map<String, BlockData> worldBlocks = worldData.get(worldIdStr);
        if (worldBlocks == null || worldBlocks.isEmpty()) {
            worldData.remove(worldIdStr);
            chunkIndex.remove(worldIdStr);
        }
        // 在世界卸载后仍将非空的旧版记录保留在内存中。它们只包含
        // 序列化后的数据，下一次异步/完整保存可以重写该文件，
        // 而不会阻塞卸载事件或丢失尚未迁移的条目。
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
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().fine(I18n.formatConsole("legacy_storage.invalid_position", "pos", posKey));
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
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().fine(I18n.formatConsole("legacy_storage.invalid_position", "pos", posKey));
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

    private record SerializedBlockData(String blockType, Map<String, SerializedItemData> items,
                                       Map<String, Object> data) {
    }

    private record SerializedItemData(String material, int amount, String bytes) {
    }

    private record SaveSnapshot(long version, Map<String, Map<String, SerializedBlockData>> worldData) {
    }
}
