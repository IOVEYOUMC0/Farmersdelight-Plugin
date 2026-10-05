package com.huidu.farmersdelight.visual;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The viewer side of display culling: one bounded spatial record of the displays, and what each player has
 * already been told about them.
 *
 *
 * The record is written by the region that owns a display and read by a player's pass, which holds no entity
 * at all: deciding about a display therefore costs no cross-region entity read, and a pass is safe on the
 * region that owns the player. Every method takes one lock, so a pass sees the record and the per-viewer
 * state as one consistent picture even when a display is registered or removed on another region thread
 * while the pass runs.
 *
 *
 * A pass covers two sets. It refreshes the displays this player is currently showing from a rotating cursor,
 * so a budget smaller than that set still reaches every entry over a few passes instead of starving the
 * tail, and it looks for displays it has not seen yet inside a chunk window around the player. Both together
 * are capped by the configured budget, so the work follows the number of players and that budget, not the
 * number of displays on the server.
 */
@ApiStatus.Internal
public final class DisplayViewMirror {

    /** Told to one viewer: the range to render one display with, 0 while it must stay hidden. */
    public interface Sink {
        void send(int entityId, float range);
    }

    /** What one pass cost and did, so a caller can watch the budget it ran under. */
    public record PassResult(int checked, int sent, int active) {
    }

    /** One display as the viewer side sees it: where it is, what it is, and how far it renders. */
    private static final class Entry {

        private final int entityId;
        private final int chunkX;
        private final int chunkZ;
        private final String typeKey;
        private final float baseRange;
        private final double x;
        private final double y;
        private final double z;

        private Entry(int entityId, int chunkX, int chunkZ, String typeKey, float baseRange,
                      double x, double y, double z) {
            this.entityId = entityId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.typeKey = typeKey;
            this.baseRange = baseRange;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private long chunkKey() {
            return key(this.chunkX, this.chunkZ);
        }
    }

    /** One player's mirror: the displays it is showing, what it was told about each, and the refresh cursor. */
    private static final class View {

        private final Set<Integer> active = new LinkedHashSet<>();
        private final Map<Integer, DisplayCulling.ViewRangeState> states = new HashMap<>();
        private UUID worldId;
        private double x;
        private double y;
        private double z;
        private int cursor;

        private void reset(UUID worldId) {
            this.active.clear();
            this.states.clear();
            this.cursor = 0;
            this.worldId = worldId;
        }
    }

    /** One pass's running totals, so the send count does not need a second trip through the entry. */
    private static final class Counter {

        private int checked;
        private int sent;
    }

    /** One range a pass decided to send, collected under the lock and delivered after it is released. */
    private record Send(int entityId, float range) {
    }

    private final Map<UUID, Map<Long, Map<Integer, Entry>>> byWorldChunk = new HashMap<>();
    private final Map<UUID, Map<Integer, Entry>> byEntity = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();

    /**
     * Records one display, replacing what an earlier registration of the same id left behind. Per-viewer
     * state is kept, because a region re-registers a display it already owns to refresh its position and the
     * viewer side must not read that as a new display.
     */
    public synchronized void register(UUID worldId, int entityId, String typeKey,
                                      double x, double y, double z, float baseRange) {
        if (worldId == null) {
            return;
        }
        int chunkX = Math.floorDiv((int) Math.floor(x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
        Entry entry = new Entry(entityId, chunkX, chunkZ, typeKey, baseRange, x, y, z);
        Entry previous = byEntity.computeIfAbsent(worldId, key -> new HashMap<>()).put(entityId, entry);
        if (previous != null) {
            dropFromChunk(worldId, previous);
        }
        byWorldChunk.computeIfAbsent(worldId, key -> new HashMap<>())
                .computeIfAbsent(entry.chunkKey(), key -> new HashMap<>())
                .put(entityId, entry);
    }

    /** Drops one display and everything every viewer was told about it. */
    public synchronized boolean unregister(UUID worldId, int entityId) {
        if (worldId == null) {
            return false;
        }
        Map<Integer, Entry> entries = byEntity.get(worldId);
        if (entries == null) {
            return false;
        }
        Entry removed = entries.remove(entityId);
        if (removed == null) {
            return false;
        }
        if (entries.isEmpty()) {
            byEntity.remove(worldId);
        }
        dropFromChunk(worldId, removed);
        for (View view : views.values()) {
            view.active.remove(entityId);
            view.states.remove(entityId);
        }
        return true;
    }

    /** Drops every display of one world, and the mirror of the players who were in it. */
    public synchronized void unregisterWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }
        byWorldChunk.remove(worldId);
        byEntity.remove(worldId);
        for (View view : views.values()) {
            if (worldId.equals(view.worldId)) {
                view.reset(worldId);
            }
        }
    }

    /** Drops one player's mirror, as a quit does. */
    public synchronized boolean forget(UUID playerId) {
        return playerId != null && views.remove(playerId) != null;
    }

    /** Keeps only the mirrors of the listed players; answers how many were dropped. */
    public synchronized int retainOnline(Set<UUID> online) {
        if (views.isEmpty()) {
            return 0;
        }
        int before = views.size();
        views.keySet().retainAll(online);
        return before - views.size();
    }

    /**
     * Forgets what every viewer was told while keeping the set each one is showing. A new distance has to
     * count as a change, or the pass would keep the old range forever because nothing looks different.
     */
    public synchronized void clearStates() {
        for (View view : views.values()) {
            view.states.clear();
        }
    }

    public synchronized void clear() {
        byWorldChunk.clear();
        byEntity.clear();
        views.clear();
    }

    /**
     * One player's culling pass. position is the player's own, read by the caller on the region that owns the
     * player; everything else the pass needs is in this mirror. The decisions are taken under the mirror's
     * lock and the packets go out after it has been released, so a network write never holds up a display
     * that another region is registering.
     */
    public PassResult pass(UUID playerId, UUID worldId, double x, double y, double z,
                           DisplayViewSettings settings, Sink sink) {
        List<Send> sends = new ArrayList<>();
        PassResult result;
        synchronized (this) {
            result = evaluate(playerId, worldId, x, y, z, settings, sends);
        }
        for (Send send : sends) {
            sink.send(send.entityId(), send.range());
        }
        return result;
    }

    private PassResult evaluate(UUID playerId, UUID worldId, double x, double y, double z,
                                DisplayViewSettings settings, List<Send> sends) {
        View view = views.computeIfAbsent(playerId, key -> new View());
        if (!worldId.equals(view.worldId)) {
            // The mirror belongs to one world, so a player who changed worlds starts over instead of
            // carrying the previous world's answers into the new one.
            view.reset(worldId);
        }
        view.x = x;
        view.y = y;
        view.z = z;
        Counter counter = new Counter();
        int budget = settings.checksPerPlayer();
        // Part of the budget is kept for first sightings even while the active set is full, so a display
        // that walks into view is picked up on the same pass instead of waiting for the active set to shrink.
        int activeBudget = view.active.isEmpty()
                ? 0
                : Math.min(view.active.size(), Math.max(1, budget - Math.max(1, budget / 4)));

        if (activeBudget > 0) {
            List<Integer> known = new ArrayList<>(view.active);
            int size = known.size();
            int start = Math.floorMod(view.cursor, size);
            for (int i = 0; i < activeBudget; i++) {
                int entityId = known.get((start + i) % size);
                counter.checked++;
                Entry entry = entry(worldId, entityId);
                if (entry == null) {
                    view.active.remove(entityId);
                    view.states.remove(entityId);
                    continue;
                }
                boolean culling = settings.cullingEnabled(entry.typeKey);
                boolean visible = update(view, entry, x, y, z, settings, sends, counter);
                if (!culling) {
                    view.active.remove(entityId);
                    view.states.remove(entityId);
                } else if (!visible) {
                    view.active.remove(entityId);
                }
            }
            view.cursor = start + activeBudget;
        }

        int remaining = budget - counter.checked;
        if (remaining > 0) {
            List<Entry> fresh = near(worldId, x, z, settings.windowRadius(), remaining, view.active);
            for (Entry entry : fresh) {
                boolean culling = settings.cullingEnabled(entry.typeKey);
                if (!culling && !view.states.containsKey(entry.entityId)) {
                    // Culling is off for this type and this viewer was never told anything about it, so
                    // there is nothing to take back and no reason to spend a check on it.
                    continue;
                }
                counter.checked++;
                boolean visible = update(view, entry, x, y, z, settings, sends, counter);
                if (!culling) {
                    view.states.remove(entry.entityId);
                } else if (visible) {
                    view.active.add(entry.entityId);
                }
            }
        }
        return new PassResult(counter.checked, counter.sent, view.active.size());
    }

    /**
     * Displays this player is currently showing. Package-private: the pass owns these sets, and only the
     * offline tests have a reason to read their size.
     */
    synchronized int activeCount(UUID playerId) {
        View view = views.get(playerId);
        return view == null ? 0 : view.active.size();
    }

    /** Displays this player has a remembered answer for; see activeCount for the visibility. */
    synchronized int stateCount(UUID playerId) {
        View view = views.get(playerId);
        return view == null ? 0 : view.states.size();
    }

    /**
     * The range this viewer must be told, or NaN when it already has the right one. Returns whether the
     * display stays visible for it. Only called with the mirror lock held.
     */
    private boolean update(View view, Entry entry, double x, double y, double z,
                           DisplayViewSettings settings, List<Send> sends, Counter counter) {
        DisplayCulling.ViewRangeState state = view.states.computeIfAbsent(entry.entityId,
                key -> new DisplayCulling.ViewRangeState());
        if (!settings.cullingEnabled(entry.typeKey)) {
            float range = state.update(true, entry.baseRange);
            if (!Float.isNaN(range)) {
                sends.add(new Send(entry.entityId, range));
                counter.sent++;
            }
            return false;
        }
        double dx = x - entry.x;
        double dy = y - entry.y;
        double dz = z - entry.z;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        boolean alreadyShown = state.hasSent() && state.lastSent() > DisplayCulling.CULLED_RANGE;
        boolean visible = DisplayCulling.isVisible(distanceSquared, settings.viewDistanceFor(entry.typeKey),
                alreadyShown ? settings.hysteresis() : 0.0D);
        float range = state.update(visible, entry.baseRange);
        if (!Float.isNaN(range)) {
            sends.add(new Send(entry.entityId, range));
            counter.sent++;
        }
        return visible;
    }

    /**
     * The displays inside a chunk window around one position that this viewer is not showing yet. Only the
     * buckets the window covers are visited, so this never walks the displays of the whole world, and the
     * limit ends the walk early on a dense field.
     */
    private List<Entry> near(UUID worldId, double x, double z, double radius, int limit, Set<Integer> skip) {
        Map<Long, Map<Integer, Entry>> chunks = byWorldChunk.get(worldId);
        if (chunks == null || chunks.isEmpty() || limit <= 0) {
            return List.of();
        }
        int blockRadius = (int) Math.ceil(Math.max(0.0D, radius));
        int half = (blockRadius >> 4) + 1;
        int centerX = Math.floorDiv((int) Math.floor(x), 16);
        int centerZ = Math.floorDiv((int) Math.floor(z), 16);
        double limitSquared = (double) blockRadius * (double) blockRadius;
        List<Entry> found = new ArrayList<>(Math.min(limit, 16));
        for (int chunkX = centerX - half; chunkX <= centerX + half; chunkX++) {
            for (int chunkZ = centerZ - half; chunkZ <= centerZ + half; chunkZ++) {
                Map<Integer, Entry> bucket = chunks.get(key(chunkX, chunkZ));
                if (bucket == null) {
                    continue;
                }
                for (Entry entry : bucket.values()) {
                    if (skip.contains(entry.entityId)) {
                        continue;
                    }
                    double dx = entry.x - x;
                    double dz = entry.z - z;
                    if (dx * dx + dz * dz > limitSquared) {
                        continue;
                    }
                    found.add(entry);
                    if (found.size() >= limit) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    private Entry entry(UUID worldId, int entityId) {
        Map<Integer, Entry> entries = byEntity.get(worldId);
        return entries == null ? null : entries.get(entityId);
    }

    private void dropFromChunk(UUID worldId, Entry entry) {
        Map<Long, Map<Integer, Entry>> chunks = byWorldChunk.get(worldId);
        if (chunks == null) {
            return;
        }
        Map<Integer, Entry> bucket = chunks.get(entry.chunkKey());
        if (bucket == null) {
            return;
        }
        bucket.remove(entry.entityId);
        if (bucket.isEmpty()) {
            chunks.remove(entry.chunkKey());
            if (chunks.isEmpty()) {
                byWorldChunk.remove(worldId);
            }
        }
    }

    /** Packs chunk coordinates into one long, the same layout the rest of the plugin uses for chunk keys. */
    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
