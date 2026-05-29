package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import it.unimi.dsi.fastutil.ints.IntList;
import net.momirealms.craftengine.bukkit.entity.data.BaseEntityData;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundTeleportEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypeProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.PositionMoveRotationProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import net.momirealms.craftengine.core.util.MiscUtils;
import net.momirealms.craftengine.core.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyItemDisplayManager implements Listener, ItemDisplayManager {

    private static final double DEFAULT_VIEW_DISTANCE = 64.0D;
    private static final int DEFAULT_SYNC_INTERVAL_TICKS = 20;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 256;

    private final FarmersDelightPlugin plugin;
    private final BukkitNetworkManager networkManager;
    private final AtomicInteger nextEntityId = new AtomicInteger(2_000_000);
    private final Map<Integer, ProxyItemDisplay> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Player> onlinePlayers = new ConcurrentHashMap<>();
    private final Set<Integer> scheduledDisplaySyncs = ConcurrentHashMap.newKeySet();
    private final AtomicLong displaySnapshotVersion = new AtomicLong();
    private volatile List<ProxyItemDisplay> displaySnapshot = List.of();
    private volatile long displaySnapshotCachedVersion = -1L;
    private PluginTask syncTask;
    private double viewDistance = DEFAULT_VIEW_DISTANCE;
    private double viewDistanceSquared = DEFAULT_VIEW_DISTANCE * DEFAULT_VIEW_DISTANCE;
    private int syncIntervalTicks = DEFAULT_SYNC_INTERVAL_TICKS;
    private int syncBatchSize = DEFAULT_SYNC_BATCH_SIZE;
    private int syncCursor;

    public ProxyItemDisplayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        if (craftEngine != null) {
            this.networkManager = craftEngine.networkManager();
        } else {
            this.networkManager = null;
        }
        if (isAvailable()) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            for (Player player : Bukkit.getOnlinePlayers()) {
                onlinePlayers.put(player.getUniqueId(), player);
            }
            reload();
            startSyncTask();
        }
    }

    @Override
    public boolean isAvailable() {
        return networkManager != null;
    }

    public void reload() {
        viewDistance = Math.max(8.0D, plugin.getConfig().getDouble(
                "performance.proxy-item-display-view-distance", DEFAULT_VIEW_DISTANCE));
        viewDistanceSquared = viewDistance * viewDistance;
        syncIntervalTicks = Math.max(1, plugin.getConfig().getInt(
                "performance.proxy-item-display-sync-interval-ticks", DEFAULT_SYNC_INTERVAL_TICKS));
        syncBatchSize = Math.max(1, plugin.getConfig().getInt(
                "performance.proxy-item-display-sync-batch-size", DEFAULT_SYNC_BATCH_SIZE));
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
            startSyncTask();
        }
    }

    @Override
    public int createDisplay(DisplaySpec spec) {
        if (!isAvailable() || spec == null || spec.location() == null || spec.location().getWorld() == null) {
            return -1;
        }

        DisplaySpec normalizedSpec = normalize(spec);
        int entityId = nextEntityId.getAndIncrement();
        UUID entityUuid = UUID.randomUUID();
        Object spawnPacket = createSpawnPacket(entityId, entityUuid, normalizedSpec);
        Object metadataPacket = createMetadataPacket(entityId, normalizedSpec);
        Object destroyPacket = createDestroyPacket(entityId);
        ProxyItemDisplay display = new ProxyItemDisplay(
                entityId,
                entityUuid,
                normalizedSpec,
                spawnPacket,
                metadataPacket,
                destroyPacket
        );
        displays.put(entityId, display);
        markDisplaySnapshotDirty();
        scheduleSyncDisplay(display);
        return entityId;
    }

    @Override
    public boolean updateDisplay(int entityId, DisplaySpec spec) {
        if (!isAvailable() || spec == null || spec.location() == null || spec.location().getWorld() == null) {
            return false;
        }

        ProxyItemDisplay display = displays.get(entityId);
        if (display == null) {
            return false;
        }

        DisplaySpec normalizedSpec = normalize(spec);
        display.spec = normalizedSpec;
        display.spawnPacket = createSpawnPacket(entityId, display.entityUuid, normalizedSpec);
        display.metadataPacket = createMetadataPacket(entityId, normalizedSpec);
        display.spawnPackets = List.of(display.spawnPacket, display.metadataPacket);
        Object positionPacket = createPositionPacket(entityId, normalizedSpec.location());
        sendUpdateForAllViewers(display, positionPacket, display.metadataPacket);
        scheduleSyncDisplay(display);
        return true;
    }

    @Override
    public void destroyDisplay(int entityId) {
        scheduledDisplaySyncs.remove(entityId);
        ProxyItemDisplay removed = displays.remove(entityId);
        if (removed != null) {
            markDisplaySnapshotDirty();
            destroyForAllViewers(removed);
        }
    }

    @Override
    public void cleanupWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }

        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, ProxyItemDisplay> entry : displays.entrySet()) {
            Location location = entry.getValue().spec.location();
            if (location.getWorld() != null && location.getWorld().getUID().equals(worldId)) {
                toRemove.add(entry.getKey());
            }
        }

        for (Integer entityId : toRemove) {
            destroyDisplay(entityId);
        }
    }

    @Override
    public int cleanup() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }

        int removed = displays.size();
        for (Integer entityId : new ArrayList<>(displays.keySet())) {
            destroyDisplay(entityId);
        }
        scheduledDisplaySyncs.clear();
        onlinePlayers.clear();
        displaySnapshot = List.of();
        markDisplaySnapshotDirty();
        return removed;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 5L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        clearViewer(event.getPlayer().getUniqueId());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        clearViewer(event.getPlayer().getUniqueId());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        onlinePlayers.remove(event.getPlayer().getUniqueId());
        clearViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        List<Integer> toRemove = new ArrayList<>();
        for (ProxyItemDisplay display : displays.values()) {
            Location location = display.spec.location();
            if (location.getWorld() == null || !location.getWorld().getUID().equals(worldId)) {
                continue;
            }
            if ((location.getBlockX() >> 4) == chunkX && (location.getBlockZ() >> 4) == chunkZ) {
                toRemove.add(display.entityId);
            }
        }
        for (Integer entityId : toRemove) {
            destroyDisplay(entityId);
        }
    }

    private void startSyncTask() {
        syncTask = plugin.scheduler().runRepeating(this::syncAll, syncIntervalTicks, syncIntervalTicks);
    }

    private void syncAll() {
        if (!isAvailable()) {
            return;
        }
        if (displays.isEmpty()) {
            return;
        }

        List<ProxyItemDisplay> snapshot = getDisplaySnapshot();
        int size = snapshot.size();
        if (size == 0) {
            syncCursor = 0;
            return;
        }

        int budget = Math.min(syncBatchSize, size);
        int start = syncCursor >= size ? 0 : syncCursor;
        int processed = 0;
        for (int i = 0; i < budget; i++) {
            ProxyItemDisplay display = snapshot.get((start + i) % size);
            scheduleSyncDisplay(display);
            processed++;
        }
        syncCursor = (start + Math.max(1, processed)) % size;
    }

    private void markDisplaySnapshotDirty() {
        displaySnapshotVersion.incrementAndGet();
    }

    private List<ProxyItemDisplay> getDisplaySnapshot() {
        long version = displaySnapshotVersion.get();
        List<ProxyItemDisplay> snapshot = displaySnapshot;
        if (displaySnapshotCachedVersion == version) {
            return snapshot;
        }

        List<ProxyItemDisplay> refreshed = new ArrayList<>(displays.values());
        List<ProxyItemDisplay> updated = refreshed.isEmpty() ? List.of() : Collections.unmodifiableList(refreshed);
        displaySnapshot = updated;
        displaySnapshotCachedVersion = version;
        return updated;
    }

    private void scheduleSyncDisplay(ProxyItemDisplay display) {
        if (display == null) {
            return;
        }

        if (!plugin.scheduler().isFolia()) {
            syncDisplay(display);
            return;
        }

        if (!scheduledDisplaySyncs.add(display.entityId)) {
            return;
        }
        try {
            scheduleDisplayForPlayers(display);
        } finally {
            scheduledDisplaySyncs.remove(display.entityId);
        }
    }

    private void scheduleDisplayForPlayers(ProxyItemDisplay display) {
        if (displays.get(display.entityId) != display) {
            return;
        }
        for (Player player : onlinePlayers.values()) {
            if (player == null) {
                continue;
            }
            try {
                plugin.scheduler().runForEntity(player, () -> syncDisplayForPlayer(display, player));
            } catch (RuntimeException e) {
                onlinePlayers.remove(player.getUniqueId());
                display.viewers.remove(player.getUniqueId());
            }
        }
    }

    private void syncPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        onlinePlayers.put(player.getUniqueId(), player);
        for (ProxyItemDisplay display : displays.values()) {
            syncDisplayForPlayer(display, player);
        }
    }

    private void syncDisplayForPlayer(ProxyItemDisplay display, Player player) {
        if (display == null || displays.get(display.entityId) != display) {
            return;
        }
        UUID playerId = player == null ? null : player.getUniqueId();
        if (playerId == null || !onlinePlayers.containsKey(playerId)) {
            if (playerId != null) {
                display.viewers.remove(playerId);
            }
            return;
        }

        if (shouldViewerSeeDisplay(player, display)) {
            if (!display.viewers.contains(playerId)) {
                spawnForViewer(player, display);
            }
        } else if (display.viewers.contains(playerId)) {
            destroyForViewer(player, display);
        }
    }

    private void syncDisplay(ProxyItemDisplay display) {
        if (displays.get(display.entityId) != display) {
            return;
        }
        World world = display.spec.location().getWorld();
        List<Player> players = world == null ? List.of() : world.getPlayers();
        syncDisplay(display, players);
    }

    private void syncDisplay(ProxyItemDisplay display, List<Player> players) {
        World world = display.spec.location().getWorld();
        if (world == null || !world.isChunkLoaded(display.spec.location().getBlockX() >> 4, display.spec.location().getBlockZ() >> 4)) {
            destroyForAllViewers(display);
            return;
        }

        Set<UUID> desiredViewers = new HashSet<>();
        for (Player player : players) {
            if (!shouldViewerSeeDisplay(player, display)) {
                continue;
            }

            desiredViewers.add(player.getUniqueId());
            if (!display.viewers.contains(player.getUniqueId())) {
                spawnForViewer(player, display);
            }
        }

        for (UUID viewerId : new HashSet<>(display.viewers)) {
            if (!desiredViewers.contains(viewerId)) {
                Player player = Bukkit.getPlayer(viewerId);
                if (player != null) {
                    destroyForViewer(player, display);
                } else {
                    display.viewers.remove(viewerId);
                }
            }
        }
    }

    private boolean shouldViewerSeeDisplay(Player player, ProxyItemDisplay display) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        Location location = display.spec.location();
        if (location.getWorld() == null || !Objects.equals(player.getWorld(), location.getWorld())) {
            return false;
        }

        if (!plugin.scheduler().isFolia()
                && !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }

        return player.getLocation().distanceSquared(location) <= viewDistanceSquared;
    }

    private void spawnForViewer(Player player, ProxyItemDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                return;
            }
            user.sendPackets(display.spawnPackets, false);
            display.viewers.add(player.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_spawn_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        }
    }

    private void destroyForViewer(Player player, ProxyItemDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user != null && user.isOnline()) {
                user.sendPacket(display.destroyPacket, false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_destroy_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        } finally {
            display.viewers.remove(player.getUniqueId());
        }
    }

    private void destroyForAllViewers(ProxyItemDisplay display) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player player = onlinePlayers.get(viewerId);
            if (player != null) {
                if (plugin.scheduler().isFolia()) {
                    try {
                        plugin.scheduler().runForEntity(player, () -> {
                            if (display.viewers.contains(viewerId)) {
                                destroyForViewer(player, display);
                            }
                        });
                    } catch (RuntimeException e) {
                        display.viewers.remove(viewerId);
                        onlinePlayers.remove(viewerId);
                    }
                } else {
                    destroyForViewer(player, display);
                }
            } else {
                display.viewers.remove(viewerId);
            }
        }
    }

    private void sendUpdateForAllViewers(ProxyItemDisplay display, Object positionPacket, Object metadataPacket) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player player = onlinePlayers.get(viewerId);
            if (player != null) {
                if (plugin.scheduler().isFolia()) {
                    try {
                        plugin.scheduler().runForEntity(player, () -> {
                            if (display.viewers.contains(viewerId)) {
                                sendUpdateForViewer(player, display, positionPacket, metadataPacket);
                            }
                        });
                    } catch (RuntimeException e) {
                        display.viewers.remove(viewerId);
                        onlinePlayers.remove(viewerId);
                    }
                } else {
                    sendUpdateForViewer(player, display, positionPacket, metadataPacket);
                }
            } else {
                display.viewers.remove(viewerId);
            }
        }
    }

    private void sendUpdateForViewer(Player player, ProxyItemDisplay display, Object positionPacket, Object metadataPacket) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                display.viewers.remove(player.getUniqueId());
                return;
            }
            user.sendPackets(List.of(positionPacket, metadataPacket), false);
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_update_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        }
    }

    private Object createSpawnPacket(int entityId, UUID entityUuid, DisplaySpec spec) {
        Location location = spec.location();
        return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                entityId,
                entityUuid,
                location.getX(),
                location.getY(),
                location.getZ(),
                0.0F,
                0.0F,
                EntityTypeProxy.ITEM_DISPLAY,
                0,
                Vec3Proxy.ZERO,
                0.0D
        );
    }

    private Object createPositionPacket(int entityId, Location location) {
        if (VersionHelper.isOrAbove1_21_2) {
            Object position = Vec3Proxy.INSTANCE.newInstance(location.getX(), location.getY(), location.getZ());
            Object values = PositionMoveRotationProxy.INSTANCE.newInstance(position, Vec3Proxy.ZERO, 0.0F, 0.0F);
            return ClientboundEntityPositionSyncPacketProxy.INSTANCE.newInstance(entityId, values, false);
        }

        Object packet = ClientboundTeleportEntityPacketProxy.UNSAFE_CONSTRUCTOR.newInstance();
        ClientboundTeleportEntityPacketProxy.INSTANCE.setId(packet, entityId);
        ClientboundTeleportEntityPacketProxy.INSTANCE.setX(packet, location.getX());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setY(packet, location.getY());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setZ(packet, location.getZ());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setYRot(packet, MiscUtils.packDegrees(0.0F));
        ClientboundTeleportEntityPacketProxy.INSTANCE.setXRot(packet, MiscUtils.packDegrees(0.0F));
        ClientboundTeleportEntityPacketProxy.INSTANCE.setOnGround(packet, false);
        return packet;
    }

    private Object createMetadataPacket(int entityId, DisplaySpec spec) {
        List<Object> values = new ArrayList<>();
        BaseEntityData.NoGravity.addEntityData(true, values);
        BaseEntityData.Silent.addEntityData(true, values);

        var wrappedItem = BukkitItemManager.instance().wrap(spec.itemStack());
        if (wrappedItem != null && !wrappedItem.isEmpty()) {
            DisplayData.ItemDisplayData.ItemStack.addEntityData(wrappedItem.minecraftItem(), values);
        }

        var transformation = spec.transformation();
        DisplayData.ItemDisplayData.Translation.addEntityData(transformation.getTranslation(), values);
        DisplayData.ItemDisplayData.Scale.addEntityData(transformation.getScale(), values);
        DisplayData.ItemDisplayData.LeftRotation.addEntityData(transformation.getLeftRotation(), values);
        DisplayData.ItemDisplayData.RightRotation.addEntityData(transformation.getRightRotation(), values);
        DisplayData.ItemDisplayData.ItemTransform.addEntityData(toCeDisplayContext(spec.itemTransform()), values);
        DisplayData.ItemDisplayData.ShadowRadius.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.ShadowStrength.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.Width.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.Height.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.ViewRange.addEntityData(1.0F, values);
        return createEntityDataPacket(entityId, values);
    }

    private Object createEntityDataPacket(int entityId, List<?> values) {
        return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(entityId, values);
    }

    private Object createDestroyPacket(int entityId) {
        return ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(IntList.of(entityId));
    }

    private void clearViewer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        for (ProxyItemDisplay display : displays.values()) {
            display.viewers.remove(playerId);
        }
    }

    private byte toCeDisplayContext(ItemDisplay.ItemDisplayTransform transform) {
        if (transform == null) {
            return 0;
        }
        return switch (transform) {
            case THIRDPERSON_LEFTHAND -> 1;
            case THIRDPERSON_RIGHTHAND -> 2;
            case FIRSTPERSON_LEFTHAND -> 3;
            case FIRSTPERSON_RIGHTHAND -> 4;
            case HEAD -> 5;
            case GUI -> 6;
            case GROUND -> 7;
            case FIXED -> 8;
            default -> 0;
        };
    }

    private DisplaySpec normalize(DisplaySpec spec) {
        Location location = spec.location().clone();
        ItemStack itemStack = spec.itemStack().clone();
        itemStack.setAmount(1);
        return new DisplaySpec(location, itemStack, spec.itemTransform(), spec.transformation());
    }

    private static final class ProxyItemDisplay {
        private final int entityId;
        private final UUID entityUuid;
        private DisplaySpec spec;
        private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
        private Object spawnPacket;
        private Object metadataPacket;
        private final Object destroyPacket;
        private List<Object> spawnPackets;

        private ProxyItemDisplay(int entityId, UUID entityUuid, DisplaySpec spec,
                                 Object spawnPacket, Object metadataPacket, Object destroyPacket) {
            this.entityId = entityId;
            this.entityUuid = entityUuid;
            this.spec = spec;
            this.spawnPacket = spawnPacket;
            this.metadataPacket = metadataPacket;
            this.destroyPacket = destroyPacket;
            this.spawnPackets = List.of(spawnPacket, metadataPacket);
        }
    }
}


