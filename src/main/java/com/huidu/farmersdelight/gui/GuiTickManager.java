package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

public class GuiTickManager {

    private static final long TICK_INTERVAL = 4L;
    private static GuiTickManager instance;
    private final FarmersDelightPlugin plugin;
    private final ConcurrentHashMap<Consumer<Void>, Player> playerTickCallbacks = new ConcurrentHashMap<>();
    private final Set<Consumer<Void>> scheduledCallbacks = ConcurrentHashMap.newKeySet();
    private PluginTask globalTickTask;
    private volatile boolean running = false;

    private GuiTickManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public static synchronized GuiTickManager getInstance(FarmersDelightPlugin plugin) {
        if (instance == null || instance.plugin != plugin) {
            if (instance != null) {
                instance.stop();
            }
            instance = new GuiTickManager(plugin);
        }
        return instance;
    }

    public static void cleanup() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        globalTickTask = plugin.scheduler().runRepeating(() -> {
            playerTickCallbacks.forEach((callback, player) -> {
                if (player == null || !scheduledCallbacks.add(callback)) {
                    return;
                }
                try {
                    plugin.scheduler().runForEntity(player, () -> {
                        try {
                            if (player.isOnline()) {
                                callback.accept(null);
                            }
                        } catch (Exception e) {
                            // A broken GUI callback is a bug, not a recoverable condition: always surface
                            // it with the stack so it stays observable in production, not just in debug.
                            // The finally block drops the scheduled flag so the failing GUI still stops
                            // being retried this tick without aborting the loop over the other callbacks.
                            plugin.getLogger().log(Level.WARNING,
                                    "GUI tick callback failed for " + player.getName(), e);
                        } finally {
                            scheduledCallbacks.remove(callback);
                        }
                    }, () -> scheduledCallbacks.remove(callback));
                } catch (RuntimeException e) {
                    scheduledCallbacks.remove(callback);
                    playerTickCallbacks.remove(callback);
                }
            });
        }, TICK_INTERVAL, TICK_INTERVAL);
    }

    public synchronized void stop() {
        running = false;
        if (globalTickTask != null) {
            globalTickTask.cancel();
            globalTickTask = null;
        }
        playerTickCallbacks.clear();
        scheduledCallbacks.clear();
    }

    public synchronized void registerCallback(Player player, Consumer<Void> callback) {
        if (player == null) {
            return;
        }
        playerTickCallbacks.put(callback, player);
        if (!running && getActiveCallbackCount() > 0) {
            start();
        }
    }

    public synchronized void unregisterCallback(Consumer<Void> callback) {
        playerTickCallbacks.remove(callback);
        scheduledCallbacks.remove(callback);
        if (getActiveCallbackCount() == 0) {
            stop();
        }
    }

    public int getActiveCallbackCount() {
        return playerTickCallbacks.size();
    }
}
