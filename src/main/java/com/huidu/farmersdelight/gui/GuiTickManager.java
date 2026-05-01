package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class GuiTickManager {

    private static final long TICK_INTERVAL = 4L;
    private static GuiTickManager instance;
    private final FarmersDelightPlugin plugin;
    private final ConcurrentHashMap<Consumer<Void>, Boolean> tickCallbacks = new ConcurrentHashMap<>();
    private BukkitTask globalTickTask;
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

        globalTickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tickCallbacks.forEach((callback, ignored) -> {
                try {
                    callback.accept(null);
                } catch (Exception e) {
                    if (plugin.getConfig().getBoolean("debug", false)) {
                        plugin.getLogger().warning("Error in GUI tick callback: " + e.getMessage());
                    }
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
        tickCallbacks.clear();
    }

    public void registerCallback(Consumer<Void> callback) {
        tickCallbacks.put(callback, Boolean.TRUE);
        if (!running && !tickCallbacks.isEmpty()) {
            start();
        }
    }

    public void unregisterCallback(Consumer<Void> callback) {
        tickCallbacks.remove(callback);
        if (tickCallbacks.isEmpty()) {
            stop();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveCallbackCount() {
        return tickCallbacks.size();
    }
}
