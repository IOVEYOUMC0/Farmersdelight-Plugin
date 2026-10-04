package com.huidu.farmersdelight.util.scheduler;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.ShutdownBudget;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class SchedulerAdapter implements RegionDispatcher {

    // Queued async work is per-player file IO (recipe-discovery loads and flushes, datapack removal). A
    // server with more than this many tasks waiting has a disk problem rather than a burst, and holding the
    // rest would only delay the work while it keeps growing, so the pool refuses it and the caller decides.
    private static final int ASYNC_QUEUE_CAPACITY = 256;

    private final FarmersDelightPlugin plugin;
    private final boolean folia;
    private final ThreadPoolExecutor asyncExecutor;

    public SchedulerAdapter(FarmersDelightPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.folia = isClassPresent();
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
        return FoliaReflect.isOwnedByCurrentRegion(location);
    }

    public void run(Runnable task) {
        if (folia) {
            FoliaReflect.globalExecute(plugin, task);
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
        FoliaReflect.regionExecute(plugin, world, chunkX, chunkZ, task);
    }

    @Override
    public void runForEntity(Entity entity, Runnable task) {
        if (!folia || entity == null) {
            run(task);
            return;
        }
        FoliaReflect.entityRun(plugin, entity, task);
    }

    @Override
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        if (!folia || entity == null) {
            run(task);
            return;
        }
        FoliaReflect.entityRun(plugin, entity, task, retired);
    }

    public PluginTask runLater(Runnable task, long delayTicks) {
        if (folia) {
            return FoliaReflect.globalRunLater(plugin, task, delayTicks);
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
        FoliaReflect.regionRunLater(
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
        FoliaReflect.entityRunLater(plugin, entity, task, delayTicks);
    }

    public PluginTask runRepeating(Runnable task, long delayTicks, long periodTicks) {
        if (folia) {
            return FoliaReflect.globalRunRepeating(plugin, task, delayTicks, periodTicks);
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
        return FoliaReflect.regionRunRepeating(
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
        asyncExecutor.execute(Objects.requireNonNull(task, "task"));
    }

    /**
     * Runs the task on the async pool, tolerating a full queue.
     *
     * @return false when the task was refused and therefore never ran
     */
    public boolean tryRunAsync(Runnable task) {
        try {
            asyncExecutor.execute(Objects.requireNonNull(task, "task"));
            return true;
        } catch (RejectedExecutionException refused) {
            return false;
        }
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
            return;
        }
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asyncExecutor.shutdownNow();
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
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "farmersdelight-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class FoliaReflect {
        private static final Object GLOBAL_SCHEDULER = invokeStatic("getGlobalRegionScheduler");
        private static final Object REGION_SCHEDULER = invokeStatic("getRegionScheduler");
        // The resolved scheduler Method is stable per (class, name, arity) combination; cache it
        // so each schedule on Folia avoids re-walking the class/interface hierarchy.
        // Keyed by the receiver's Class so the lookup needs no string building: this runs on every
        // scheduler dispatch, several thousand times a second on a busy server. The entry also carries
        // the already-resolved Consumer positions, because Method.getParameterTypes() hands back a fresh
        // clone of its array on each call and adaptArgs only ever asks the same question of it.
        private record SchedulerMethod(Method method, boolean[] wantsConsumer, boolean needsAdapter) {
        }

        // Nested by method name and then arity: the arity is part of the cache identity because one name can
        // be overloaded by parameter count, and nesting it keeps the hot path clear of the per-call key string
        // this lookup used to build. The arity Integer is interned for the small values schedulers use.
        private static final Map<Class<?>, Map<String, Map<Integer, SchedulerMethod>>> METHOD_CACHE =
                new ConcurrentHashMap<>();

        private FoliaReflect() {
        }

        private static void globalExecute(FarmersDelightPlugin plugin, Runnable task) {
            invoke(GLOBAL_SCHEDULER, "execute", plugin, task);
        }

        private static void regionExecute(FarmersDelightPlugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
            invoke(REGION_SCHEDULER, "execute", plugin, world, chunkX, chunkZ, task);
        }

        private static PluginTask globalRunLater(FarmersDelightPlugin plugin, Runnable task, long delayTicks) {
            Object scheduled = delayTicks <= 0L
                    ? invoke(GLOBAL_SCHEDULER, "run", plugin, task)
                    : invoke(GLOBAL_SCHEDULER, "runDelayed", plugin, task, Math.max(1L, delayTicks));
            return wrapScheduledTask(scheduled);
        }

        private static PluginTask regionRunLater(FarmersDelightPlugin plugin, World world, int chunkX, int chunkZ,
                                                 Runnable task, long delayTicks) {
            Object scheduled = delayTicks <= 0L
                    ? invoke(REGION_SCHEDULER, "run", plugin, world, chunkX, chunkZ, task)
                    : invoke(REGION_SCHEDULER, "runDelayed", plugin, world, chunkX, chunkZ, task, Math.max(1L, delayTicks));
            return wrapScheduledTask(scheduled);
        }

        private static PluginTask globalRunRepeating(FarmersDelightPlugin plugin, Runnable task, long delayTicks, long periodTicks) {
            Object scheduled = invoke(
                    GLOBAL_SCHEDULER,
                    "runAtFixedRate",
                    plugin,
                    task,
                    Math.max(1L, delayTicks),
                    Math.max(1L, periodTicks)
            );
            return wrapScheduledTask(scheduled);
        }

        private static PluginTask regionRunRepeating(FarmersDelightPlugin plugin, World world, int chunkX, int chunkZ,
                                                     Runnable task, long delayTicks, long periodTicks) {
            Object scheduled = invoke(
                    REGION_SCHEDULER,
                    "runAtFixedRate",
                    plugin,
                    world,
                    chunkX,
                    chunkZ,
                    task,
                    Math.max(1L, delayTicks),
                    Math.max(1L, periodTicks)
            );
            return wrapScheduledTask(scheduled);
        }

        private static void entityRun(FarmersDelightPlugin plugin, Entity entity, Runnable task) {
            Object scheduler = invoke(entity, "getScheduler");
            invoke(scheduler, "run", plugin, task, null);
        }

        private static void entityRun(FarmersDelightPlugin plugin, Entity entity, Runnable task, Runnable retired) {
            Object scheduler = invoke(entity, "getScheduler");
            Object scheduled = invoke(scheduler, "run", plugin, task, retired);
            // EntityScheduler#run returns null when the entity is ALREADY retired, and in that case it
            // never invokes the retired callback itself. Callers use that callback to release an in-flight
            // guard, so without this the guard would stay set forever and the entity stop being ticked.
            if (scheduled == null && retired != null) {
                retired.run();
            }
        }

        private static boolean isOwnedByCurrentRegion(Location location) {
            // Called straight into the API rather than reflectively: it is on the compile classpath, and the
            // other Folia-aware call sites (the basket vacuum, the buff bossbar) already call it directly.
            // This is a per-interaction check that the handheld skillet reaches up to 27 times per use, so the
            // reflective lookup it used to do - a getMethod scan plus a parameter-array clone, every call -
            // was the hottest reflection in the plugin.
            return Bukkit.isOwnedByCurrentRegion(location);
        }

        private static PluginTask entityRunLater(FarmersDelightPlugin plugin, Entity entity, Runnable task, long delayTicks) {
            Object scheduler = invoke(entity, "getScheduler");
            Object scheduled = delayTicks <= 0L
                    ? invoke(scheduler, "run", plugin, task, null)
                    : invoke(scheduler, "runDelayed", plugin, task, null, Math.max(1L, delayTicks));
            return wrapScheduledTask(scheduled);
        }

        private static PluginTask wrapScheduledTask(Object scheduledTask) {
            if (scheduledTask == null) {
                return PluginTask.NOOP;
            }
            return new PluginTask() {
                @Override
                public void cancel() {
                    invoke(scheduledTask, "cancel");
                }

                @Override
                public boolean isCancelled() {
                    Object state = invoke(scheduledTask, "getExecutionState");
                    return state != null && state.toString().contains("CANCEL");
                }
            };
        }

        private static Object invokeStatic(String methodName) {
            try {
                Method method = Bukkit.class.getMethod(methodName);
                return method.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Folia scheduler method unavailable: " + methodName, e);
            }
        }

        private static Object invoke(Object target, String methodName, Object... args) {
            try {
                Class<?> targetClass = target.getClass();
                Map<String, Map<Integer, SchedulerMethod>> perClass =
                        METHOD_CACHE.computeIfAbsent(targetClass, key -> new ConcurrentHashMap<>());
                Map<Integer, SchedulerMethod> perArity =
                        perClass.computeIfAbsent(methodName, key -> new ConcurrentHashMap<>());
                SchedulerMethod entry = perArity.get(args.length);
                if (entry == null) {
                    Method method = findMethod(targetClass, methodName, args.length);
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    boolean[] wantsConsumer = new boolean[parameterTypes.length];
                    boolean needsAdapter = false;
                    for (int i = 0; i < parameterTypes.length; i++) {
                        wantsConsumer[i] = isConsumer(parameterTypes[i]);
                        needsAdapter |= wantsConsumer[i];
                    }
                    if (!method.canAccess(target)) {
                        method.setAccessible(true);
                    }
                    entry = new SchedulerMethod(method, wantsConsumer, needsAdapter);
                    perArity.put(args.length, entry);
                }
                // Only a call that hands a Runnable to a Consumer parameter needs the copied array; every
                // other dispatch passes the varargs array it already has.
                Object[] callArgs = entry.needsAdapter() ? adaptArgs(entry.wantsConsumer(), args) : args;
                return entry.method().invoke(target, callArgs);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to invoke Folia scheduler method " + methodName, e);
            }
        }

        private static Method findMethod(Class<?> type, String methodName, int parameterCount) throws NoSuchMethodException {
            Method interfaceMethod = findInterfaceMethod(type, methodName, parameterCount);
            if (interfaceMethod != null) {
                return interfaceMethod;
            }
            for (Method method : type.getMethods()) {
                if (matches(method, methodName, parameterCount) && isPublic(method.getDeclaringClass())) {
                    return method;
                }
            }
            throw new NoSuchMethodException(type.getName() + "#" + methodName + "/" + parameterCount);
        }

        private static Method findInterfaceMethod(Class<?> type, String methodName, int parameterCount) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Class<?> interfaceType : current.getInterfaces()) {
                    Method method = findInterfaceMethodRecursive(interfaceType, methodName, parameterCount);
                    if (method != null) {
                        return method;
                    }
                }
            }
            return null;
        }

        private static Method findInterfaceMethodRecursive(Class<?> interfaceType, String methodName, int parameterCount) {
            if (!isPublic(interfaceType)) {
                return null;
            }
            for (Method method : interfaceType.getMethods()) {
                if (matches(method, methodName, parameterCount) && isPublic(method.getDeclaringClass())) {
                    return method;
                }
            }
            for (Class<?> parentInterface : interfaceType.getInterfaces()) {
                Method method = findInterfaceMethodRecursive(parentInterface, methodName, parameterCount);
                if (method != null) {
                    return method;
                }
            }
            return null;
        }

        private static boolean matches(Method method, String methodName, int parameterCount) {
            return method.getName().equals(methodName) && method.getParameterCount() == parameterCount;
        }

        private static boolean isPublic(Class<?> type) {
            return Modifier.isPublic(type.getModifiers());
        }

        private static Object[] adaptArgs(boolean[] wantsConsumer, Object[] args) {
            Object[] adapted = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Runnable runnable && i < wantsConsumer.length && wantsConsumer[i]) {
                    adapted[i] = (Consumer<Object>) ignored -> runnable.run();
                } else {
                    adapted[i] = arg;
                }
            }
            return adapted;
        }

        private static boolean isConsumer(Class<?> type) {
            return Consumer.class.isAssignableFrom(type);
        }
    }
}
