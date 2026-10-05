package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import org.bukkit.Location;
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
 * pass is throttled to the existing 20-tick cadence, works per entity on its owning region, and prunes dead
 * entities instead of needing removal hooks at the call sites.
 */
@ApiStatus.Internal
public final class RealDisplayCuller {

    private static final int DEFAULT_INTERVAL_TICKS = 20;
    private static final double DEFAULT_VIEW_DISTANCE = 64.0D;
    private static final double DEFAULT_HYSTERESIS = 8.0D;
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();

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

    /** Configures the threshold; called from the plugin's reload, keeps the defaults when unset. */
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

    public int trackedCount() {
        return this.displays.size();
    }

    /** Stops managing a display (chunk unload, world unload, reload). Only the bookkeeping is dropped. */
    public void untrack(Entity display) {
        if (display == null) {
            return;
        }
        this.displays.remove(display);
        this.viewerStates.remove(display.getEntityId());
    }

    public void shutdown() {
        stopTask();
        this.displays.clear();
        this.viewerStates.clear();
        HOLDERS.remove(this.plugin.getName(), this);
    }

    private void ensureTask() {
        if (this.task != null) {
            return;
        }
        synchronized (this) {
            if (this.task == null) {
                ACTIVE.add(this.plugin.getName());
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
            ACTIVE.remove(this.plugin.getName());
        }
    }

    /** One throttled pass: prune dead entities, then hand each live one to its owning region. */
    private void tick() {
        if (this.displays.isEmpty()) {
            stopTask();
            return;
        }
        for (Entity display : this.displays) {
            if (!display.isValid() || display.isDead()) {
                untrack(display);
                continue;
            }
            Location location = display.getLocation();
            World world = location.getWorld();
            if (world == null) {
                untrack(display);
                continue;
            }
            if (this.plugin.scheduler().isFolia()) {
                // Only the owning region may read the entity's location and send its packets.
                this.plugin.scheduler().runAt(world, location.getBlockX() >> 4, location.getBlockZ() >> 4,
                        () -> cull(display));
            } else {
                cull(display);
            }
        }
    }

    private void cull(Entity display) {
        Location location = display.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int entityId = display.getEntityId();
        float baseRange = baseRange(display);
        Map<UUID, DisplayCulling.ViewRangeState> states =
                this.viewerStates.computeIfAbsent(entityId, ignored -> new ConcurrentHashMap<>());
        for (Player player : world.getPlayers()) {
            UUID playerId = player.getUniqueId();
            DisplayCulling.ViewRangeState state = states.computeIfAbsent(playerId,
                    ignored -> new DisplayCulling.ViewRangeState());
            boolean alreadyShown = state.hasSent() && state.lastSent() > DisplayCulling.CULLED_RANGE;
            double dx = player.getX() - location.getX();
            double dy = player.getY() - location.getY();
            double dz = player.getZ() - location.getZ();
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
