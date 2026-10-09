package com.huidu.farmersdelight.api.util;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Opt-in timings only; callers retain responsibility for world ownership and data consistency. */
public final class PerformanceProbe {
    public enum Operation { SERIALIZATION, ITEM_SERIALIZATION, SERIALIZATION_LOCK, INVENTORY_HOLDER }

    @FunctionalInterface
    public interface Timing {
        void record(long nanos);
    }

    public static final class Sample implements AutoCloseable {
        private final Timing timing;
        private final long started = System.nanoTime();
        private boolean closed;

        Sample(Timing timing) { this.timing = timing; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            timing.record(System.nanoTime() - started);
        }
    }

    private PerformanceProbe() { }

    /** Null while the operation is not sampled: no clock read or sample allocation. */
    @Nullable
    public static Sample begin(Operation operation) {
        Timing timing = FarmersDelightApi.get().performanceTiming(operation);
        return timing == null ? null : new Sample(timing);
    }

    public static CompoundTag item(ItemStack item) {
        try (Sample ignored = begin(Operation.ITEM_SERIALIZATION)) {
            return ItemStackUtils.saveBukkitItemAsTag(item);
        }
    }

    public static ListTag items(ItemStack[] items) {
        try (Sample ignored = begin(Operation.ITEM_SERIALIZATION)) {
            return ItemStackUtils.saveBukkitItemsAsListTag(items);
        }
    }

    @Nullable
    public static InventoryHolder holder(Inventory inventory) {
        try (Sample ignored = begin(Operation.INVENTORY_HOLDER)) {
            return inventory.getHolder(false);
        }
    }
}
