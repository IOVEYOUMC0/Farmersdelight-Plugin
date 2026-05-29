package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.Consumer;

public class GuiTickManager {

    private static final long TICK_INTERVAL = 4L;
    private static GuiTickManager instance;
    private final FarmersDelightPlugin plugin;
    private final ConcurrentHashMap<Consumer<Void>, Player> playerTickCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Consumer<Void>, Boolean> globalTickCallbacks = new ConcurrentHashMap<>();
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

    public void start() {
        if (running) return;
        running = true;

        globalTickTask = plugin.scheduler().runRepeating(() -> {
            globalTickCallbacks.forEach((callback, ignored) -> {
                try {
                    callback.accept(null);
                } catch (Exception e) {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().warning(I18n.formatConsole("gui_runtime.tick_callback_failed",
                                "error", e.getMessage()));
                    }
                }
            });
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
                            if (plugin.isDebugEnabled()) {
                                plugin.getLogger().warning(I18n.formatConsole("gui_runtime.tick_callback_failed",
                                        "error", e.getMessage()));
                            }
                        } finally {
                            scheduledCallbacks.remove(callback);
                        }
                    });
                } catch (RuntimeException e) {
                    scheduledCallbacks.remove(callback);
                    playerTickCallbacks.remove(callback);
                }
            });
        }, TICK_INTERVAL, TICK_INTERVAL);
    }

    public void stop() {
        running = false;
        if (globalTickTask != null) {
            globalTickTask.cancel();
            globalTickTask = null;
        }
        playerTickCallbacks.clear();
        globalTickCallbacks.clear();
        scheduledCallbacks.clear();
    }

    public void registerCallback(Consumer<Void> callback) {
        globalTickCallbacks.put(callback, Boolean.TRUE);
        if (!running && getActiveCallbackCount() > 0) {
            start();
        }
    }

    public void registerCallback(Player player, Consumer<Void> callback) {
        if (player == null) {
            registerCallback(callback);
            return;
        }
        playerTickCallbacks.put(callback, player);
        if (!running && getActiveCallbackCount() > 0) {
            start();
        }
    }

    public void unregisterCallback(Consumer<Void> callback) {
        playerTickCallbacks.remove(callback);
        globalTickCallbacks.remove(callback);
        scheduledCallbacks.remove(callback);
        if (getActiveCallbackCount() == 0) {
            stop();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveCallbackCount() {
        return playerTickCallbacks.size() + globalTickCallbacks.size();
    }
}

