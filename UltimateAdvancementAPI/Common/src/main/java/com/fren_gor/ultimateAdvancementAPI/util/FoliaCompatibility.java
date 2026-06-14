package com.fren_gor.ultimateAdvancementAPI.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Minimal Folia support: routes the few scheduling calls this plugin makes through Folia's global-region
 * and async schedulers when running on Folia, and through the Bukkit scheduler otherwise. The Folia API
 * is invoked reflectively so this module keeps compiling against spigot-api.
 */
public final class FoliaCompatibility {

    public static final boolean FOLIA;
    private static final Method GET_GLOBAL_SCHEDULER, GLOBAL_RUN_DELAYED, GLOBAL_EXECUTE,
            GET_ASYNC_SCHEDULER, ASYNC_RUN_NOW, TASK_CANCEL;

    static {
        boolean folia;
        Method getGlobal = null, globalRunDelayed = null, globalExecute = null,
                getAsync = null, asyncRunNow = null, taskCancel = null;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Class<?> globalScheduler = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> asyncScheduler = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            Class<?> scheduledTask = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            getGlobal = Bukkit.class.getMethod("getGlobalRegionScheduler");
            getAsync = Bukkit.class.getMethod("getAsyncScheduler");
            globalRunDelayed = globalScheduler.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
            globalExecute = globalScheduler.getMethod("execute", Plugin.class, Runnable.class);
            asyncRunNow = asyncScheduler.getMethod("runNow", Plugin.class, Consumer.class);
            taskCancel = scheduledTask.getMethod("cancel");
            folia = true;
        } catch (Throwable t) {
            folia = false;
        }
        FOLIA = folia;
        GET_GLOBAL_SCHEDULER = getGlobal;
        GLOBAL_RUN_DELAYED = globalRunDelayed;
        GLOBAL_EXECUTE = globalExecute;
        GET_ASYNC_SCHEDULER = getAsync;
        ASYNC_RUN_NOW = asyncRunNow;
        TASK_CANCEL = taskCancel;
    }

    private FoliaCompatibility() {
    }

    /** Runs runnable on the global/main thread after delayTicks; returns a cancellable handle. */
    public static Task runSyncLater(@NotNull Plugin plugin, @NotNull Runnable runnable, long delayTicks) {
        if (FOLIA) {
            try {
                Object scheduler = GET_GLOBAL_SCHEDULER.invoke(null);
                if (delayTicks <= 0L) {
                    GLOBAL_EXECUTE.invoke(scheduler, plugin, runnable);
                    return new Task(null);
                }
                Consumer<Object> consumer = ignored -> runnable.run();
                return new Task(GLOBAL_RUN_DELAYED.invoke(scheduler, plugin, consumer, delayTicks));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        return new Task(Bukkit.getScheduler().runTaskLater(plugin, runnable, Math.max(0L, delayTicks)));
    }

    /** Runs runnable off the main thread. */
    public static void runAsync(@NotNull Plugin plugin, @NotNull Runnable runnable) {
        if (FOLIA) {
            try {
                Object scheduler = GET_ASYNC_SCHEDULER.invoke(null);
                Consumer<Object> consumer = ignored -> runnable.run();
                ASYNC_RUN_NOW.invoke(scheduler, plugin, consumer);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        }
    }

    /** Cancellable handle wrapping a Folia ScheduledTask or a BukkitTask. */
    public static final class Task {
        private final Object handle;

        private Task(Object handle) {
            this.handle = handle;
        }

        public void cancel() {
            if (handle == null) {
                return;
            }
            try {
                if (FOLIA) {
                    TASK_CANCEL.invoke(handle);
                } else if (handle instanceof BukkitTask) {
                    ((BukkitTask) handle).cancel();
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }
}
