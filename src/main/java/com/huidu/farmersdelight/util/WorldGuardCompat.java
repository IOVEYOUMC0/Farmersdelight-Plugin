package com.huidu.farmersdelight.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class WorldGuardCompat {
    private static final String WORLD_GUARD_PLUGIN = "WorldGuard";

    private static volatile Boolean available;
    private static volatile Object buildFlag;
    private static volatile Object useFlag;
    // 反射句柄只解析一次并复用，因此每次交互的 canUse/canBuild 查询都
    // 不会重复执行 Class.forName + getMethod 以及完整的 getMethods() 扫描。
    private static volatile Object cachedRegionContainer;
    private static volatile Method createQueryMethod;
    private static volatile Method adaptLocationMethod;
    private static volatile Method adaptPlayerMethod;
    private static volatile Method testStateMethod;

    private WorldGuardCompat() {
    }

    public static boolean canBuild(Player player, Block block) {
        return block == null || canBuild(player, block.getLocation());
    }

    public static boolean canBuild(Player player, Location location) {
        return query(player, location, true);
    }

    public static boolean canUse(Player player, Block block) {
        return block == null || canUse(player, block.getLocation());
    }

    public static boolean canUse(Player player, Location location) {
        return query(player, location, false);
    }

    private static boolean query(Player player, Location location, boolean build) {
        if (player == null || location == null || location.getWorld() == null || !isAvailable()) {
            return true;
        }

        try {
            Object container = regionContainer();
            if (container == null) {
                return true;
            }
            Method createQuery = createQueryMethod;
            if (createQuery == null) {
                createQuery = container.getClass().getMethod("createQuery");
                createQueryMethod = createQuery;
            }
            Object query = createQuery.invoke(container);
            Object adaptedLocation = adaptLocation(location);
            Object localPlayer = adaptPlayer(player);
            Object flag = build ? buildFlag() : useFlag();
            if (query == null || adaptedLocation == null || localPlayer == null || flag == null) {
                return true;
            }

            Method testState = testStateMethod;
            if (testState == null) {
                testState = findMethod(query.getClass(), "testState", 3);
                if (testState == null) {
                    return true;
                }
                testStateMethod = testState;
            }
            Class<?> flagArrayType = testState.getParameterTypes()[2];
            Class<?> flagType = flagArrayType.getComponentType();
            Object flags = Array.newInstance(flagType, 1);
            Array.set(flags, 0, flag);
            Object result = testState.invoke(query, adaptedLocation, localPlayer, flags);
            return !(result instanceof Boolean allowed) || allowed;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return true;
        }
    }

    private static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean loaded = Bukkit.getPluginManager().getPlugin(WORLD_GUARD_PLUGIN) != null
                && Bukkit.getPluginManager().isPluginEnabled(WORLD_GUARD_PLUGIN);
        available = loaded;
        return loaded;
    }

    private static Object regionContainer() throws ReflectiveOperationException {
        Object container = cachedRegionContainer;
        if (container != null) {
            return container;
        }
        Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
        Object worldGuard = worldGuardClass.getMethod("getInstance").invoke(null);
        Object platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuard);
        container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        cachedRegionContainer = container;
        return container;
    }

    private static Object adaptLocation(Location location) throws ReflectiveOperationException {
        Method method = adaptLocationMethod;
        if (method == null) {
            method = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter").getMethod("adapt", Location.class);
            adaptLocationMethod = method;
        }
        return method.invoke(null, location);
    }

    private static Object adaptPlayer(Player player) throws ReflectiveOperationException {
        Method method = adaptPlayerMethod;
        if (method == null) {
            method = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter").getMethod("adapt", Player.class);
            adaptPlayerMethod = method;
        }
        return method.invoke(null, player);
    }

    private static Object buildFlag() throws ReflectiveOperationException {
        Object flag = buildFlag;
        if (flag == null) {
            flag = flag("BUILD");
            buildFlag = flag;
        }
        return flag;
    }

    private static Object useFlag() throws ReflectiveOperationException {
        Object flag = useFlag;
        if (flag == null) {
            flag = flag("USE");
            useFlag = flag;
        }
        return flag;
    }

    private static Object flag(String name) throws ReflectiveOperationException {
        Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
        Field field = flagsClass.getField(name);
        return field.get(null);
    }

    private static Method findMethod(Class<?> owner, String name, int parameterCount) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
                continue;
            }
            return method;
        }
        return null;
    }
}
