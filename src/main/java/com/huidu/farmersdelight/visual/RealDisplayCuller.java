package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.PeriodicStagger;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-viewer culling for the few REAL display entities FD still spawns. They are tracked by vanilla entity
 * tracking, which follows the server view distance (~160 blocks) rather than the player's own, so without
 * this they stay client-side much longer than CraftEngine's furniture does.
 *
 *
 * Same rule as CraftEngine: an invisible display keeps its entity and its id, the viewer is sent a ViewRange
 * of 0, and the base range comes back when the player returns. The work is split by ownership: the region
 * that owns a display registers it in a bounded spatial record and re-checks that it is still there, and a
 * player's pass reads that record on the player's own region. No pass reads an entity it does not own, and
 * no pass walks the displays of the whole world: it refreshes the displays its player is showing and looks
 * for new ones only inside a window around the player, both under one per-player budget.
 */
@ApiStatus.Internal
public final class RealDisplayCuller {

    private static final int DEFAULT_INTERVAL_TICKS = 20;
    private static final double DEFAULT_VIEW_DISTANCE = 64.0D;
    private static final double DEFAULT_HYSTERESIS = 8.0D;
    private static final int DEFAULT_CHECKS_PER_PLAYER = 64;

    /** One culler per plugin. Kept out of the plugin class so the shared facility owns its own lifecycle. */
    public static RealDisplayCuller of(FarmersDelightPlugin plugin) {
        return HOLDERS.computeIfAbsent(plugin.getName(), key -> new RealDisplayCuller(plugin));
    }

    private static final Map<String, RealDisplayCuller> HOLDERS = new ConcurrentHashMap<>();

    private final FarmersDelightPlugin plugin;
    private final Set<Entity> displays = ConcurrentHashMap.newKeySet();
    /** The world each tracked display was registered in, so its bookkeeping can be dropped without reading it. */
    private final Map<Entity, UUID> displayWorlds = new ConcurrentHashMap<>();
    private final DisplayViewMirror mirror = new DisplayViewMirror();
    private volatile DisplayViewSettings settings = DisplayViewSettings.defaults();
    private volatile PluginTask task;

    private RealDisplayCuller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads the culling thresholds: the shared performance.proxy-display.view-distance as the fallback,
     * performance.budgets.display-cull-checks-per-player as the budget one player's pass runs under, and
     * performance.proxy-display.display-culling for the per-type overrides.
     */
    public void reload(double configuredViewDistance) {
        double fallback = configuredViewDistance > 0.0D ? configuredViewDistance : DEFAULT_VIEW_DISTANCE;
        int checksPerPlayer = Math.max(1, this.plugin.getConfigInt(DEFAULT_CHECKS_PER_PLAYER,
                "performance.budgets.display-cull-checks-per-player"));
        this.settings = new DisplayViewSettings(fallback, DEFAULT_HYSTERESIS, checksPerPlayer,
                readTypes(fallback));
        // A viewer is only told a range when it changed, so a new distance has to invalidate what was told.
        this.mirror.clearStates();
    }

    private Map<String, DisplayViewSettings.Type> readTypes(double fallback) {
        ConfigurationSection section =
                this.plugin.getFirstConfigSection("performance.proxy-display.display-culling");
        if (section == null) {
            return Map.of();
        }
        Map<String, DisplayViewSettings.Type> types = new HashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            boolean culling = entry.getBoolean("entity-culling", true);
            double distance = entry.getDouble("view-distance", fallback);
            types.put(key, new DisplayViewSettings.Type(culling, distance > 0.0D ? distance : fallback));
        }
        return types;
    }

    /** Starts managing this real display. The culler never removes it; vanilla ownership stays untouched. */
    public void track(Entity display) {
        if (display == null) {
            return;
        }
        this.displays.add(display);
        register(display);
        ensureTask();
    }

    /** Plugin disable: cancel the task, drop every tracked display and the cached holder. */
    public void shutdown() {
        stopTask();
        this.displays.clear();
        this.displayWorlds.clear();
        this.mirror.clear();
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
                        PeriodicStagger.initialDelay("display-cull", DEFAULT_INTERVAL_TICKS), DEFAULT_INTERVAL_TICKS);
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
     * One throttled pass. The displays are handed to their own regions first, then each online player gets a
     * pass on its own region; this thread reads neither an entity nor a block.
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
                    // The retired callback covers an entity that is gone before the task could run: without
                    // it the mirror would keep a display no region can ever reach again.
                    this.plugin.scheduler().runForEntity(display, () -> verify(display), () -> untrack(display));
                } catch (RuntimeException e) {
                    // A retired entity cannot be scheduled anywhere any more; drop the bookkeeping.
                    untrack(display);
                }
            } else {
                verify(display);
            }
        }
        DisplayViewSettings current = this.settings;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<UUID> onlineIds = new HashSet<>(Math.max(4, online.size() * 2));
        for (Player player : online) {
            onlineIds.add(player.getUniqueId());
        }
        // A player who is gone keeps nothing: no further pass would ever reach that mirror.
        this.mirror.retainOnline(onlineIds);
        for (Player player : online) {
            if (folia) {
                try {
                    this.plugin.scheduler().runForEntity(player, () -> pass(player, current),
                            () -> this.mirror.forget(player.getUniqueId()));
                } catch (RuntimeException e) {
                    this.mirror.forget(player.getUniqueId());
                }
            } else {
                pass(player, current);
            }
        }
    }

    /**
     * Records what a pass needs about one display: its position, its model type and its render range. Runs on
     * the region that owns the entity, so the pass itself never has to read it.
     */
    private void register(Entity display) {
        World world = display.getWorld();
        if (world == null) {
            return;
        }
        this.displayWorlds.put(display, world.getUID());
        this.mirror.register(world.getUID(), display.getEntityId(), typeKey(display),
                display.getX(), display.getY(), display.getZ(), baseRange(display));
    }

    /** Stops managing a display (dead entity, unloaded world). Only the bookkeeping is dropped. */
    private void untrack(Entity display) {
        if (display == null) {
            return;
        }
        this.displays.remove(display);
        UUID worldId = this.displayWorlds.remove(display);
        if (worldId != null) {
            this.mirror.unregister(worldId, display.getEntityId());
        }
    }

    /** Runs on the region that owns the entity: the first thing touched here is the entity itself. */
    private void verify(Entity display) {
        if (!display.isValid() || display.isDead()) {
            untrack(display);
            return;
        }
        // Refreshing an entry the mirror already holds keeps what every viewer was told, so this sends
        // nothing by itself; it only makes the record agree with an entity that moved.
        register(display);
    }

    /** Runs on the region that owns the player: the only entity read here is the player itself. */
    private void pass(Player player, DisplayViewSettings current) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        this.mirror.pass(player.getUniqueId(), world.getUID(), player.getX(), player.getY(), player.getZ(),
                current, (entityId, range) -> send(player, entityId, range));
    }

    /**
     * The display's model type: the block id whose model the display draws. It names the per-type entry an
     * operator can write, and it is read here, on the region that owns the display.
     */
    private static String typeKey(Entity display) {
        if (display instanceof BlockDisplay blockDisplay) {
            BlockData data = blockDisplay.getBlock();
            if (data != null) {
                return data.getMaterial().getKey().toString();
            }
        }
        return display.getType().getKey().toString();
    }

    /** CE takes the per-player display distance scale when it is available; 1.0 is its default. */
    private float baseRange(Entity display) {
        if (display instanceof Display bukkitDisplay) {
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
