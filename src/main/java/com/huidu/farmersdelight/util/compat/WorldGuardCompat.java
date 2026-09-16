package com.huidu.farmersdelight.util.compat;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public final class WorldGuardCompat {
    private static final String WORLD_GUARD_PLUGIN = "WorldGuard";
    private static final String MASTER_FLAG_NAME = "farmersdelight-use";

    // Per-station protection features live on the ProtectionCompat facade; this class maps each to its
    // WorldGuard StateFlag (feature.flagName()) and defaults every flag to ALLOW (unset = no change).
    private static volatile Boolean available;
    private static volatile Object useFlag;
    // Registered StateFlag instances resolved once in registerFlags() during onLoad. null when
    // WorldGuard is absent / registration failed -> the query layer treats a null flag as ALLOW.
    private static volatile Object masterFlag;
    // flagName -> registered StateFlag. Holds the FD feature flags plus any addon-registered flags
    // (addons register their own flag names on their onLoad, after FD's, so they must be tolerated here).
    private static volatile Map<String, Object> customFlags = Map.of();
    // Registry + register/get handles cached from the first registration; reused by registerCustomFlag so
    // addons registering flags late don't re-scan WorldGuard's classes.
    private static volatile Object flagRegistry;
    private static volatile Method registerFlagMethod;
    private static volatile Method getFlagMethod;
    // Reflection handles are resolved once and reused, so per-interaction canUse/canBuild queries
    // don't repeat Class.forName + getMethod and the full getMethods() scan.
    private static volatile Object cachedRegionContainer;
    private static volatile Method createQueryMethod;
    private static volatile Method adaptLocationMethod;
    private static volatile Method wrapPlayerMethod;
    private static volatile Method testStateMethod;
    private static volatile Method testBuildMethod;
    private static volatile Object interactFlag;
    // A failed WorldGuard query falls back to "allowed"; without this flag that fallback is
    // indistinguishable from "no region here", so a broken reflection path would silently disable
    // every protection check for the whole server run.
    private static volatile boolean queryFailureReported;

    private WorldGuardCompat() {
    }

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
            // Start from the current map so addon flags registered before FD's own onLoad are preserved.
            Map<String, Object> resolved = new java.util.HashMap<>(customFlags);
            for (ProtectionCompat.Feature feature : ProtectionCompat.Feature.values()) {
                Object flag = registerStateFlag(registry, stateFlagClass, register, get, feature.flagName());
                if (flag != null) {
                    resolved.put(feature.flagName(), flag);
                }
            }
            customFlags = resolved.isEmpty() ? Map.of() : Map.copyOf(resolved);
            // Cache the handles so addons can register their own flags during their onLoad without re-scanning.
            flagRegistry = registry;
            registerFlagMethod = register;
            getFlagMethod = get;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            masterFlag = null;
            customFlags = Map.of();
        }
    }

    /**
     * Registers one extra StateFlag (typically an addon's own feature flag). Idempotent; a no-op when
     * WorldGuard is absent or the registry was never reachable. Must be called from onLoad (WorldGuard
     * locks its FlagRegistry once it enables).
     */
    public static void registerCustomFlag(String flagName) {
        if (flagName == null || flagName.isBlank() || customFlags.containsKey(flagName)
                || Bukkit.getPluginManager().getPlugin(WORLD_GUARD_PLUGIN) == null) {
            return;
        }
        Object registry = flagRegistry;
        Method register = registerFlagMethod;
        Method get = getFlagMethod;
        if (registry == null || register == null) {
            bootstrapRegistry();
            registry = flagRegistry;
            register = registerFlagMethod;
            get = getFlagMethod;
            if (registry == null || register == null) {
                return;
            }
        }
        try {
            Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
            Object flag = registerStateFlag(registry, stateFlagClass, register, get, flagName);
            if (flag != null) {
                Map<String, Object> copy = new java.util.HashMap<>(customFlags);
                copy.put(flagName, flag);
                customFlags = Map.copyOf(copy);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    private static void bootstrapRegistry() {
        try {
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object worldGuard = worldGuardClass.getMethod("getInstance").invoke(null);
            Object registry = worldGuardClass.getMethod("getFlagRegistry").invoke(worldGuard);
            if (registry == null) {
                return;
            }
            flagRegistry = registry;
            registerFlagMethod = findMethod(registry.getClass(), "register", 1);
            getFlagMethod = findMethod(registry.getClass(), "get", 1);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

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

    // -- Query layer: WG BUILD/USE flag AND master flag AND the feature/addon flag (null flagName = master only). --
    public static boolean canBuild(Player player, Location location, ProtectionCompat.Feature feature) {
        return canBuild(player, location, feature == null ? null : feature.flagName());
    }

    public static boolean canUse(Player player, Location location, ProtectionCompat.Feature feature) {
        return canUse(player, location, feature == null ? null : feature.flagName());
    }

    public static boolean canBuild(Player player, Location location, String flagName) {
        return testBuild(player, location) && customAllows(player, location, flagName);
    }

    public static boolean canUse(Player player, Location location, String flagName) {
        // WorldGuard never queries USE on its own: Flags.USE has no default state, so testState(USE)
        // is false everywhere nobody wrote "-f use allow". Its own RegionProtectionListener folds USE
        // and INTERACT into testBuild, which is what supplies the membership-derived ALLOW.
        return testBuild(player, location, stateFlag("USE"), stateFlag("INTERACT"))
                && customAllows(player, location, flagName);
    }

    private static Object stateFlag(String name) {
        try {
            if ("USE".equals(name)) {
                Object flag = useFlag;
                if (flag == null) {
                    flag = flag(name);
                    useFlag = flag;
                }
                return flag;
            }
            Object flag = interactFlag;
            if (flag == null) {
                flag = flag(name);
                interactFlag = flag;
            }
            return flag;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean customAllows(Player player, Location location, String flagName) {
        Object master = masterFlag;
        if (master != null && !testFlagState(player, location, master)) {
            return false;
        }
        if (flagName == null) {
            return true;
        }
        Object flag = customFlags.get(flagName);
        return flag == null || testFlagState(player, location, flag);
    }

    // Queries a custom StateFlag registered by FD or an addon. Those are created with a default of
    // ALLOW, so testState is the right query for them (unlike Flags.USE, which has no default).
    private static boolean testFlagState(Player player, Location location, Object flag) {
        if (flag == null) {
            return true;
        }
        return runQuery(player, location, "testState", flag);
    }

    // Queries WorldGuard's build permission, optionally folding in extra state flags the way
    // RegionProtectionListener does. Region membership is what supplies the ALLOW here.
    private static boolean testBuild(Player player, Location location, Object... extraFlags) {
        return runQuery(player, location, "testBuild", extraFlags);
    }

    private static boolean runQuery(Player player, Location location, String methodName, Object... flags) {
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
            Object localPlayer = wrapPlayer(player);
            if (query == null || adaptedLocation == null || localPlayer == null) {
                return true;
            }

            boolean build = "testBuild".equals(methodName);
            Method method = build ? testBuildMethod : testStateMethod;
            if (method == null) {
                method = findMethod(query.getClass(), methodName, 3);
                if (method == null) {
                    reportQueryFailure(methodName + " not found on " + query.getClass().getName());
                    return true;
                }
                if (build) {
                    testBuildMethod = method;
                } else {
                    testStateMethod = method;
                }
            }
            Class<?> flagType = method.getParameterTypes()[2].getComponentType();
            Object flagArray = Array.newInstance(flagType, countNonNull(flags));
            int index = 0;
            for (Object flag : flags) {
                if (flag != null) {
                    Array.set(flagArray, index++, flag);
                }
            }
            Object result = method.invoke(query, adaptedLocation, localPlayer, flagArray);
            return !(result instanceof Boolean allowed) || allowed;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reportQueryFailure(methodName + ": " + e);
            return true;
        }
    }

    private static int countNonNull(Object[] values) {
        int count = 0;
        for (Object value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    // Reported once per server run: warning on every interaction would be worse than the silence it
    // replaces, but a permanently fail-open protection layer must not stay invisible.
    private static void reportQueryFailure(String detail) {
        if (queryFailureReported) {
            return;
        }
        queryFailureReported = true;
        I18n.logWarning("worldguard_query_failed", "error", detail);
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

    // WorldEdit's BukkitAdapter.adapt(Player) returns a WorldEdit BukkitPlayer, which is neither
    // LocalPlayer nor RegionAssociable, so RegionQuery rejects it during argument validation. The
    // WorldGuard-side wrapper returns com.sk89q.worldguard.LocalPlayer, which both overloads accept.
    private static Object wrapPlayer(Player player) throws ReflectiveOperationException {
        Class<?> pluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
        Method method = wrapPlayerMethod;
        if (method == null) {
            method = pluginClass.getMethod("wrapPlayer", Player.class);
            wrapPlayerMethod = method;
        }
        Object instance = pluginClass.getMethod("inst").invoke(null);
        return instance == null ? null : method.invoke(instance, player);
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
