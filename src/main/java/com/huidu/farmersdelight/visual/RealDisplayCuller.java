package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-viewer culling for the few REAL display entities FD still spawns (the rope carrier BlockDisplays in
 * CarrierRestorer). They are tracked by vanilla entity tracking, which follows the server view distance
 * (~160 blocks) rather than the player's own, so without this they stay client-side much longer than
 * CraftEngine's furniture does.
 *
 *
 * Same rule as CraftEngine: an invisible display keeps its entity and its id, the viewer is sent a ViewRange
 * of 0 (ItemDisplayBlockEntityElement.setCulled), and the base range comes back when the player returns. The
 * pass is throttled to the existing 20-tick cadence, hands each entity to its own region (runForEntity)
 * before touching it, and prunes dead entities instead of needing removal hooks at the call sites.
 */
@ApiStatus.Internal
public final class RealDisplayCuller {

    private static final int DEFAULT_INTERVAL_TICKS = 20;
    private static final double DEFAULT_VIEW_DISTANCE = 64.0D;
    private static final double DEFAULT_HYSTERESIS = 8.0D;

    /** One culler per plugin. Kept out of the plugin class so the shared facility owns its own lifecycle. */
    public static RealDisplayCuller of(FarmersDelightPlugin plugin) {
        return HOLDERS.computeIfAbsent(plugin.getName(), key -> new RealDisplayCuller(plugin));
    }

    private static final Map<String, RealDisplayCuller> HOLDERS = new ConcurrentHashMap<>();

    private final FarmersDelightPlugin plugin;
    private final Set<Entity> displays = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Map<UUID, DisplayCulling.ViewRangeState>> viewerStates = new ConcurrentHashMap<>();
    private volatile double viewDistance = DEFAULT_VIEW_DISTANCE;
    private volatile double hysteresis = DEFAULT_HYSTERESIS;
    private volatile PluginTask task;

    private RealDisplayCuller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Configures the threshold from the same existing key the proxy path reads
     * (performance.proxy-display.view-distance), so both paths cull at one distance.
     */
    public void reload(double configuredViewDistance) {
        this.viewDistance = configuredViewDistance > 0.0D ? configuredViewDistance : DEFAULT_VIEW_DISTANCE;
    }

    /** Starts managing this real display. The culler never removes it; vanilla ownership stays untouched. */
    public void track(Entity display) {
        if (display == null) {
            return;
        }
        this.displays.add(display);
        ensureTask();
    }

    /** Stops managing a display (dead entity, unloaded world). Only the bookkeeping is dropped. */
    private void untrack(Entity display) {
        if (display == null) {
            return;
        }
        this.displays.remove(display);
        this.viewerStates.remove(display.getEntityId());
    }

    /** Plugin disable: cancel the task, drop every tracked display and the cached holder. */
    public void shutdown() {
        stopTask();
        this.displays.clear();
        this.viewerStates.clear();
        HOLDERS.remove(this.plugin.getName(), this);
    }

    private void ensureTask() {
        if (!DisplayCulling.needsFreshTask(this.task)) {
            return;
        }
        synchronized (this) {
            // Second check under the lock, with the same rule: a task cancelled by a disable must be rebuilt
            // instead of silently keeping culling switched off.
            if (DisplayCulling.needsFreshTask(this.task)) {
                this.task = this.plugin.scheduler().runRepeating(this::tick,
                        DEFAULT_INTERVAL_TICKS, DEFAULT_INTERVAL_TICKS);
            }
        }
    }

    private void stopTask() {
        synchronized (this) {
            if (this.task != null) {
                this.task.cancel();
                this.task = null;
            }
        }
    }

    /**
     * One throttled pass. On Folia the entity is handed to its own region first (runForEntity): neither the
     * entity's validity nor its position is read on this thread, so every read and every packet happens on the
     * region that owns it. Only Paper — where the whole server is one thread — reads inline.
     */
    private void tick() {
        if (this.displays.isEmpty()) {
            stopTask();
            return;
        }
        boolean folia = this.plugin.scheduler().isFolia();
        for (Entity display : this.displays) {
            if (folia) {
                try {
                    this.plugin.scheduler().runForEntity(display, () -> cull(display));
                } catch (RuntimeException e) {
                    // A retired entity cannot be scheduled anywhere any more; drop the bookkeeping.
                    untrack(display);
                }
            } else {
                cull(display);
            }
        }
    }

    /** Runs on the region that owns the entity: the first thing touched here is the entity itself. */
    private void cull(Entity display) {
        if (!display.isValid() || display.isDead()) {
            untrack(display);
            return;
        }
        // Field reads instead of a Location snapshot: getLocation() allocates one Location per tracked display
        // per pass, while getX/getY/getZ read the position this task already owns.
        World world = display.getWorld();
        if (world == null) {
            untrack(display);
            return;
        }
        int entityId = display.getEntityId();
        float baseRange = baseRange(display);
        double displayX = display.getX();
        double displayY = display.getY();
        double displayZ = display.getZ();
        Map<UUID, DisplayCulling.ViewRangeState> states =
                this.viewerStates.computeIfAbsent(entityId, ignored -> new ConcurrentHashMap<>());
        for (Player player : world.getPlayers()) {
            UUID playerId = player.getUniqueId();
            DisplayCulling.ViewRangeState state = states.computeIfAbsent(playerId,
                    ignored -> new DisplayCulling.ViewRangeState());
            boolean alreadyShown = state.hasSent() && state.lastSent() > DisplayCulling.CULLED_RANGE;
            double dx = player.getX() - displayX;
            double dy = player.getY() - displayY;
            double dz = player.getZ() - displayZ;
            boolean visible = DisplayCulling.isVisible(dx * dx + dy * dy + dz * dz, this.viewDistance,
                    alreadyShown ? this.hysteresis : 0.0D);
            float range = state.update(visible, baseRange);
            if (!Float.isNaN(range)) {
                send(player, entityId, range);
            }
        }
    }

    /** CE takes the per-player display distance scale when it is available; 1.0 is its default. */
    private float baseRange(Entity display) {
        if (display instanceof org.bukkit.entity.Display bukkitDisplay) {
            float range = bukkitDisplay.getViewRange();
            return range > 0.0F ? range : 1.0F;
        }
        return 1.0F;
    }

    private void send(Player player, int entityId, float range) {
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        if (craftEngine == null) {
            return;
        }
        BukkitNetworkManager networkManager = craftEngine.networkManager();
        NetWorkUser user = networkManager == null ? null : networkManager.getOnlineUser(player.getUniqueId());
        if (user == null || !user.isOnline()) {
            return;
        }
        List<Object> values = new ArrayList<>(1);
        DisplayData.ViewRange.addEntityData(range, values, true);
        user.sendPacket(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(entityId, values), false);
    }
}
