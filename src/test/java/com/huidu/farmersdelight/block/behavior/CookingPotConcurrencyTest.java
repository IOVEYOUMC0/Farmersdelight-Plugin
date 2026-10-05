package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BlockPosKey;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookingPotConcurrencyTest {

    private static final int OUTPUT_SLOT = 8;  // CookingPotLayout.DEFAULT's single output slot.

    @Test
    void concurrentTakesNeverOverDeliver_eightViewersOneAtATime() throws Exception {
        CookingPotBlockEntity be = freshPot();
        seedOutput(be, 64);

        int viewers = 8;
        int takesPerViewer = 8;  // 8 viewers × 8 takes × 1 each = 64 → exactly drains the stack.
        AtomicInteger taken = new AtomicInteger(0);
        runConcurrently(viewers, () -> {
            for (int i = 0; i < takesPerViewer; i++) {
                CookingPotBlockEntity.TakenMeal meal = be.takeOutputSlotPortionForDelivery(OUTPUT_SLOT, 1);
                if (meal != null && meal.item() != null) {
                    taken.addAndGet(meal.item().getAmount());
                }
            }
        });

        assertEquals(64, taken.get(),
                "8 viewers × 8 takes × 1 should sum to the original 64 — never higher (dup) or lower (lost).");
        CookingPotBlockEntity.TakenMeal residual = be.takeOutputSlotPortionForDelivery(OUTPUT_SLOT, 1);
        assertNull(residual, "Output slot must be empty after exactly 64 single-item takes.");
    }

    @Test
    void oversubscribedConcurrentTakesAreCappedByStock() throws Exception {
        CookingPotBlockEntity be = freshPot();
        seedOutput(be, 64);

        int viewers = 8;
        int requestedPerTake = 16;  // 8 × 16 = 128 requested, but stock is only 64.
        AtomicInteger taken = new AtomicInteger(0);
        runConcurrently(viewers, () -> {
            CookingPotBlockEntity.TakenMeal meal = be.takeOutputSlotPortionForDelivery(OUTPUT_SLOT, requestedPerTake);
            if (meal != null && meal.item() != null) {
                taken.addAndGet(meal.item().getAmount());
            }
        });

        assertEquals(64, taken.get(),
                "Oversubscribed take requests must total exactly the stock — clamped by inventoryLock.");
    }

    @Test
    void takeFromClearedSlotReturnsNull_simulatingBlockBreakRace() {
        CookingPotBlockEntity be = freshPot();
        seedOutput(be, 32);
        // closeOpenGuisAt(world,x,y,z) tears the BE state down ahead of the actual block break to
        // prevent a click from racing in after; the test simulates the clear here and asserts a stray click
        // can't manufacture an item.
        be.setInventorySlot(OUTPUT_SLOT, null);
        assertNull(be.takeOutputSlotPortionForDelivery(OUTPUT_SLOT, 1),
                "Take from a cleared slot must return null — the dup the closeOpenGuisAt comment warns about.");
    }

    @Test
    void interleavedClearsAndTakesNeverDuplicateAcrossClears() throws Exception {
        CookingPotBlockEntity be = freshPot();

        int rounds = 32;
        int stackPerRound = 16;
        AtomicInteger stocked = new AtomicInteger(0);
        AtomicInteger taken = new AtomicInteger(0);

        Thread stocker = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                be.setInventorySlot(OUTPUT_SLOT, new StubStack(stackPerRound));
                stocked.addAndGet(stackPerRound);
                Thread.yield();
                be.setInventorySlot(OUTPUT_SLOT, null);
                Thread.yield();
            }
        }, "stocker");

        List<Thread> viewers = new ArrayList<>();
        for (int v = 0; v < 4; v++) {
            viewers.add(new Thread(() -> {
                for (int i = 0; i < rounds * stackPerRound; i++) {
                    CookingPotBlockEntity.TakenMeal meal = be.takeOutputSlotPortionForDelivery(OUTPUT_SLOT, 1);
                    if (meal != null && meal.item() != null) {
                        taken.addAndGet(meal.item().getAmount());
                    }
                }
            }, "viewer-" + v));
        }

        stocker.start();
        for (Thread v : viewers) v.start();
        stocker.join();
        for (Thread v : viewers) v.join();

        assertTrue(taken.get() <= stocked.get(),
                "Total taken (" + taken.get() + ") must never exceed total stocked (" + stocked.get()
                        + ") — anything higher is a dup.");
    }

    // ----- helpers -----

    private static CookingPotBlockEntity freshPot() {
        // null World keeps syncWorldlyContainer() a no-op so CE's block manager is not dragged into the test.
        return new CookingPotBlockEntity(null, new BlockPosKey(0, 0, 0), null, CookingPotLayout.DEFAULT, null);
    }

    private static void seedOutput(CookingPotBlockEntity be, int amount) {
        be.setInventorySlot(OUTPUT_SLOT, new StubStack(amount));
        assertNotNull(be.getMealDisplayItem(), "Stub stack must land in the output slot before the race starts.");
    }

    private static void runConcurrently(int threads, Runnable body) throws InterruptedException {
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            workers.add(new Thread(() -> {
                try {
                    start.await();
                } catch (Exception ignored) {
                }
                body.run();
            }, "worker-" + i));
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();
    }

    static final class StubStack extends ItemStack {
        private int amount;

        StubStack(int amount) {
            super();  // skips Material lookup
            this.amount = amount;
        }

        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int n) { this.amount = n; }
        @Override public Material getType() { return Material.STONE; }
        @Override public boolean hasItemMeta() { return false; }
        @Override public ItemMeta getItemMeta() { return null; }
        @Override public ItemStack clone() { return new StubStack(amount); }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof StubStack; }
    }
}
