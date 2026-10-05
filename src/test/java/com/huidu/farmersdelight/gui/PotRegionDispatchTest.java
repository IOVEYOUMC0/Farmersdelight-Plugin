package com.huidu.farmersdelight.gui;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The region decision behind the cooking pot GUI's store writes: the pot's own region runs them inline, any
 * other region gets exactly one dispatched task aimed at the pot's location, and a dispatch that cannot be
 * queued (or a world that cannot be resolved) still performs the write inline — a close() flush must never be
 * dropped.
 *
 *
 * No server is involved: the seam takes the ownership query and the dispatcher as arguments.
 */
class PotRegionDispatchTest {

    // Held strongly on purpose: Bukkit's Location keeps its world in a WeakReference and getWorld() throws
    // "World unloaded" once that referent has been collected.
    private static final World WORLD = world();

    @Test
    void theOwningRegionWritesInlineWithoutDispatching() {
        List<Location> dispatched = new ArrayList<>();
        PotRegionDispatch region = new PotRegionDispatch(null, location -> true, (location, task) -> {
            dispatched.add(location);
            task.run();
        });
        int[] storeWrites = {0};

        region.runAt(new Location(WORLD, 4, 64, 4), () -> storeWrites[0]++);

        assertEquals(1, storeWrites[0], "the same region keeps the existing inline path");
        assertEquals(List.of(), dispatched, "an inline write must not queue a task");
    }

    @Test
    void anotherRegionDispatchesExactlyOnceToThePotLocation() {
        List<Location> dispatched = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        PotRegionDispatch region = new PotRegionDispatch(null, location -> false, (location, task) -> {
            dispatched.add(location);
            tasks.add(task);
        });
        Location pot = new Location(WORLD, 12, 70, -8);
        int[] storeWrites = {0};

        region.runAt(pot, () -> storeWrites[0]++);

        assertEquals(0, storeWrites[0], "the store must not be written on the viewer's region");
        assertEquals(1, dispatched.size(), "exactly one dispatch");
        assertSame(pot, dispatched.getFirst(), "dispatched at the pot's own location");

        tasks.getFirst().run();
        assertEquals(1, storeWrites[0], "the write happens on the pot's region");
    }

    @Test
    void aDispatchThatCannotBeQueuedStillWritesInline() {
        List<Location> dispatched = new ArrayList<>();
        PotRegionDispatch region = new PotRegionDispatch(null, location -> false, (location, task) -> {
            dispatched.add(location);
            throw new IllegalStateException("Plugin attempted to register task while disabled");
        });
        int[] storeWrites = {0};

        region.runAt(new Location(WORLD, 1, 65, 1), () -> storeWrites[0]++);

        assertEquals(1, dispatched.size(), "the dispatch was attempted");
        assertEquals(1, storeWrites[0], "a refused dispatch must not drop the close() flush");
    }

    @Test
    void aLocationWithoutAWorldIsNotOwnedAndWritesInline() {
        List<Location> dispatched = new ArrayList<>();
        PotRegionDispatch region = new PotRegionDispatch(null, location -> false, (location, task) -> {
            dispatched.add(location);
            task.run();
        });
        int[] storeWrites = {0};

        assertFalse(region.isOwned(null));
        region.runAt(null, () -> storeWrites[0]++);

        assertFalse(region.isOwned(new Location(null, 0, 0, 0)));
        region.runAt(new Location(null, 0, 0, 0), () -> storeWrites[0]++);

        assertEquals(2, storeWrites[0], "both fall back to inline");
        assertEquals(List.of(), dispatched, "a location with no world cannot be dispatched");
    }

    @Test
    void anUnloadedWorldFallsBackToInlineWithoutClaimingOwnership() {
        List<Location> dispatched = new ArrayList<>();
        PotRegionDispatch region = new PotRegionDispatch(null, location -> false, (location, task) -> {
            dispatched.add(location);
            task.run();
        });
        Location unloaded = new Location(null, 0, 0, 0) {
            @Override
            public World getWorld() {
                throw new IllegalArgumentException("World unloaded");
            }
        };
        int[] storeWrites = {0};

        assertFalse(region.isOwned(unloaded), "an unusable world is never claimed as the current region");
        region.runAt(unloaded, () -> storeWrites[0]++);

        assertEquals(1, storeWrites[0], "the fallback still performs the write");
        assertEquals(List.of(), dispatched);
    }

    @Test
    void anOwnedLocationIsRecognisedThroughTheWorld() {
        PotRegionDispatch region = new PotRegionDispatch(null, location -> true, (location, task) -> {
        });

        assertTrue(region.isOwned(new Location(WORLD, 0, 0, 0)));
        assertFalse(new PotRegionDispatch(null, location -> false, (location, task) -> {
        }).isOwned(new Location(WORLD, 0, 0, 0)));
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "test world";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected World call: " + method.getName());
                });
    }
}
