package com.huidu.farmersdelight.util.compat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;

public final class WorldGuardCompat {
    private static final String WORLD_GUARD_PLUGIN = "WorldGuard";
    /** Master gate. /rg flag <region> farmersdelight-use deny blocks ALL FarmersDelight custom
     *  interactions in a region regardless of the per-feature flags below. Default ALLOW = no change. */
    private static final String MASTER_FLAG_NAME = "farmersdelight-use";

    // Per-station protection features live on the ProtectionCompat facade; this class maps each to its
    // WorldGuard StateFlag (feature.flagName()) and defaults every flag to ALLOW (unset = no change).
    private static volatile Boolean available;
    private static volatile Object buildFlag;
    private static volatile Object useFlag;
    // Registered StateFlag instances resolved once in registerFlags() during onLoad. null when
    // WorldGuard is absent / registration failed -> the query layer treats a null flag as ALLOW.
    private static volatile Object masterFlag;
    private static volatile Map<ProtectionCompat.Feature, Object> featureFlags = Map.of();
    // Reflection handles are resolved once and reused, so per-interaction canUse/canBuild queries
    // don't repeat Class.forName + getMethod and the full getMethods() scan.
    private static volatile Object cachedRegionContainer;
    private static volatile Method createQueryMethod;
    private static volatile Method adaptLocationMethod;
    private static volatile Method adaptPlayerMethod;
    private static volatile Method testStateMethod;

    private WorldGuardCompat() {
    }

    /**
     * Register FarmersDelight's custom region flags with WorldGuard (master + one per ProtectionCompat.Feature).
     * MUST be called during the plugin load phase (onLoad) -- WorldGuard locks its FlagRegistry the
     * moment it enables, so a late registration throws. No-op when WorldGuard is not installed. If
     * another plugin already registered a flag by the same name, the existing StateFlag is reused (WG
     * persists flag values on regions even across restarts, so reusing keeps admin settings intact).
     */
    public static void registerFlags() {
        try {
            if (Bukkit.getPluginManager().getPlugin(WORLD_GUARD_PLUGIN) == null) {
                return;
            }
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object worldGuard = worldGuardClass.getMethod("getInstance").invoke(null);
            Object registry = worldGuardClass.getMethod("getFlagRegistry").invoke(worldGuard);
            Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
            Method register = findMethod(registry.getClass(), "register", 1);
            Method get = findMethod(registry.getClass(), "get", 1);
            if (register == null) {
                return;
            }
            masterFlag = registerStateFlag(registry, stateFlagClass, register, get, MASTER_FLAG_NAME);
            EnumMap<ProtectionCompat.Feature, Object> resolved = new EnumMap<>(ProtectionCompat.Feature.class);
            for (ProtectionCompat.Feature feature : ProtectionCompat.Feature.values()) {
                Object flag = registerStateFlag(registry, stateFlagClass, register, get, feature.flagName());
                if (flag != null) {
                    resolved.put(feature, flag);
                }
            }
            featureFlags = resolved.isEmpty() ? Map.of() : Map.copyOf(resolved);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            masterFlag = null;
            featureFlags = Map.of();
        }
    }

    /** Register one StateFlag by name; on FlagConflictException reuse the existing StateFlag. Returns
     *  the flag instance, or null if it could not be created/reused. */
    private static Object registerStateFlag(Object registry, Class<?> stateFlagClass, Method register,
                                            Method get, String name) {
        try {
            Object flag = stateFlagClass.getConstructor(String.class, boolean.class).newInstance(name, true);
            try {
                register.invoke(registry, flag);
                return flag;
            } catch (ReflectiveOperationException conflict) {
                Object existing = get != null ? get.invoke(registry, name) : null;
                return stateFlagClass.isInstance(existing) ? existing : null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    // -- ProtectionCompat.Feature-aware overloads: WG BUILD/USE flag AND master flag AND the feature's own flag. --
    public static boolean canBuild(Player player, Location location, ProtectionCompat.Feature feature) {
        return testFlagState(player, location, buildFlagOrNull()) && customAllows(player, location, feature);
    }

    public static boolean canUse(Player player, Location location, ProtectionCompat.Feature feature) {
        return testFlagState(player, location, useFlagOrNull()) && customAllows(player, location, feature);
    }

    private static Object buildFlagOrNull() {
        try {
            return buildFlag();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object useFlagOrNull() {
        try {
            return useFlag();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** Custom flag gate: master flag AND (if a feature is given) the feature's own flag. Each is ALLOW
     *  when unregistered / WG absent / unset, so a fresh server with no flags set sees no change. */
    private static boolean customAllows(Player player, Location location, ProtectionCompat.Feature feature) {
        Object master = masterFlag;
        if (master != null && !testFlagState(player, location, master)) {
            return false;
        }
        if (feature == null) {
            return true;
        }
        Object flag = featureFlags.get(feature);
        return flag == null || testFlagState(player, location, flag);
    }

    /** Query one WorldGuard StateFlag at a location for a player. Returns true (allow) on any failure,
     *  when WG is absent, or when the flag is null -- protection never fails closed on our account. */
    private static boolean testFlagState(Player player, Location location, Object flag) {
        if (player == null || location == null || location.getWorld() == null || flag == null || !isAvailable()) {
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
            if (query == null || adaptedLocation == null || localPlayer == null) {
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
