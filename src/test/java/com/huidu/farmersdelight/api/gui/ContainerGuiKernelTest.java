package com.huidu.farmersdelight.api.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine against a fake controller and a fake inventory: which cell maps to which store slot, what is
 * painted where, that a refused write-back repaints the cell instead of keeping the GUI value, and that every
 * store access happens under the controller's lock.
 *
 *
 * Item stacks cannot be built without a running server, so the fake store holds empty cells: what is asserted
 * is the decision and the call sequence, which is where the duplication and loss bugs live. The value
 * comparison itself is covered by {@link GuiWriteBackTest}, and so is the item-level half of the engine
 * (cloning a stored value, painting a placeholder icon) — that needs a server.
 */
class ContainerGuiKernelTest {

    private static final GuiSlotGroup[] GROUPS = {
            new GuiSlotGroup("bait", 0, 1, "bait"),
            new GuiSlotGroup("output", 1, 9, null)
    };

    @Test
    void aConfiguredCellMapsToItsStoreSlotAndEverythingElseToMinusOne() {
        ContainerGuiKernel kernel = new ContainerGuiKernel(new FakeController(layout()), GROUPS);

        assertEquals(0, kernel.storeIndex(3), "the bait cell");
        assertEquals(1, kernel.storeIndex(9), "the first catch");
        assertEquals(9, kernel.storeIndex(17), "the last catch");
        assertEquals(-1, kernel.storeIndex(0), "a backdrop cell");
        assertEquals(-1, kernel.storeIndex(18), "outside the layout");
        assertEquals(-1, kernel.storeIndex(-1));
    }

    @Test
    void withoutALayoutNothingIsPaintedAndNothingIsSynced() {
        ContainerGuiKernel kernel = new ContainerGuiKernel(new FakeController(null), GROUPS);
        FakeView view = new FakeView(18);

        assertFalse(kernel.hasLayout());
        assertFalse(kernel.fill(view.inventory));
        assertFalse(kernel.syncAll(view.inventory));
        assertFalse(kernel.refreshAll(view.inventory));
        assertFalse(kernel.refreshSlot(view.inventory, 3));
        assertFalse(kernel.syncSlot(view.inventory, 3));
        assertEquals(-1, kernel.storeIndex(3));
        assertEquals(List.of(), view.calls);
    }

    @Test
    void fillPaintsTheBackdropOnUnusedCellsAndEveryGroupCell() {
        FakeController controller = new FakeController(layout());
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        assertTrue(kernel.fill(view.inventory));

        assertEquals(18, view.calls.size(), "every cell of the two-row grid is painted once");
        assertEquals(8, controller.backdropCalls, "the eight cells the layout does not use");
        assertEquals(Arrays.asList("bait", null, null, null, null, null, null, null, null, null),
                controller.iconRequests, "one placeholder lookup per group cell, in group order");
        assertTrue(controller.storeLockCalls > 0, "the engine must take the controller's lock");
        assertEquals(10, controller.storedReads.size(), "one store read per group cell");
    }

    @Test
    void refreshAllPaintsEveryGroupCellExceptTheSkippedOnes() {
        ContainerGuiKernel kernel = new ContainerGuiKernel(new FakeController(layout()), GROUPS);
        FakeView view = new FakeView(18);

        assertTrue(kernel.refreshAll(view.inventory, rawSlot -> rawSlot >= 9));

        assertEquals(List.of(3), view.painted(), "the bait cell is refreshed, the pending catches are not");
    }

    @Test
    void refreshSlotLeavesBackdropCellsAlone() {
        ContainerGuiKernel kernel = new ContainerGuiKernel(new FakeController(layout()), GROUPS);
        FakeView view = new FakeView(18);

        assertTrue(kernel.refreshSlot(view.inventory, 3));
        assertEquals(List.of(3), view.painted());
        assertFalse(kernel.refreshSlot(view.inventory, 0), "a backdrop cell has no store slot");
        assertEquals(List.of(3), view.painted());
    }

    @Test
    void syncAllCommitsEveryCellUnderTheLockAndMarksTheStationDirtyOnce() {
        FakeController controller = new FakeController(layout());
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        assertTrue(kernel.syncAll(view.inventory));

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), controller.committed);
        assertEquals(1, controller.markDirtyCalls);
        assertTrue(controller.storeLockHeld, "store() must run under storeLock()");
        assertEquals(List.of(), view.painted(), "every commit was allowed, so nothing was repainted");
    }

    @Test
    void aCommitTheGuardAllowsIsStoredAndMarksDirty() {
        FakeController controller = new FakeController(layout());
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        assertTrue(kernel.syncSlot(view.inventory, 3));

        assertEquals(List.of(0), controller.committed);
        assertEquals(1, controller.markDirtyCalls);
        assertEquals(List.of(), view.painted(), "an allowed commit does not repaint");
    }

    @Test
    void aCommitTheGuardRefusesRepaintsTheCellAndStoresNothing() {
        FakeController controller = new FakeController(layout());
        controller.refuseAll = true;
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        assertTrue(kernel.syncSlot(view.inventory, 3));

        assertEquals(List.of(), controller.committed, "the refusal must not reach the store");
        assertEquals(0, controller.markDirtyCalls, "a repaint changes nothing to save");
        assertEquals(List.of(3), view.painted(), "the cell is adopted from the store instead");
    }

    @Test
    void aRefusedCellInsideSyncAllIsRepaintedWhileTheOtherCellsCommit() {
        FakeController controller = new FakeController(layout());
        controller.refusedStoreIndices.add(1);
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        assertTrue(kernel.syncAll(view.inventory));

        assertFalse(controller.committed.contains(1), "the refused cell is not committed");
        assertEquals(List.of(0, 2, 3, 4, 5, 6, 7, 8, 9), controller.committed);
        assertEquals(List.of(9), view.painted(), "the refused cell is painted from the store");
        assertEquals(1, controller.markDirtyCalls);
    }

    @Test
    void syncSlotIgnoresASlotThatHoldsNoContents() {
        FakeController controller = new FakeController(layout());
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        assertTrue(kernel.syncSlot(view.inventory, 0), "a backdrop cell is a no-op, not a failure");
        assertEquals(List.of(), controller.committed);
        assertEquals(List.of(), view.painted());
        assertEquals(List.of(), view.calls);
    }

    @Test
    void commitOnlyWritesTheStoreWithoutRepaintingAndReportsTheRefusedCells() {
        FakeController controller = new FakeController(layout());
        controller.refusedStoreIndices.add(1);
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        int[] refused = kernel.commitOnly(view.inventory);

        assertArrayEquals(new int[]{9}, refused, "the refused cell is reported by raw slot");
        assertFalse(controller.committed.contains(1), "the refused cell is not committed");
        assertEquals(List.of(0, 2, 3, 4, 5, 6, 7, 8, 9), controller.committed);
        assertEquals(List.of(), view.calls, "commitOnly must never write the viewer's inventory");
        assertEquals(1, controller.markDirtyCalls);
    }

    @Test
    void commitOnlyReportsEveryRefusedCellInSlotOrder() {
        FakeController controller = new FakeController(layout());
        controller.refuseAll = true;
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        int[] refused = kernel.commitOnly(view.inventory);

        assertArrayEquals(new int[]{3, 9, 10, 11, 12, 13, 14, 15, 16, 17}, refused);
        assertEquals(List.of(), controller.committed);
        assertEquals(List.of(), view.calls, "a refusal is not repainted by commitOnly");
    }

    @Test
    void commitOnlyOfOneCellReportsTheRefusalWithoutWritingAnything() {
        FakeController controller = new FakeController(layout());
        controller.refuseAll = true;
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        assertFalse(kernel.commitOnly(view.inventory, 3));
        assertEquals(List.of(), controller.committed);
        assertEquals(List.of(), view.calls);
        assertEquals(0, controller.markDirtyCalls, "a refusal changes nothing to save");
    }

    @Test
    void repaintFromPaintsExactlyTheGivenCellsFromTheSuppliedValues() {
        FakeController controller = new FakeController(layout());
        ContainerGuiKernel kernel = new ContainerGuiKernel(controller, GROUPS);
        FakeView view = new FakeView(18);

        kernel.repaintFrom(view.inventory, new int[]{3, 9}, index -> null);

        assertEquals(List.of(3, 9), view.painted(), "only the listed cells are painted");
        assertEquals(List.of(), controller.storedReads, "the store is not read: the values came from the caller");
        assertEquals(List.of(), controller.committed, "the store is not written either");
        assertEquals(0, controller.markDirtyCalls);
    }

    @Test
    void theCommitOnlyEntryPointsReportNothingWithoutALayout() {
        ContainerGuiKernel kernel = new ContainerGuiKernel(new FakeController(null), GROUPS);
        FakeView view = new FakeView(18);

        assertArrayEquals(new int[0], kernel.commitOnly(view.inventory));
        assertFalse(kernel.commitOnly(view.inventory, 3));
        assertEquals(List.of(), view.calls);
    }

    private static GuiLayout layout() {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString("""
                    crab-trap-gui:
                      rows: 2
                      layout:
                      - "XXXBXXXXX"
                      - "OOOOOOOOO"
                      legend:
                        B: bait
                        O: output
                        X: background
                    """);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        ConfigurationSection section = configuration.getConfigurationSection("crab-trap-gui");
        GuiLayout layout = GuiLayouts.parse(section);
        if (layout == null) {
            throw new AssertionError("fixture layout did not parse");
        }
        return layout;
    }

    /** A controller whose store is all-empty, with the guard, the lock and the paint requests instrumented. */
    private static final class FakeController implements ContainerGuiKernel.Controller {

        private final GuiLayout layout;
        private final Object lock = new Object();
        private final List<Integer> committed = new ArrayList<>();
        private final List<Integer> storedReads = new ArrayList<>();
        private final List<String> iconRequests = new ArrayList<>();
        private final List<Integer> refusedStoreIndices = new ArrayList<>();
        private boolean refuseAll;
        private int backdropCalls;
        private int markDirtyCalls;
        private int storeLockCalls;
        private boolean storeLockHeld;

        private FakeController(GuiLayout layout) {
            this.layout = layout;
        }

        @Override
        public GuiLayout layout() {
            return layout;
        }

        @Override
        public int slotCount() {
            return 10;
        }

        @Override
        public ItemStack stored(int storeIndex) {
            assertTrue(Thread.holdsLock(lock), "stored() must be read under storeLock()");
            storeLockHeld = true;
            storedReads.add(storeIndex);
            return null;
        }

        @Override
        public void store(int storeIndex, ItemStack item) {
            assertTrue(Thread.holdsLock(lock), "store() must be written under storeLock()");
            storeLockHeld = true;
            committed.add(storeIndex);
        }

        @Override
        public Object storeLock() {
            storeLockCalls++;
            return lock;
        }

        @Override
        public void markDirty() {
            markDirtyCalls++;
        }

        @Override
        public ItemStack icon(String iconType) {
            iconRequests.add(iconType);
            return null;
        }

        @Override
        public ItemStack backdrop() {
            backdropCalls++;
            return null;
        }

        @Override
        public boolean mayCommit(ItemStack stored, ItemStack paintedBaseline) {
            if (refuseAll) {
                return false;
            }
            return !refusedStoreIndices.contains(lastStoredRead());
        }

        private int lastStoredRead() {
            return storedReads.isEmpty() ? -1 : storedReads.get(storedReads.size() - 1);
        }
    }

    /** An inventory backed by a slot map, recording every write. */
    private static final class FakeView {

        private final Inventory inventory;
        private final Map<Integer, ItemStack> cells = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final List<Integer> written = new ArrayList<>();

        private FakeView(int size) {
            this.inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                    new Class<?>[]{Inventory.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getSize":
                                return size;
                            case "getItem":
                                return cells.get((Integer) args[0]);
                            case "setItem": {
                                int slot = (Integer) args[0];
                                cells.put(slot, (ItemStack) args[1]);
                                written.add(slot);
                                calls.add("setItem(" + slot + ")");
                                return null;
                            }
                            case "toString":
                                return "fake view";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                throw new AssertionError("unexpected Inventory call: " + method.getName());
                        }
                    });
        }

        private List<Integer> painted() {
            return List.copyOf(written);
        }
    }
}
