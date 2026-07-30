package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-installed bail handle decoration for cooking pots. Opt-in via shift + empty-hand right-click,
 * removed by the same action. While the handle is present, TrayManager#shouldHaveTray returns false
 * so the auto-tray never coexists with the handle.
 *
 * <p>Tracked worlds get an orphan-sweep on startup/load and cleanup on disable/unload/reload. Handle
 * entities carry a PDC marker so the despawn paths only touch auto-placed ones (the item itself isn't
 * user-placeable).
 */
public final class HandleManager {

    private static final String DEFAULT_HANDLE_FURNITURE_ID = "farmersdelight:cooking_pot_handle";
    private static final String DEFAULT_MARKER_KEY = "auto_pot_handle";
    // Mirrors tray defaults: centered on the pot horizontally. Y=0 anchors at the pot block's bottom;
    // the CE handle definition's element {position: 0,0.5,0} then sets the visible model to the pot's
    // vertical centre, where the bail's arc extends up over the rim.
    private static final double DEFAULT_X_OFFSET = 0.5D;
    private static final double DEFAULT_Y_OFFSET = 0.0D;
    private static final double DEFAULT_Z_OFFSET = 0.5D;
    private static final String DEFAULT_TOGGLE_SOUND = "minecraft:block.lantern.place";
    private static final float DEFAULT_TOGGLE_SOUND_VOLUME = 0.7F;
    private static final float DEFAULT_TOGGLE_SOUND_PITCH = 1.0F;

    private final FarmersDelightPlugin plugin;
    private final Set<UUID> trackedWorlds = ConcurrentHashMap.newKeySet();
    private NamespacedKey handleMarkerKey;
    private Key handleFurnitureKey;
    private double xOffset;
    private double yOffset;
    private double zOffset;
    private String toggleSoundId;
    private float toggleSoundVolume;
    private float toggleSoundPitch;

    public HandleManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationSection config = plugin.getFirstConfigSection("cooking-pot.handle", "handle");
        if (config == null) {
            // Detached empty section: missing config falls back to each field's default below without
            // injecting a section the user never wrote.
            config = new org.bukkit.configuration.MemoryConfiguration();
        }
        String furnitureId = config.getString("handle-block-id", DEFAULT_HANDLE_FURNITURE_ID);
        handleFurnitureKey = Key.of(furnitureId);
        String markerKey = config.getString("marker-key", DEFAULT_MARKER_KEY);
        handleMarkerKey = createHandleMarkerKey(markerKey);
        xOffset = config.getDouble("x-offset", DEFAULT_X_OFFSET);
        yOffset = config.getDouble("y-offset", DEFAULT_Y_OFFSET);
        zOffset = config.getDouble("z-offset", DEFAULT_Z_OFFSET);
        toggleSoundId = config.getString("toggle-sound", DEFAULT_TOGGLE_SOUND);
        toggleSoundVolume = (float) config.getDouble("toggle-sound-volume", DEFAULT_TOGGLE_SOUND_VOLUME);
        toggleSoundPitch = (float) config.getDouble("toggle-sound-pitch", DEFAULT_TOGGLE_SOUND_PITCH);
    }

    /** The world location at which the handle furniture is anchored for a pot at potPos. */
    private Location getHandleLocation(World world, BlockPos potPos) {
        return new Location(world, potPos.x() + xOffset, potPos.y() + yOffset, potPos.z() + zOffset);
    }

    private NamespacedKey createHandleMarkerKey(String markerKey) {
        String key = markerKey == null || markerKey.isBlank() ? DEFAULT_MARKER_KEY : markerKey.trim();
        try {
            return new NamespacedKey(plugin, key);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(I18n.formatConsole("handle.invalid_marker_key", "key", key));
            return new NamespacedKey(plugin, DEFAULT_MARKER_KEY);
        }
    }

    // ── public API ──────────────────────────────────────────────────────────────────────────────

    /**
     * 检查烹饪锅上是否有自动放置的手柄家具。
     * 内部使用 chunk 实体列表扫描，避免 getNearbyEntities 的 Folia 线程安全问题。
     */
    public boolean hasHandle(World world, BlockPos potPos) {
        if (world == null || potPos == null) return false;
        return hasHandle(world, potPos, getChunkEntities(world, potPos));
    }

    /**
     * 使用预获取的实体列表检查手柄，避免在 Folia 区域线程上调用 getNearbyEntities。
     * 调用方（如 chunk 加载清理）已经持有实体列表，直接扫描即可。
     */
    public boolean hasHandle(World world, BlockPos potPos, List<Entity> entities) {
        if (world == null || potPos == null || entities == null) return false;
        Location handleLoc = getHandleLocation(world, potPos);
        int hx = handleLoc.getBlockX();
        int hy = handleLoc.getBlockY();
        int hz = handleLoc.getBlockZ();
        for (Entity entity : entities) {
            if (!(entity instanceof ItemDisplay)) continue;
            Location eloc = entity.getLocation();
            if (eloc.getBlockX() != hx || eloc.getBlockY() != hy || eloc.getBlockZ() != hz) continue;
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null && furniture.id().equals(handleFurnitureKey) && isAutoPlacedHandle(furniture)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Flip handle on/off at the pot. If a handle exists, remove it and let TrayManager re-evaluate tray
     * presence. If no handle exists, remove any existing tray and install the handle (variant by facing).
     * Returns true if the handle was placed (off→on), false if removed (on→off) or no-op (no pot).
     */
    public boolean toggleHandle(World world, BlockPos potPos, @Nullable Player player) {
        if (world == null || potPos == null) return false;
        Block potBlock = world.getBlockAt(potPos.x(), potPos.y(), potPos.z());
        if (!Constants.BLOCK_COOKING_POT.equals(CustomBlockUtils.getId(potBlock))) return false;
        trackWorld(world);

        List<Entity> chunkEntities = getChunkEntities(world, potPos);
        boolean had = hasHandle(world, potPos, chunkEntities);
        if (had) {
            removeHandle(world, potPos, chunkEntities);
            TrayManager trayManager = plugin.getTrayManager();
            if (trayManager != null) trayManager.checkAndPlaceTray(world, potPos);
        } else {
            TrayManager trayManager = plugin.getTrayManager();
            if (trayManager != null) trayManager.removeTrayIfAutoPlaced(world, potPos);
            placeHandleIfMissing(world, potPos, potBlock, chunkEntities);
        }
        if (player != null && toggleSoundVolume > 0) {
            SoundUtils.play(player, player.getLocation(), toggleSoundId, Sound.BLOCK_LANTERN_PLACE,
                    SoundCategory.BLOCKS, toggleSoundVolume, toggleSoundPitch);
        }
        return !had;
    }

    /** 移除烹饪锅上所有自动放置的手柄。锅被破坏/替换时调用。 */
    public void removeHandle(World world, BlockPos potPos) {
        if (world == null || potPos == null) return;
        removeHandle(world, potPos, getChunkEntities(world, potPos));
    }

    private void removeHandle(World world, BlockPos potPos, List<Entity> entities) {
        if (world == null || potPos == null || entities == null) return;
        for (BukkitFurniture furniture : findHandleFurniture(world, getHandleLocation(world, potPos), entities)) {
            if (isAutoPlacedHandle(furniture)) {
                CraftEngineFurniture.remove(furniture, true, true);
            }
        }
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────────

    /** Register a world and sweep orphan handles (handles whose pot is gone). */
    public void trackWorld(World world) {
        if (world == null) return;
        if (trackedWorlds.add(world.getUID())) {
            sweepOrphans(world);
        }
    }

    /** Called on plugin disable. Clears in-memory tracking only (entities persist). */
    public void cleanupAll() {
        trackedWorlds.clear();
    }

    /** Called on world unload. Drops the world's tracking entry. */
    public void cleanupWorld(UUID worldId) {
        if (worldId != null) trackedWorlds.remove(worldId);
    }

    /** Called on /fd reload. Re-read config and re-sweep orphans across all tracked worlds. */
    public void reload() {
        loadConfig();
        for (UUID id : trackedWorlds) {
            World world = Bukkit.getWorld(id);
            if (world != null) sweepOrphans(world);
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────────

    /** Sweep orphaned auto-placed handles per loaded chunk rather than walking the whole-world entity index
     * (that stalls the main thread in decoration-heavy worlds and crosses regions on Folia — R-PERF-006).
     * Mirrors TrayManager's per-chunk model. */
    private void sweepOrphans(World world) {
        if (world == null) return;
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            sweepOrphansInChunk(world, chunk.getX(), chunk.getZ());
        }
    }

    /** Sweeps orphaned handles in one chunk on that chunk's owning region (Folia-safe). Called per loaded chunk
     * on reload and on chunk load, so a handle whose pot vanished off-thread is cleaned when its chunk is next
     * present, without a whole-world scan. */
    public void sweepOrphansInChunk(World world, int chunkX, int chunkZ) {
        if (world == null) return;
        plugin.scheduler().runAt(world, chunkX, chunkZ, () -> sweepOrphansInChunkNow(world, chunkX, chunkZ));
    }

    private void sweepOrphansInChunkNow(World world, int chunkX, int chunkZ) {
        if (world == null || !world.isChunkLoaded(chunkX, chunkZ)) return;
        sweepOrphansIn(world, java.util.Arrays.asList(world.getChunkAt(chunkX, chunkZ).getEntities()));
    }

    /**
     * Sweeps an already-fetched entity list instead of fetching a chunk's own. Lets a caller that has to
     * walk the same chunk's entities for another purpose share one walk; the caller is responsible for
     * having confirmed the chunk is loaded and for being on the region that owns it.
     */
    public void sweepOrphansIn(World world, List<Entity> entities) {
        if (world == null || entities == null) return;
        for (Entity entity : entities) {
            if (!(entity instanceof ItemDisplay display)) continue;
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
            if (furniture == null) continue;
            if (!furniture.id().equals(handleFurnitureKey)) continue;
            if (!isAutoPlacedHandle(furniture)) continue;

            Location loc = display.getLocation();
            Block potBlock = world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            if (!Constants.BLOCK_COOKING_POT.equals(CustomBlockUtils.getId(potBlock))) {
                CraftEngineFurniture.remove(furniture, true, true);
            }
        }
    }

    private void placeHandleIfMissing(World world, BlockPos potPos, Block potBlock, List<Entity> entities) {
        Location handleLoc = getHandleLocation(world, potPos);
        for (BukkitFurniture existing : findHandleFurniture(world, handleLoc, entities)) {
            if (isAutoPlacedHandle(existing)) return;
        }
        String variant = facingVariant(potBlock);
        BukkitFurniture furniture;
        try {
            furniture = CraftEngineFurniture.place(handleLoc, handleFurnitureKey, variant);
        } catch (Exception e) {
            I18n.logWarning("plugin.handle_place_failed", "anchor", handleLoc, "error", e.getMessage());
            return;
        }
        if (furniture == null) return;
        markHandle(furniture);
    }

    private String facingVariant(Block potBlock) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(potBlock);
        if (state != null && !state.isEmpty()) {
            Property<?> facing = state.owner().value().getProperty("facing");
            if (facing != null) {
                Object value = state.get(facing);
                if (value != null) {
                    String name = value.toString().toLowerCase();
                    if ("north".equals(name) || "east".equals(name) || "south".equals(name) || "west".equals(name)) {
                        return name;
                    }
                }
            }
        }
        return "north";
    }

    private void markHandle(BukkitFurniture furniture) {
        Entity entity = furniture.bukkitEntity();
        if (entity == null) return;
        entity.getPersistentDataContainer().set(handleMarkerKey, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isAutoPlacedHandle(@Nullable BukkitFurniture furniture) {
        if (furniture == null) return false;
        Entity entity = furniture.bukkitEntity();
        return entity != null
                && entity.getPersistentDataContainer().has(handleMarkerKey, PersistentDataType.BYTE);
    }

    private Collection<BukkitFurniture> findHandleFurniture(World world, Location handleLoc) {
        return findHandleFurniture(world, handleLoc, getChunkEntities(world, handleLoc));
    }

    /**
     * 使用预获取的实体列表查找手柄家具，避免在 Folia 区域线程上调用 getNearbyEntities。
     */
    private Collection<BukkitFurniture> findHandleFurniture(World world, Location handleLoc, List<Entity> entities) {
        List<BukkitFurniture> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        if (world == null || handleLoc == null || entities == null) return result;
        Location searchCentre = handleLoc.clone().add(0, 0.5, 0);
        double minX = searchCentre.getX() - 0.55;
        double minY = searchCentre.getY() - 0.55;
        double minZ = searchCentre.getZ() - 0.55;
        double maxX = searchCentre.getX() + 0.55;
        double maxY = searchCentre.getY() + 0.55;
        double maxZ = searchCentre.getZ() + 0.55;
        for (Entity entity : entities) {
            if (!(entity instanceof ItemDisplay)) continue;
            Location eloc = entity.getLocation();
            if (eloc.getX() < minX || eloc.getX() > maxX
                    || eloc.getY() < minY || eloc.getY() > maxY
                    || eloc.getZ() < minZ || eloc.getZ() > maxZ) continue;
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null) continue;
            if (!furniture.id().equals(handleFurnitureKey)) continue;
            Entity root = furniture.bukkitEntity();
            if (root != null && seen.add(root.getUniqueId())) {
                result.add(furniture);
            }
        }
        return result;
    }

    /** 从 BlockPos 所在 chunk 获取实体列表，chunk 未加载时返回空列表。 */
    private List<Entity> getChunkEntities(World world, BlockPos pos) {
        if (world == null || pos == null) return List.of();
        int cx = pos.x() >> 4;
        int cz = pos.z() >> 4;
        if (!world.isChunkLoaded(cx, cz)) return List.of();
        return java.util.Arrays.asList(world.getChunkAt(cx, cz).getEntities());
    }

    /** 从 Location 所在 chunk 获取实体列表，chunk 未加载时返回空列表。 */
    private List<Entity> getChunkEntities(World world, Location loc) {
        if (world == null || loc == null) return List.of();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) return List.of();
        return java.util.Arrays.asList(world.getChunkAt(cx, cz).getEntities());
    }
}
