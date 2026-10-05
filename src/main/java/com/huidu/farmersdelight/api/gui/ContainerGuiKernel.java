package com.huidu.farmersdelight.api.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * The container GUI engine: paints configured cells from the authoritative store, and commits a viewer's
 * edits back only while the cell still holds what it was painted from.
 *
 *
 * A station supplies a Controller (its store, its lock, its placeholder items) and its
 * GuiSlotGroup table; this class owns the parts every container GUI repeats: mapping configured cells
 * to store indices, remembering what each cell was painted from, refusing a stale write-back and repainting
 * the cell from the store instead, and the skip rule for edits still in flight.
 *
 *
 * Threading
 *
 * fill(Inventory), refreshAll(Inventory, IntPredicate) and refreshSlot(Inventory, int)
 * only write the viewer's inventory, so they must be called on the region that owns the viewer. The engine
 * itself never dispatches, never calls a scheduler and never reads a world.
 *
 *
 * syncAll(Inventory) and syncSlot(Inventory, int) read the store and write it back. They run
 * under Controller#storeLock(), which the engine takes for every store access and for the per-cell
 * paint record, so a caller on the viewer's region cannot interleave with a ticker on the store's region. The
 * lock is reentrant: a caller that already holds it (the usual shape, a click handler that wraps its whole
 * read-modify-write) is unaffected.
 *
 *
 * What the controller still owns
 *
 * Cloning and normalising: Controller#store(int, ItemStack) receives whatever the cell held, including
 * a null for a placeholder icon, and is responsible for cloning it and for storing null rather than an empty
 * stack.
 */
public final class ContainerGuiKernel {

    /** What the engine needs from the station: its layout, its store, its lock and its placeholder items. */
    public interface Controller {

        /** The layout, or null when the station has none (every engine method then reports "nothing done"). */
        @Nullable
        GuiLayout layout();

        /** Number of slots in the store. */
        int slotCount();

        /** The authoritative value of one store slot; null and air both mean empty. Must not record anything. */
        @Nullable
        ItemStack stored(int storeIndex);

        /** Writes one store slot: clone what is kept, and store null rather than an empty stack. */
        void store(int storeIndex, @Nullable ItemStack item);

        /** The monitor every store access is taken under. Must be a real object, never null. */
        Object storeLock();

        /** Called when a commit changed the store, so the station can mark its block entity dirty. */
        void markDirty();

        /** The placeholder item for a group's iconType, or null when that group has none. */
        @Nullable
        default ItemStack icon(@Nullable String iconType) {
            return null;
        }

        /** The item painted on cells the layout does not use, or null to leave them empty. */
        @Nullable
        default ItemStack backdrop() {
            return null;
        }

        /** True when the cell holds the placeholder of iconType rather than a real item. */
        default boolean isPlaceholder(ItemStack stack, @Nullable String iconType) {
            return false;
        }

        /**
         * Whether a cell may be committed. Defaults to the shared snapshot guard; override only for a station
         * whose commit policy genuinely differs (a signed delta, for instance). A weaker override is how a
         * container starts duplicating items.
         */
        default boolean mayCommit(@Nullable ItemStack stored, @Nullable ItemStack paintedBaseline) {
            return GuiWriteBack.mayCommit(stored, paintedBaseline);
        }
    }

    private final Controller controller;
    private final GuiSlotGroup[] groups;
    // What each GUI cell was painted from, indexed by raw slot; recorded under the store lock whenever a cell
    // is painted. A cell that was painted empty is indistinguishable from one never painted, and both must
    // refuse a commit, which is what the guard does with a null baseline.
    private ItemStack[] painted = new ItemStack[0];

    public ContainerGuiKernel(Controller controller, GuiSlotGroup... groups) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.groups = groups.clone();
    }

    /** True when the station supplied a layout, i.e. the painting methods have something to paint. */
    public boolean hasLayout() {
        return controller.layout() != null;
    }

    /**
     * Paints the whole layout: the backdrop on every cell the layout does not use, then each group's stored
     * values (a placeholder icon where a cell is empty). False when the station has no layout, so the caller
     * can fall back to a hardcoded grid.
     */
    public boolean fill(Inventory view) {
        Objects.requireNonNull(view, "view");
        GuiLayout layout = controller.layout();
        if (layout == null) {
            return false;
        }
        synchronized (controller.storeLock()) {
            ensurePainted(layout.size());
            for (int rawSlot = 0; rawSlot < layout.size(); rawSlot++) {
                if (!layout.isFunctional(rawSlot)) {
                    ItemStack backdrop = controller.backdrop();
                    view.setItem(rawSlot, backdrop == null ? null : backdrop.clone());
                }
            }
            for (GuiSlotGroup group : groups) {
                int[] cells = layout.slotsOf(group.type());
                for (int offset = 0; offset < cells.length && offset < group.count(); offset++) {
                    paint(view, cells[offset], controller.stored(group.storeIndex(offset)), group.iconType());
                }
            }
        }
        return true;
    }

    /** Refreshes every configured cell from the store; false when the station has no layout. */
    public boolean refreshAll(Inventory view) {
        return refreshAll(view, rawSlot -> false);
    }

    /**
     * Refreshes every configured cell from the store except the cells skipPending accepts. A cell
     * whose edit is still in flight owns the value a player is about to take, so repainting it from the store
     * would show the item twice and hand it out again. False when the station has no layout.
     */
    public boolean refreshAll(Inventory view, IntPredicate skipPending) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(skipPending, "skipPending");
        GuiLayout layout = controller.layout();
        if (layout == null) {
            return false;
        }
        synchronized (controller.storeLock()) {
            ensurePainted(layout.size());
            for (GuiSlotGroup group : groups) {
                int[] cells = layout.slotsOf(group.type());
                for (int offset = 0; offset < cells.length && offset < group.count(); offset++) {
                    int rawSlot = cells[offset];
                    if (skipPending.test(rawSlot)) {
                        continue;
                    }
                    paint(view, rawSlot, controller.stored(group.storeIndex(offset)), group.iconType());
                }
            }
        }
        return true;
    }

    /**
     * Refreshes one configured cell from the store, restoring the placeholder icon for an empty cell. False
     * when the slot is not a configured cell or the station has no layout.
     */
    public boolean refreshSlot(Inventory view, int rawSlot) {
        Objects.requireNonNull(view, "view");
        GuiLayout layout = controller.layout();
        if (layout == null) {
            return false;
        }
        int storeIndex = storeIndex(layout, rawSlot);
        if (storeIndex < 0) {
            return false;
        }
        synchronized (controller.storeLock()) {
            ensurePainted(layout.size());
            paint(view, rawSlot, controller.stored(storeIndex), iconTypeAt(storeIndex));
        }
        return true;
    }

    /**
     * Commits every configured cell the guard allows, and repaints the cells it refuses. Marks the station
     * dirty once afterwards. False when the station has no layout.
     */
    public boolean syncAll(Inventory view) {
        Objects.requireNonNull(view, "view");
        GuiLayout layout = controller.layout();
        if (layout == null) {
            return false;
        }
        synchronized (controller.storeLock()) {
            ensurePainted(layout.size());
            for (GuiSlotGroup group : groups) {
                int[] cells = layout.slotsOf(group.type());
                for (int offset = 0; offset < cells.length && offset < group.count(); offset++) {
                    commit(view, cells[offset], group.storeIndex(offset), group.iconType());
                }
            }
            controller.markDirty();
        }
        return true;
    }

    /**
     * Commits one configured cell, used by the delayed vanilla click and drag synchronisation. A cell the
     * guard refuses is repainted from the store instead; a slot that is not a configured cell is left alone.
     * False only when the station has no layout.
     */
    public boolean syncSlot(Inventory view, int rawSlot) {
        Objects.requireNonNull(view, "view");
        GuiLayout layout = controller.layout();
        if (layout == null) {
            return false;
        }
        int storeIndex = storeIndex(layout, rawSlot);
        if (storeIndex < 0) {
            return true;
        }
        synchronized (controller.storeLock()) {
            ensurePainted(layout.size());
            if (commit(view, rawSlot, storeIndex, iconTypeAt(storeIndex))) {
                controller.markDirty();
            }
        }
        return true;
    }

    /**
     * Commits every configured cell the guard allows, without repainting anything, and returns the raw slots
     * it refused, in the order the groups declare their slots (the caller repaints them per slot, so the order
     * does not matter to it). Marks the station dirty once, like syncAll(Inventory).
     *
     *
     * Region contract: this method writes only the store and the station's dirty flag, and never writes the
     * viewer's inventory. A caller may therefore run it on the region that owns the store and hand the refused
     * slots to the viewer's own region for the repaint (repaintFrom), which is what the
     * cross-region container paths do. A caller already on the viewer's region keeps using
     * syncAll(Inventory), which is this plus the repaint.
     */
    public int[] commitOnly(Inventory view) {
        Objects.requireNonNull(view, "view");
        GuiLayout layout = controller.layout();
        if (layout == null) {
            return new int[0];
        }
        synchronized (controller.storeLock()) {
            ensurePainted(layout.size());
            int[] refused = new int[countConfiguredCells(layout)];
            int count = 0;
            for (GuiSlotGroup group : groups) {
                int[] cells = layout.slotsOf(group.type());
                for (int offset = 0; offset < cells.length && offset < group.count(); offset++) {
                    if (!commitCell(view, cells[offset], group.storeIndex(offset), group.iconType())) {
                        refused[count++] = cells[offset];
                    }
                }
            }
            controller.markDirty();
            return Arrays.copyOf(refused, count);
        }
    }

    /**
     * Commits one configured cell, without repainting it. True when the station's store was written, false
     * when the guard refused; a slot that is not a configured cell returns true and changes nothing, exactly
     * like syncSlot(Inventory, int).
     *
     *
     * Region contract as commitOnly(Inventory): store writes only, never the viewer's inventory.
     */
    public boolean commitOnly(Inventory view, int rawSlot) {
        Objects.requireNonNull(view, "view");
        GuiLayout layout = controller.layout();
        if (layout == null) {
            return false;
        }
        int storeIndex = storeIndex(layout, rawSlot);
        if (storeIndex < 0) {
            return true;
        }
        synchronized (controller.storeLock()) {
            ensurePainted(layout.size());
            if (!commitCell(view, rawSlot, storeIndex, iconTypeAt(storeIndex))) {
                return false;
            }
            controller.markDirty();
            return true;
        }
    }

    /**
     * Repaints the given cells from store values the caller read on the region that owns the store, recording
     * each value as that cell's write-back baseline.
     *
     *
     * Region contract: this method writes the viewer's inventory and the paint record only, and never reads or
     * writes the store, so it must be called on the region that owns the viewer — the half of a cross-region
     * commit that must not happen on the store's region. storedByIndex answers with the snapshot value
     * for a store index.
     */
    public void repaintFrom(Inventory view, int[] rawSlots, IntFunction<ItemStack> storedByIndex) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(rawSlots, "rawSlots");
        Objects.requireNonNull(storedByIndex, "storedByIndex");
        GuiLayout layout = controller.layout();
        if (layout == null) {
            return;
        }
        synchronized (controller.storeLock()) {
            ensurePainted(layout.size());
            for (int rawSlot : rawSlots) {
                int storeIndex = storeIndex(layout, rawSlot);
                if (storeIndex < 0) {
                    continue;
                }
                paint(view, rawSlot, storedByIndex.apply(storeIndex), iconTypeAt(storeIndex));
            }
        }
    }

    /** The store index behind a configured cell, or -1 when the slot holds no contents. */
    public int storeIndex(int rawSlot) {
        GuiLayout layout = controller.layout();
        return layout == null ? -1 : storeIndex(layout, rawSlot);
    }

    /** The number of configured cells, i.e. the upper bound of commitOnly(Inventory)'s result. */
    private int countConfiguredCells(GuiLayout layout) {
        int total = 0;
        for (GuiSlotGroup group : groups) {
            total += Math.min(layout.slotsOf(group.type()).length, group.count());
        }
        return total;
    }

    /**
     * Resolves a cell against the guard, then either commits the viewer's value or repaints the store's.
     * Returns true when the value was committed.
     */
    private boolean commit(Inventory view, int rawSlot, int storeIndex, @Nullable String iconType) {
        ItemStack stored = controller.stored(storeIndex);
        if (!controller.mayCommit(stored, painted[rawSlot])) {
            // The store moved on since this cell was painted: adopt its value, never keep the stale one.
            paint(view, rawSlot, stored, iconType);
            return false;
        }
        storeFromView(view, rawSlot, storeIndex, iconType);
        return true;
    }

    /**
     * The write half of commit(Inventory, int, int, String): commits when the guard allows and owns
     * no repaint. The baseline of a refused cell is left alone on purpose — the viewer still sees the old
     * value until the caller repaints it, and recording the store's newer value here would let a click in that
     * window commit the stale on-screen value.
     */
    private boolean commitCell(Inventory view, int rawSlot, int storeIndex, @Nullable String iconType) {
        ItemStack stored = controller.stored(storeIndex);
        if (!controller.mayCommit(stored, painted[rawSlot])) {
            return false;
        }
        storeFromView(view, rawSlot, storeIndex, iconType);
        return true;
    }

    /** Writes the cell's current value into the store, dropping placeholder icons. */
    private void storeFromView(Inventory view, int rawSlot, int storeIndex, @Nullable String iconType) {
        ItemStack item = view.getItem(rawSlot);
        boolean placeholder = item != null && !item.getType().isAir() && controller.isPlaceholder(item, iconType);
        controller.store(storeIndex, placeholder ? null : item);
    }

    /** Paints one cell from a store value and records that value as the cell's write-back baseline. */
    private void paint(Inventory view, int rawSlot, @Nullable ItemStack stored, @Nullable String iconType) {
        painted[rawSlot] = stored;
        if (stored == null || stored.getType().isAir()) {
            ItemStack icon = controller.icon(iconType);
            view.setItem(rawSlot, icon == null ? null : icon.clone());
        } else {
            view.setItem(rawSlot, stored.clone());
        }
    }

    private void ensurePainted(int size) {
        if (painted.length < size) {
            painted = Arrays.copyOf(painted, size);
        }
    }

    private int storeIndex(GuiLayout layout, int rawSlot) {
        for (GuiSlotGroup group : groups) {
            int[] cells = layout.slotsOf(group.type());
            for (int offset = 0; offset < cells.length && offset < group.count(); offset++) {
                if (cells[offset] == rawSlot) {
                    return group.storeIndex(offset);
                }
            }
        }
        return -1;
    }

    /** The icon type of the group a store index belongs to, clamped to the store's own size. */
    private String iconTypeAt(int storeIndex) {
        for (GuiSlotGroup group : groups) {
            int end = Math.min(group.storeStart() + group.count(), controller.slotCount());
            if (storeIndex >= group.storeStart() && storeIndex < end) {
                return group.iconType();
            }
        }
        return null;
    }
}
