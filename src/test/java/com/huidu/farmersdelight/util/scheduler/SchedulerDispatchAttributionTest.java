package com.huidu.farmersdelight.util.scheduler;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The location-dispatch gate: a location whose world cannot be resolved is refused instead of being handed to
 * the global scheduler, while a location in a real world still dispatches to that world's region.
 */
class SchedulerDispatchAttributionTest {

    @Test
    void aMissingLocationIsNotAttributable() {
        assertNull(SchedulerAdapter.attributableWorld(null));
    }

    @Test
    void aLocationWithoutAWorldIsNotAttributable() {
        // A Location may be built with no world at all; there is no region such a dispatch could belong to.
        assertNull(SchedulerAdapter.attributableWorld(new Location(null, 8, 64, -8)));
    }

    @Test
    void aLocationInAWorldIsAttributedToThatWorld() {
        World world = world();
        assertSame(world, SchedulerAdapter.attributableWorld(new Location(world, 8, 64, -8)));
    }

    @Test
    void aLocationWhoseWorldWasCollectedIsNotAttributable() throws ReflectiveOperationException {
        // Location keeps its world behind a weak reference and getWorld() throws once that reference was
        // collected after an unload; the gate has to answer "no owner" instead of letting the throw escape.
        Location location = new Location(world(), 0, 0, 0);
        Field worldReference = Location.class.getDeclaredField("world");
        worldReference.setAccessible(true);
        worldReference.set(location, new WeakReference<World>(null));

        assertNull(SchedulerAdapter.attributableWorld(location));
    }

    @Test
    void noLocationDispatchFallsBackToTheGlobalScheduler() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/util/scheduler/SchedulerAdapter.java");
        assertEquals(2, occurrences(source, "attributableWorld(location)"),
                "both location entry points have to decide the world before dispatching");
        assertTrue(source.contains("refusedUnattributable(\"runAt\")"));
        assertTrue(source.contains("refusedUnattributable(\"runLaterAt\")"));
        assertTrue(source.contains("refusedUnattributable(\"tryRunAt\")"));
        assertFalse(source.contains("if (!folia || world == null)"),
                "a missing world must be refused, never handed to the global scheduler");
    }

    /** A world handle for location construction; nothing on it is called, so no server is needed. */
    private static World world() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == short.class) return (short) 0;
                    if (type == byte.class) return (byte) 0;
                    if (type == float.class) return 0F;
                    if (type == double.class) return 0D;
                    if (type == char.class) return (char) 0;
                    return null;
                });
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
