package com.huidu.farmersdelight.util.scheduler;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SchedulerAdapter {

    private final FarmersDelightPlugin plugin;
    private final boolean folia;
    private final ExecutorService asyncExecutor;

    public SchedulerAdapter(FarmersDelightPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.folia = isClassPresent("io.papermc.paper.threadedregions.RegionizedServer");
        this.asyncExecutor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
                new NamedThreadFactory()
        );
    }

    public boolean isFolia() {
        return folia;
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

    public void runAt(World world, int chunkX, int chunkZ, Runnable task) {
        if (!folia || world == null) {
            run(task);
            return;
        }
        FoliaReflect.regionExecute(plugin, world, chunkX, chunkZ, task);
    }

    public void runForEntity(Entity entity, Runnable task) {
        if (!folia || entity == null) {
            run(task);
            return;
        }
        FoliaReflect.entityRun(plugin, entity, task);
    }

    public PluginTask runLater(Runnable task, long delayTicks) {
        if (folia) {
            return FoliaReflect.globalRunLater(plugin, task, delayTicks);
        }
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delayTicks)));
    }

    public PluginTask runLaterAt(Location location, Runnable task, long delayTicks) {
        if (location == null || !folia) {
            return runLater(task, delayTicks);
        }
        World world = location.getWorld();
        if (world == null) {
            return runLater(task, delayTicks);
        }
        return FoliaReflect.regionRunLater(
                plugin,
                world,
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4,
                task,
                delayTicks
        );
    }

    public PluginTask runLaterForEntity(Entity entity, Runnable task, long delayTicks) {
        if (entity == null || !folia) {
            return runLater(task, delayTicks);
        }
        return FoliaReflect.entityRunLater(plugin, entity, task, delayTicks);
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

    public void runAsync(Runnable task) {
        asyncExecutor.execute(task);
    }

    public void shutdown() {
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

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, SchedulerAdapter.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
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
        private static final Object GLOBAL_SCHEDULER = invokeStatic(Bukkit.class, "getGlobalRegionScheduler");
        private static final Object REGION_SCHEDULER = invokeStatic(Bukkit.class, "getRegionScheduler");

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

        private static Object invokeStatic(Class<?> type, String methodName) {
            try {
                Method method = type.getMethod(methodName);
                return method.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Folia scheduler method unavailable: " + methodName, e);
            }
        }

        private static Object invoke(Object target, String methodName, Object... args) {
            try {
                Method method = findMethod(target.getClass(), methodName, args.length);
                Object[] adaptedArgs = adaptArgs(method.getParameterTypes(), args);
                if (!method.canAccess(target)) {
                    method.setAccessible(true);
                }
                return method.invoke(target, adaptedArgs);
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

        private static Object[] adaptArgs(Class<?>[] parameterTypes, Object[] args) {
            Object[] adapted = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                Class<?> parameterType = parameterTypes[i];
                if (arg instanceof Runnable runnable && isConsumer(parameterType)) {
                    adapted[i] = (java.util.function.Consumer<Object>) ignored -> runnable.run();
                } else {
                    adapted[i] = arg;
                }
            }
            return adapted;
        }

        private static boolean isConsumer(Class<?> type) {
            return java.util.function.Consumer.class.isAssignableFrom(type);
        }
    }
}
