package com.huidu.farmersdelight.visual;

import net.momirealms.craftengine.core.entity.culling.Cullable;
import net.momirealms.craftengine.core.entity.culling.CullableHolder;
import net.momirealms.craftengine.core.entity.culling.CullingData;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.world.collision.AABB;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ProxyDisplayCullableTest {
    private static final int ID = 42;

    @Test
    void nativeRegistrationWaitsForCeAndDoesNotRegisterTwice() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        assertTrue(display.addViewer(client.player, true));
        assertFalse(display.addViewer(client.player, true));
        assertSame(client.cullable, client.tracked.get(ID).cullable);
        assertSame(display.cullingData(), client.cullable.cullingData());
        assertTrue(client.packets.isEmpty());
        client.cullable.show(client.player);
        client.cullable.show(client.player);
        assertEquals(List.of("spawn", "meta-1", "range"), client.packets);
    }

    @Test
    void oldCeHideThenShowUsesLatestSnapshot() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(client.player, true);
        client.cullable.show(client.player);
        client.cullable.hide(client.player);
        snapshot(display, 2);
        display.sendUpdate(client.id, false);
        assertEquals(List.of("spawn", "meta-1", "range", "destroy"), client.packets);
        client.cullable.show(client.player);
        assertEquals(List.of("spawn", "meta-2", "range"), client.packets.subList(4, 7));
        assertSame(client.cullable, client.tracked.get(ID).cullable);
        assertSame(display.cullingData(), client.cullable.cullingData());
    }

    @Test
    void retainedCullDefersUpdatesAndRestoresLatestPositionWithoutRespawn() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(client.player, true);
        client.cullable.show(client.player);
        assertTrue(client.cull(client.player));
        snapshot(display, 2);
        display.sendUpdate(client.id, true);
        snapshot(display, 3);
        display.sendUpdate(client.id, false);
        assertEquals(List.of("spawn", "meta-1", "range", "hidden"), client.packets);
        client.restore(client.player);
        assertEquals(List.of("pos-3", "meta-3", "range"), client.packets.subList(4, 7));
        display.sendUpdate(client.id, false);
        assertEquals("meta-3", client.packets.getLast());
    }

    @Test
    void staleCeRegistrationCannotHideOrRestoreItsReplacement() throws Exception {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(client.player, true);
        Cullable stale = client.cullable;
        stale.show(client.player);
        display.removeViewer(client.id);
        display.addViewer(client.player, true);
        client.cullable.show(client.player);
        int sent = client.packets.size();
        stale.hide(client.player);
        stale.show(client.player);
        stale.getClass().getMethod("cull", Player.class).invoke(stale, client.player);
        stale.getClass().getMethod("restore", Player.class).invoke(stale, client.player);
        assertEquals(sent, client.packets.size());
        display.sendUpdate(client.id, false);
        assertEquals("meta-1", client.packets.getLast());
    }

    @Test
    void delayedCeRespawnTrackerClearIsRepairedByTheNextSync() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(client.player, true);
        client.cullable.show(client.player);
        client.cull(client.player);
        client.tracked.clear();
        assertTrue(display.addViewer(client.player, true));
        assertSame(client.cullable, client.tracked.get(ID).cullable);
        assertSame(display.cullingData(), client.cullable.cullingData());
        assertEquals("destroy", client.packets.getLast());
        client.cullable.show(client.player);
        assertEquals(List.of("spawn", "meta-1", "range"),
                client.packets.subList(client.packets.size() - 3, client.packets.size()));
    }

    @Test
    void registrationNeverOverwritesAnotherCeTracker() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        CullableHolder foreign = new CullableHolder(foreign());
        client.tracked.put(ID, foreign);
        assertFalse(display.addViewer(client.player, true));
        assertSame(foreign, client.tracked.get(ID));
        assertTrue(display.viewerIds().isEmpty());
        assertTrue(client.packets.isEmpty());
    }

    @Test
    void retainedDisplayWithoutUpdatesRestoresOnlyViewRange() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(client.player, true);
        client.cullable.show(client.player);
        client.cull(client.player);
        client.restore(client.player);
        assertEquals(List.of("spawn", "meta-1", "range", "hidden", "range"), client.packets);
    }

    @Test
    void fallbackShowsImmediatelyAndNeverRegistersWithCe() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        assertTrue(display.addViewer(client.player, false));
        assertTrue(client.tracked.isEmpty());
        assertEquals(List.of("spawn", "meta-1", "range"), client.packets);
        display.removeViewer(client.id);
        assertEquals("destroy", client.packets.getLast());
        assertTrue(display.viewerIds().isEmpty());
    }

    @Test
    void closingRetainedDisplayUnregistersAndRejectsLateCallbacks() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(client.player, true);
        client.cullable.show(client.player);
        client.cull(client.player);
        display.close();
        assertTrue(client.tracked.isEmpty());
        assertTrue(display.viewerIds().isEmpty());
        assertEquals("destroy", client.packets.getLast());
        int sent = client.packets.size();
        client.restore(client.player);
        client.cullable.show(client.player);
        client.cullable.hide(client.player);
        display.close();
        assertFalse(display.addViewer(client.player, true));
        assertEquals(sent, client.packets.size());
    }

    @Test
    void removingOneViewerDoesNotAffectAnotherOrDeleteForeignTracking() {
        Client first = new Client();
        Client second = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(first.player, true);
        display.addViewer(second.player, true);
        first.cullable.show(first.player);
        second.cullable.show(second.player);
        CullableHolder foreign = new CullableHolder(foreign());
        first.tracked.put(ID, foreign);
        display.removeViewer(first.id);
        assertSame(foreign, first.tracked.get(ID));
        assertSame(second.cullable, second.tracked.get(ID).cullable);
        display.sendUpdate(second.id, false);
        assertEquals("meta-1", second.packets.getLast());
    }

    @Test
    void oldConnectionCannotShowAReconnectedPlayersDisplay() {
        Client first = new Client();
        Client reconnected = new Client(first.id);
        ProxyDisplayCullable display = display();
        display.addViewer(first.player, true);
        display.removeViewer(first.id);
        display.addViewer(reconnected.player, true);
        first.cullable.show(first.player);
        first.restore(first.player);
        assertTrue(first.packets.isEmpty());
        assertTrue(reconnected.packets.isEmpty());
        reconnected.cullable.show(reconnected.player);
        assertEquals(3, reconnected.packets.size());
    }

    @Test
    void partialSpawnFailureCleansClientAndAllowsRetry() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(client.player, true);
        client.failPacket = "range";
        client.cullable.show(client.player);
        assertTrue(client.tracked.isEmpty());
        assertEquals(Set.of(client.id), display.viewerIds());
        assertEquals("destroy", client.packets.getLast());
        client.failPacket = null;
        assertTrue(display.addViewer(client.player, true));
        client.cullable.show(client.player);
        assertEquals("range", client.packets.getLast());
    }

    @Test
    void failedFallbackSpawnKeepsCandidateIndexedForRetryAndCleanup() {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        client.failPacket = "range";
        assertTrue(display.addViewer(client.player, false));
        assertEquals(Set.of(client.id), display.viewerIds());
        assertEquals("destroy", client.packets.getLast());
        client.failPacket = null;
        assertFalse(display.addViewer(client.player, false));
        assertEquals("range", client.packets.getLast());
        display.close();
        assertTrue(display.viewerIds().isEmpty());
        assertEquals("destroy", client.packets.getLast());
    }

    @Test
    void concurrentCeCallbackCannotResurrectClosedDisplay() throws Exception {
        Client client = new Client();
        ProxyDisplayCullable display = display();
        display.addViewer(client.player, true);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var show = workers.submit(() -> {
                await(start);
                for (int i = 0; i < 100; i++) client.cullable.show(client.player);
            });
            var close = workers.submit(() -> {
                await(start);
                display.close();
            });
            start.countDown();
            show.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
        }
        assertTrue(client.tracked.isEmpty());
        assertTrue(display.viewerIds().isEmpty());
        assertTrue(client.packets.isEmpty() || "destroy".equals(client.packets.getLast()));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static Cullable foreign() {
        return new Cullable() {
            @Override public void show(Player player) { throw new AssertionError("foreign show"); }
            @Override public void hide(Player player) { throw new AssertionError("foreign hide"); }
            @Override public CullingData cullingData() { return null; }
        };
    }

    private static ProxyDisplayCullable display() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        ProxyDisplayCullable display = new ProxyDisplayCullable(ID, "destroy", "hidden", logger,
                new AtomicLong(), new AtomicLong(), new AtomicLong());
        snapshot(display, 1);
        return display;
    }

    private static void snapshot(ProxyDisplayCullable display, int revision) {
        display.update(List.of("spawn", "meta-" + revision), "pos-" + revision, "meta-" + revision,
                "range", new CullingData(new AABB(0, 0, 0, 1, 1, 1), 64, 0.25, true));
    }

    private static final class Client {
        final UUID id;
        final Player player;
        final List<Object> packets = new ArrayList<>();
        final Map<Integer, CullableHolder> tracked = new HashMap<>();
        Object failPacket;
        Cullable cullable;

        boolean cull(Player player) {
            try {
                return (boolean) cullable.getClass().getMethod("cull", Player.class).invoke(cullable, player);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        void restore(Player player) {
            try {
                cullable.getClass().getMethod("restore", Player.class).invoke(cullable, player);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        Client() {
            this(UUID.randomUUID());
        }

        Client(UUID id) {
            this.id = id;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "uuid" -> id;
                        case "isOnline" -> true;
                        case "getTrackedEntity" -> tracked.get((Integer) args[0]);
                        case "addTrackedEntity" -> {
                            cullable = (Cullable) args[1];
                            tracked.put((Integer) args[0], new CullableHolder(cullable));
                            yield null;
                        }
                        case "removeTrackedEntity" -> { tracked.remove((Integer) args[0]); yield null; }
                        case "sendPacket" -> {
                            if (args[0].equals(failPacket)) throw new IllegalStateException("network failure");
                            packets.add(args[0]);
                            yield null;
                        }
                        case "sendPackets" -> { packets.addAll((List<?>) args[0]); yield null; }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> id.toString();
                        default -> throw new AssertionError("Unexpected CE/Bukkit access: " + method);
                    });
        }
    }
}
