package com.huidu.farmersdelight.util;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The viewer tracker must count per holder and never leak a holder when the player quits. */
class InventoryViewerTrackerTest {

    @AfterEach
    void clear() {
        InventoryViewerTracker.clear();
    }

    @Test
    void openAndCloseMoveTheHolderCounter() {
        InventoryHolder holder = holder();
        HumanEntity player = player();

        InventoryViewerTracker.trackOpen(player, inventory(holder));
        assertTrue(InventoryViewerTracker.hasViewer(holder));

        InventoryViewerTracker.trackClose(player, inventory(holder));
        assertFalse(InventoryViewerTracker.hasViewer(holder));
    }

    @Test
    void quittingReleasesEveryHolderThePlayerLeftOpen() {
        InventoryHolder first = holder();
        InventoryHolder second = holder();
        HumanEntity player = player();
        InventoryViewerTracker.trackOpen(player, inventory(first));
        InventoryViewerTracker.trackOpen(player, inventory(second));

        InventoryViewerTracker.release(player.getUniqueId());

        assertFalse(InventoryViewerTracker.hasViewer(first));
        assertFalse(InventoryViewerTracker.hasViewer(second));
    }

    @Test
    void aLateCloseAfterQuitDoesNotStealAnotherPlayersViewer() {
        InventoryHolder holder = holder();
        HumanEntity quitting = player();
        HumanEntity other = player();
        InventoryViewerTracker.trackOpen(quitting, inventory(holder));
        InventoryViewerTracker.trackOpen(other, inventory(holder));
        assertTrue(InventoryViewerTracker.hasViewer(holder));

        InventoryViewerTracker.release(quitting.getUniqueId());
        InventoryViewerTracker.trackClose(quitting, inventory(holder));

        assertTrue(InventoryViewerTracker.hasViewer(holder), "the other player still has the container open");
        InventoryViewerTracker.trackClose(other, inventory(holder));
        assertFalse(InventoryViewerTracker.hasViewer(holder));
    }

    @Test
    void openingTheSameHolderTwiceNeedsTwoCloses() {
        InventoryHolder holder = holder();
        HumanEntity player = player();
        InventoryViewerTracker.trackOpen(player, inventory(holder));
        InventoryViewerTracker.trackOpen(player, inventory(holder));

        InventoryViewerTracker.trackClose(player, inventory(holder));
        assertTrue(InventoryViewerTracker.hasViewer(holder), "one nested open is still active");

        InventoryViewerTracker.trackClose(player, inventory(holder));
        assertFalse(InventoryViewerTracker.hasViewer(holder));
    }

    @Test
    void concurrentOpenCloseDoesNotCorruptTheCounter() throws InterruptedException {
        InventoryHolder holder = holder();
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    HumanEntity player = player();
                    InventoryViewerTracker.trackOpen(player, inventory(holder));
                    InventoryViewerTracker.trackClose(player, inventory(holder));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(InventoryViewerTracker.hasViewer(holder));
    }

    private static InventoryHolder holder() {
        return (InventoryHolder) Proxy.newProxyInstance(
                InventoryHolder.class.getClassLoader(), new Class<?>[]{InventoryHolder.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Inventory inventory(InventoryHolder holder) {
        return (Inventory) Proxy.newProxyInstance(
                Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHolder" -> holder;
                    default -> null;
                });
    }

    private static HumanEntity player() {
        UUID id = UUID.randomUUID();
        return (HumanEntity) Proxy.newProxyInstance(
                HumanEntity.class.getClassLoader(), new Class<?>[]{HumanEntity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    default -> null;
                });
    }
}
