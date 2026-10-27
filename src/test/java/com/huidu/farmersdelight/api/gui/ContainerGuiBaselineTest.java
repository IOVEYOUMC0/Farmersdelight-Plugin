package com.huidu.farmersdelight.api.gui;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContainerGuiBaselineTest {
    @Test
    void anotherViewCannotAdvanceThisViewsBaseline() throws Exception {
        withStacks(() -> {
            Store store = new Store(4);
            ContainerGuiKernel kernel = store.kernel();
            View first = new View();
            View second = new View();
            kernel.fill(first.inventory);
            store.item = new Stack(2);
            kernel.fill(second.inventory);
            first.item = null;
            kernel.syncSlot(first.inventory, 0);
            assertEquals(2, store.item.getAmount(), "a stale first view must not clear the second view's stack");
            assertEquals(2, first.item.getAmount());
        });
    }

    @Test
    void mutableStoreStacksCannotChangeAnAlreadyPaintedBaseline() throws Exception {
        withStacks(() -> {
            Store store = new Store(4);
            ContainerGuiKernel kernel = store.kernel();
            View view = new View();
            kernel.fill(view.inventory);
            store.item.setAmount(2);
            view.item = null;
            kernel.syncSlot(view.inventory, 0);
            assertEquals(2, store.item.getAmount());
            assertEquals(2, view.item.getAmount());
        });
    }

    @Test
    void repeatedSuccessfulEditsAdvanceTheSameViewsBaseline() throws Exception {
        withStacks(() -> {
            Store store = new Store(4);
            ContainerGuiKernel kernel = store.kernel();
            View view = new View();
            kernel.fill(view.inventory);
            view.item = new Stack(3);
            kernel.syncSlot(view.inventory, 0);
            view.item = new Stack(2);
            kernel.syncSlot(view.inventory, 0);
            assertEquals(2, store.item.getAmount());
        });
    }

    @Test
    void snapshotRepaintingUsesThePersistentKernelsBaseline() throws Exception {
        withStacks(() -> {
            Store store = new Store(1);
            ContainerGuiKernel kernel = store.kernel();
            View view = new View();
            kernel.repaintFrom(view.inventory, new int[]{0}, index -> store.item.clone());
            view.item = null;
            kernel.syncSlot(view.inventory, 0);
            assertNull(store.item);
        });
    }

    private static void withStacks(Runnable test) throws Exception {
        Field blockType = Material.class.getDeclaredField("blockType");
        blockType.setAccessible(true);
        Object original = blockType.get(Material.STONE);
        try {
            blockType.set(Material.STONE, (Supplier<Object>) () -> null);
            test.run();
        } finally {
            blockType.set(Material.STONE, original);
        }
    }

    private static final class Stack extends ItemStack {
        private int count;
        Stack(int count) { this.count = count; }
        public Material getType() { return Material.STONE; }
        public int getAmount() { return count; }
        public void setAmount(int count) { this.count = count; }
        public Stack clone() { return new Stack(count); }
        public boolean isSimilar(ItemStack other) { return other instanceof Stack; }
    }

    private static final class View {
        /** The one-row layout this fixture serves; the kernel asks the view for its size when it paints. */
        private static final int SIZE = 9;
        private ItemStack item;
        private final Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[]{Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> item;
                    case "setItem" -> { item = (ItemStack) args[1]; yield null; }
                    case "getSize" -> SIZE;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                });
    }

    private static final class Store implements ContainerGuiKernel.Controller {
        private ItemStack item;
        private final Object lock = new Object();
        Store(int count) { item = new Stack(count); }
        ContainerGuiKernel kernel() { return new ContainerGuiKernel(this, new GuiSlotGroup("slot", 0, 1, null)); }
        public GuiLayout layout() {
            return new GuiLayout() {
                public int rows() { return 1; }
                public boolean isFunctional(int slot) { return slot == 0; }
                public int[] slotsOf(String type) { return new int[]{0}; }
            };
        }
        public int slotCount() { return 1; }
        public ItemStack stored(int slot) { return item; }
        public void store(int slot, ItemStack value) { item = value == null ? null : value.clone(); }
        public Object storeLock() { return lock; }
        public void markDirty() { }
    }
}
