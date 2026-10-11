package com.huidu.farmersdelight.util.scheduler;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.ShutdownBudget;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SchedulerAdapter implements RegionDispatcher {

    // Queued async work is per-player file IO (recipe-discovery loads and flushes, datapack removal). A
    // server with more than this many tasks waiting has a disk problem rather than a burst, and holding the
    // rest would only delay the work while it keeps growing, so the pool refuses it and the caller decides.
    private static final int ASYNC_QUEUE_CAPACITY = 256;

    private final FarmersDelightPlugin plugin;
    private final boolean folia;
    private final ThreadPoolExecutor asyncExecutor;
    private final AsyncPoolMetrics asyncMetrics = new AsyncPoolMetrics();
    private final BlockedThreadDump stalledPoolDump;

    public SchedulerAdapter(FarmersDelightPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.folia = isClassPresent();
        this.stalledPoolDump = BlockedThreadDump.toLogger(plugin.getLogger());
        this.asyncExecutor = BoundedExecutor.create(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
                ASYNC_QUEUE_CAPACITY,
                new NamedThreadFactory()
        );
    }

    public boolean isFolia() {
        return folia;
    }

    public boolean isOwnedByCurrentRegion(Location location) {
        if (!folia || location == null || location.getWorld() == null) {
            return true;
        }
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    public void run(Runnable task) {
        if (folia) {
            FoliaTasks.globalExecute(plugin, task);
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAt(Location location, Runnable task) {
        if (location == null) {
            run(task);
            return;
        }
        runAt(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, task);
    }

    @Override
    public void runAt(World world, int chunkX, int chunkZ, Runnable task) {
        if (!folia || world == null) {
            run(task);
            return;
        }
        FoliaTasks.regionExecute(plugin, world, chunkX, chunkZ, task);
    }

    @Override
    public void runForEntity(Entity entity, Runnable task) {
        if (!folia || entity == null) {
            run(task);
            return;
        }
        FoliaTasks.entityRun(plugin, entity, task, null);
    }

    @Override
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        if (!folia || entity == null) {
            run(task);
            return;
        }
        FoliaTasks.entityRun(plugin, entity, task, retired);
    }

    public PluginTask runLater(Runnable task, long delayTicks) {
        if (folia) {
            return FoliaTasks.globalRunLater(plugin, task, delayTicks);
        }
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delayTicks)));
    }

    public void runLaterAt(Location location, Runnable task, long delayTicks) {
        if (location == null || !folia) {
            runLater(task, delayTicks);
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            runLater(task, delayTicks);
            return;
        }
        FoliaTasks.regionRunLater(
                plugin,
                world,
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4,
                task,
                delayTicks
        );
    }

    public void runLaterForEntity(Entity entity, Runnable task, long delayTicks) {
        if (entity == null || !folia) {
            runLater(task, delayTicks);
            return;
        }
        FoliaTasks.entityRunLater(plugin, entity, task, delayTicks);
    }

    public PluginTask runRepeating(Runnable task, long delayTicks, long periodTicks) {
        if (folia) {
            return FoliaTasks.globalRunRepeating(plugin, task, delayTicks, periodTicks);
        }
        return wrap(Bukkit.getScheduler().runTaskTimer(
                plugin,
                task,
                Math.max(1L, delayTicks),
                Math.max(1L, periodTicks)
        ));
    }

    public PluginTask runRepeatingAt(Location location, Runnable task, long delayTicks, long periodTicks) {
        if (location == null || !folia) {
            return runRepeating(task, delayTicks, periodTicks);
        }
        World world = location.getWorld();
        if (world == null) {
            return runRepeating(task, delayTicks, periodTicks);
        }
        return FoliaTasks.regionRunRepeating(
                plugin,
                world,
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4,
                task,
                delayTicks,
                periodTicks
        );
    }

    /**
     * Runs the task on the async pool.
     *
     * @throws java.util.concurrent.RejectedExecutionException when the queue is full or the pool is shut
     *         down; the task did not run, so the caller still owns whatever it was going to flush
     */
    public void runAsync(Runnable task) {
        asyncMetrics.submit(asyncExecutor, task);
    }

    /**
     * Runs the task on the async pool, tolerating a full queue.
     *
     * @return false when the task was refused and therefore never ran
     */
    public boolean tryRunAsync(Runnable task) {
        return asyncMetrics.trySubmit(asyncExecutor, task);
    }

    /** One reading of the async pool's counters, for the runtime statistics screen. */
    public AsyncSnapshot asyncSnapshot() {
        return asyncMetrics.snapshot(asyncExecutor);
    }

    public void shutdown() {
        shutdown(null);
    }

    /**
     * Drains the async pool. With a budget the wait is whatever the shutdown has left; without one it
     * falls back to a fixed ten seconds. A ten-second wait per component is exactly how a shutdown ends
     * up taking a minute, so callers inside a shutdown sequence should pass the shared budget.
     */
    public void shutdown(ShutdownBudget budget) {
        if (budget != null) {
            budget.awaitTermination("farmersdelight async pool", asyncExecutor);
            asyncMetrics.accountTerminated(asyncExecutor);
            return;
        }
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                // Recorded before the force-cancel below, while the stuck work is still on its stack.
                stalledPoolDump.report("async pool did not drain within 10 seconds");
                asyncMetrics.forcedShutdown(asyncExecutor);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asyncMetrics.forcedShutdown(asyncExecutor);
        }
    }

    private static PluginTask wrap(BukkitTask task) {
        return new PluginTask() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }

    static PluginTask wrap(ScheduledTask task) {
        if (task == null) return PluginTask.NOOP;
        return new PluginTask() {
            @Override
            public void cancel() { task.cancel(); }

            @Override
            public boolean isCancelled() { return task.isCancelled(); }
        };
    }

    private static boolean isClassPresent() {
        String className = "io.papermc.paper.threadedregions.RegionizedServer";
        ClassLoader[] loaders = {
                SchedulerAdapter.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                Bukkit.getServer() == null ? null : Bukkit.getServer().getClass().getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next loader; plugin and server classes may use different class loaders.
            }
        }
        return false;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public @NotNull Thread newThread(@NotNull Runnable runnable) {
            Thread thread = new Thread(runnable, "farmersdelight-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class FoliaTasks {
        private FoliaTasks() { }

        private static void globalExecute(FarmersDelightPlugin plugin, Runnable task) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }

        private static void regionExecute(FarmersDelightPlugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
        }

        private static PluginTask globalRunLater(FarmersDelightPlugin plugin, Runnable task, long delayTicks) {
            var scheduler = Bukkit.getGlobalRegionScheduler();
            return wrap(delayTicks <= 0L ? scheduler.run(plugin, ignored -> task.run())
                    : scheduler.runDelayed(plugin, ignored -> task.run(), delayTicks));
        }

        private static void regionRunLater(FarmersDelightPlugin plugin, World world, int chunkX, int chunkZ,
                                                 Runnable task, long delayTicks) {
            var scheduler = Bukkit.getRegionScheduler();
            if (delayTicks <= 0L) scheduler.run(plugin, world, chunkX, chunkZ, ignored -> task.run());
            else scheduler.runDelayed(plugin, world, chunkX, chunkZ, ignored -> task.run(), delayTicks);
        }

        private static PluginTask globalRunRepeating(FarmersDelightPlugin plugin, Runnable task, long delayTicks, long periodTicks) {
            return wrap(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(),
                    Math.max(1L, delayTicks), Math.max(1L, periodTicks)));
        }

        private static PluginTask regionRunRepeating(FarmersDelightPlugin plugin, World world, int chunkX, int chunkZ,
                                                     Runnable task, long delayTicks, long periodTicks) {
            return wrap(Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, chunkX, chunkZ,
                    ignored -> task.run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks)));
        }

        private static void entityRun(FarmersDelightPlugin plugin, Entity entity, Runnable task, Runnable retired) {
            ScheduledTask scheduled = entity.getScheduler().run(plugin, ignored -> task.run(), retired);
            // An already-retired entity rejects scheduling without invoking its callback.
            if (scheduled == null && retired != null) retired.run();
        }

        private static void entityRunLater(FarmersDelightPlugin plugin, Entity entity, Runnable task, long delayTicks) {
            var scheduler = entity.getScheduler();
            if (delayTicks <= 0L) scheduler.run(plugin, ignored -> task.run(), null);
            else scheduler.runDelayed(plugin, ignored -> task.run(), null, delayTicks);
        }
    }
}
