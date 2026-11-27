package com.huidu.farmersdelight.api.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Overflow delivery must report a refused spawn instead of assuming the drop worked. */
class ItemDeliveryTest {

    private Field serverField;
    private Object previousServer;

    @BeforeEach
    void installServerStub() throws ReflectiveOperationException {
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        Server server = proxy(Server.class, (instance, method, args) -> {
            if (method.getName().equals("getLogger")) {
                return Logger.getLogger("ItemDeliveryTest");
            }
            throw new AssertionError(method.getName());
        });
        serverField.set(null, server);
    }

    @AfterEach
    void restoreServer() throws ReflectiveOperationException {
        serverField.set(null, previousServer);
    }

    @Test
    void anInvalidSpawnIsReportedAsFailure() {
        assertFalse(ItemDelivery.dropVerified(world(false, false), location(null), stack()));
    }

    @Test
    void aValidSpawnIsReportedAsSuccess() {
        assertTrue(ItemDelivery.dropVerified(world(true, false), location(null), stack()));
    }

    @Test
    void aThrowAfterInsertionWithAValidEntityStillCountsAsDelivered() {
        assertTrue(ItemDelivery.dropVerified(world(true, true), location(null), stack()));
    }

    @Test
    void aThrowBeforeAnyEntityIsPropagated() {
        assertThrows(IllegalStateException.class,
                () -> ItemDelivery.dropVerified(world(false, true), location(null), stack()));
    }

    @Test
    void aRefusedOverflowDropDoesNotThrow() {
        AtomicInteger drops = new AtomicInteger();
        World world = countingWorld(false, drops);
        Player player = player(world);

        assertDoesNotThrow(() -> ItemDelivery.giveOrDrop(player, location(world), stack()));
        assertTrue(drops.get() == 1);
    }

    @Test
    void aValidOverflowDropRunsOnce() {
        AtomicInteger drops = new AtomicInteger();
        World world = countingWorld(true, drops);
        Player player = player(world);

        ItemDelivery.giveOrDrop(player, location(world), stack());
        assertTrue(drops.get() == 1);
    }

    private static ItemStack stack() {
        return new StubStack();
    }

    private static Location location(World world) {
        return new Location(world, 0, 64, 0);
    }

    @SuppressWarnings("unchecked")
    private static Player player(World world) {
        UUID id = UUID.randomUUID();
        Inventory inventory = proxy(PlayerInventory.class, (proxy, method, args) -> {
            if (method.getName().equals("addItem")) {
                Map<Integer, ItemStack> leftover = new HashMap<>();
                leftover.put(0, new StubStack());
                return leftover;
            }
            throw new AssertionError(method.getName());
        });
        return proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getInventory" -> inventory;
            case "getWorld" -> world;
            case "getLocation" -> location(world);
            case "getName" -> "Probe";
            case "getUniqueId" -> id;
            default -> throw new AssertionError(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static World world(boolean valid, boolean throwAfterCallback) {
        AtomicInteger ignored = new AtomicInteger();
        return countingWorld(valid, ignored, throwAfterCallback);
    }

    @SuppressWarnings("unchecked")
    private static World countingWorld(boolean valid, AtomicInteger drops) {
        return countingWorld(valid, drops, false);
    }

    @SuppressWarnings("unchecked")
    private static World countingWorld(boolean valid, AtomicInteger drops, boolean throwAfterCallback) {
        Item entity = proxy(Item.class, (object, method, args) -> {
            if (method.getName().equals("isValid")) {
                return valid;
            }
            throw new AssertionError(method.getName());
        });
        return proxy(World.class, (object, method, args) -> {
            if (method.getName().equals("getName") || method.getName().equals("toString")) {
                return "world";
            }
            if (!method.getName().equals("dropItemNaturally")) {
                throw new AssertionError(method.getName());
            }
            drops.incrementAndGet();
            ((Consumer<Item>) args[2]).accept(entity);
            if (throwAfterCallback) {
                throw new IllegalStateException("spawn failed after insertion");
            }
            return entity;
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    /** Skips the Bukkit item registry, which a unit test does not have. */
    static final class StubStack extends ItemStack {
        @Override public boolean isEmpty() { return false; }
        @Override public int getAmount() { return 1; }
        @Override public Material getType() { return Material.STONE; }
    }
}
