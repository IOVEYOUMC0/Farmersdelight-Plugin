package com.huidu.farmersdelight.util;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The viewer distance gate that runs per viewer from the viewer's own scheduler. */
class ManagerSupportTest {

    @Test
    void theGateRequiresTheSameWorldAndComparesSquaredDistance() {
        World world = world();
        World otherWorld = world();

        assertTrue(ManagerSupport.withinViewerRange(player(world, 10, 64, 10), world, 10.5, 64.0, 10.5, 1.0));
        assertFalse(ManagerSupport.withinViewerRange(player(otherWorld, 10, 64, 10), world, 10, 64, 10, 1.0));
        assertFalse(ManagerSupport.withinViewerRange(player(world, 10, 64, 10), world, 12, 64, 10, 1.0));
    }

    @Test
    void theGateIncludesTheExactRadius() {
        World world = world();

        assertTrue(ManagerSupport.withinViewerRange(player(world, 3, 4, 0), world, 0, 0, 0, 25.0));
        assertFalse(ManagerSupport.withinViewerRange(player(world, 3, 4, 0), world, 0, 0, 0, 24.0));
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Player player(World world, double x, double y, double z) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        return null;
    }
}
