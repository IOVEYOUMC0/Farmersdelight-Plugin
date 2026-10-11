package com.huidu.farmersdelight.visual;

import net.momirealms.craftengine.core.entity.culling.Cullable;
import net.momirealms.craftengine.core.entity.culling.CullingData;
import net.momirealms.craftengine.core.entity.player.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Packet state only: CE calls these methods from its culling thread, without Bukkit world access. */
final class ProxyDisplayCullable {
    private final int entityId;
    private final Object destroyPacket;
    private final Object hiddenRangePacket;
    private final Logger logger;
    private final AtomicLong spawnCount;
    private final AtomicLong destroyCount;
    private final AtomicLong updateCount;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private volatile CullingData data;
    private List<Object> spawnPackets;
    private Object positionPacket;
    private Object metadataPacket;
    private Object visibleRangePacket;
    private boolean closed;
    private long revision;

    ProxyDisplayCullable(int entityId, Object destroyPacket, Object hiddenRangePacket, Logger logger,
                         AtomicLong spawnCount, AtomicLong destroyCount, AtomicLong updateCount) {
        this.entityId = entityId;
        this.destroyPacket = destroyPacket;
        this.hiddenRangePacket = hiddenRangePacket;
        this.logger = logger;
        this.spawnCount = spawnCount;
        this.destroyCount = destroyCount;
        this.updateCount = updateCount;
    }

    Set<UUID> viewerIds() {
        return viewers.keySet();
    }

    synchronized void update(List<Object> spawnPackets, Object positionPacket, Object metadataPacket,
                             Object visibleRangePacket, CullingData data) {
        this.spawnPackets = spawnPackets;
        this.positionPacket = positionPacket;
        this.metadataPacket = metadataPacket;
        this.visibleRangePacket = visibleRangePacket;
        this.data = data;
        revision++;
    }

    synchronized boolean addViewer(Player player, boolean nativeCulling) {
        if (closed || !player.isOnline()) {
            return false;
        }
        Viewer previous = viewers.get(player.uuid());
        if (previous != null) {
            if (previous.player == player
                    && previous.nativeCulling == nativeCulling
                    && (!nativeCulling || player.getTrackedEntity(entityId) != null)) {
                if (!nativeCulling && !previous.spawned) show(previous);
                return false;
            }
            // A delayed CE respawn packet can clear its tracker after FD's world/respawn callback.
            removeViewer(player.uuid());
        }
        if (nativeCulling && player.getTrackedEntity(entityId) != null) {
            return false;
        }
        Viewer viewer = new Viewer(player, nativeCulling);
        viewers.put(player.uuid(), viewer);
        try {
            if (nativeCulling) {
                player.addTrackedEntity(entityId, viewer);
            } else {
                show(viewer);
            }
            return viewers.get(player.uuid()) == viewer;
        } catch (RuntimeException | LinkageError failure) {
            removeViewer(player.uuid());
            throw failure;
        }
    }

    synchronized void removeViewer(UUID playerId) {
        Viewer viewer = viewers.remove(playerId);
        if (viewer == null) {
            return;
        }
        // Removal from CE's registry alone does not remove a retained entity from the client.
        unregister(viewer);
        removeClientEntity(viewer);
    }

    private void unregister(Viewer viewer) {
        if (viewer.nativeCulling) {
            var holder = viewer.player.getTrackedEntity(entityId);
            if (holder != null && holder.cullable == viewer) {
                viewer.player.removeTrackedEntity(entityId);
            }
        }
    }

    synchronized void close() {
        closed = true;
        for (UUID id : Set.copyOf(viewers.keySet())) {
            removeViewer(id);
        }
    }

    private synchronized void show(Viewer viewer) {
        if (!isCurrent(viewer) || viewer.spawned) {
            return;
        }
        Player player = viewer.player;
        try {
            viewer.spawned = true;
            player.sendPackets(spawnPackets, false);
            player.sendPacket(visibleRangePacket, false);
            viewer.visible = true;
            viewer.revision = revision;
            spawnCount.incrementAndGet();
        } catch (RuntimeException | LinkageError failure) {
            fail(viewer, failure);
        }
    }

    private synchronized void hide(Viewer viewer) {
        if (isCurrent(viewer)) {
            removeClientEntity(viewer);
        }
    }

    // These callbacks are optional on older CE versions; no newer interface method is invoked here.
    private synchronized boolean cull(Viewer viewer) {
        if (!isCurrent(viewer) || !viewer.spawned) {
            return false;
        }
        try {
            viewer.player.sendPacket(hiddenRangePacket, false);
            viewer.visible = false;
            updateCount.incrementAndGet();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            fail(viewer, failure);
            return false;
        }
    }

    private synchronized void restore(Viewer viewer) {
        if (!isCurrent(viewer)) {
            return;
        }
        if (!viewer.spawned) {
            show(viewer);
            return;
        }
        Player player = viewer.player;
        try {
            // Hidden updates are deferred; restore the latest position and metadata before making it visible.
            if (viewer.revision == revision) {
                player.sendPacket(visibleRangePacket, false);
            } else {
                player.sendPackets(List.of(positionPacket, metadataPacket, visibleRangePacket), false);
            }
            viewer.revision = revision;
            viewer.visible = true;
            updateCount.incrementAndGet();
        } catch (RuntimeException | LinkageError failure) {
            fail(viewer, failure);
        }
    }

    synchronized void sendUpdate(UUID playerId, boolean moved) {
        Viewer viewer = viewers.get(playerId);
        if (closed || viewer == null || !viewer.spawned || !viewer.visible) {
            return;
        }
        try {
            if (!moved) {
                viewer.player.sendPacket(metadataPacket, false);
            } else {
                viewer.player.sendPackets(List.of(positionPacket, metadataPacket), false);
            }
            viewer.revision = revision;
            updateCount.incrementAndGet();
        } catch (RuntimeException | LinkageError failure) {
            fail(viewer, failure);
        }
    }

    CullingData cullingData() {
        return data;
    }

    private boolean isCurrent(Viewer viewer) {
        return !closed && viewers.get(viewer.player.uuid()) == viewer;
    }

    private void removeClientEntity(Viewer viewer) {
        if (!viewer.spawned) {
            return;
        }
        viewer.spawned = false;
        viewer.visible = false;
        try {
            if (viewer.player.isOnline()) {
                viewer.player.sendPacket(destroyPacket, false);
                destroyCount.incrementAndGet();
            }
        } catch (RuntimeException | LinkageError failure) {
            logger.log(Level.WARNING, "Failed to remove proxy display " + entityId, failure);
        }
    }

    private void fail(Viewer viewer, Throwable failure) {
        // Keep the candidate indexed until the next sync retries or removes it; only CE tracking is invalid.
        unregister(viewer);
        removeClientEntity(viewer);
        logger.log(Level.WARNING, "Failed to send proxy display " + entityId, failure);
    }

    private final class Viewer implements Cullable {
        private final Player player;
        private final boolean nativeCulling;
        private boolean spawned;
        private boolean visible;
        private long revision;

        @Override
        public void show(Player player) {
            if (player == this.player) ProxyDisplayCullable.this.show(this);
        }

        @Override
        public void hide(Player player) {
            if (player == this.player) ProxyDisplayCullable.this.hide(this);
        }

        // Optional callbacks are declared without invoking newer CE interface methods.
        public boolean cull(Player player) {
            return player == this.player && ProxyDisplayCullable.this.cull(this);
        }

        public void restore(Player player) {
            if (player == this.player) ProxyDisplayCullable.this.restore(this);
        }

        @Override
        public CullingData cullingData() {
            return data;
        }

        private Viewer(Player player, boolean nativeCulling) {
            this.player = player;
            this.nativeCulling = nativeCulling;
        }
    }
}
