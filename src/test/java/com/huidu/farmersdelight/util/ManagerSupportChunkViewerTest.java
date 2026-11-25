package com.huidu.farmersdelight.util;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Chunk-viewer collection must not touch player coordinates; distance is checked later on the viewer's thread. */
class ManagerSupportChunkViewerTest {

    @Test
    void collectsTrackedViewersWithoutReadingTheirCoordinates() {
        Player viewer = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    throw new AssertionError("viewer coordinates must not be read while collecting: " + method.getName());
                });
        Chunk chunk = (Chunk) Proxy.newProxyInstance(
                Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class},
                (proxy, method, args) -> "getPlayersSeeingChunk".equals(method.getName()) ? List.of(viewer) : null);
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isChunkLoaded" -> true;
                    case "getChunkAt" -> chunk;
                    default -> null;
                });
        List<Player> out = new ArrayList<>();

        List<Player> result = ManagerSupport.playersSeeingChunk(world, new Location(world, 3, 64, -17), out);

        assertSame(out, result);
        assertEquals(List.of(viewer), result);
    }
}
