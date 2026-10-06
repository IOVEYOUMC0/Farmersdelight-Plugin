package com.huidu.farmersdelight.manager;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skillet tick changes the same stored item, recipe and progress that a hopper insert reads and rewrites,
 * and the hopper runs on the region thread that owns the hopper rather than the one that owns the block. Every
 * one of those changes therefore has to happen inside the per-block monitor: a settlement that ran beside it
 * would let the hopper read a stack that was just cleared, or lose the insert it already reported.
 */
class SkilletTickLockTest {

    private static final int DEFAULT_COOKING_TIME = 200;
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final Path SKILLET_MANAGER = Path.of("src", "main", "java", "com", "huidu",
            "farmersdelight", "manager", "SkilletManager.java");

    @Test
    void theFinishedSettlementConsumesOneItemAndReportsWhatIsLeft() {
        SkilletData skillet = new SkilletData(new Location(null, 0, 0, 0), DEFAULT_COOKING_TIME);
        Stack food = new Stack(3);
        skillet.storedItem = food;
        skillet.cookingProgress = DEFAULT_COOKING_TIME;
        skillet.ownerId = OWNER;
        skillet.ownerName = "cook";

        SkilletManager.CookingSettlement settlement = SkilletManager.settleFinishedCooking(skillet, false);

        assertNotNull(settlement);
        assertTrue(settlement.keepStored(), "two items are left to stack");
        assertSame(food, skillet.storedItem);
        assertEquals(2, food.getAmount());
        assertEquals(0, skillet.cookingProgress, "the next item starts from zero progress");
        assertEquals(3, settlement.previous().amount(), "the captured state keeps the pre-settlement amount");
        assertEquals(DEFAULT_COOKING_TIME, settlement.previous().progress());
        assertEquals(OWNER, settlement.ownerId());
        assertEquals("cook", settlement.ownerName());
    }

    @Test
    void theLastItemClearsTheStackRecipeAndOwner() {
        SkilletData skillet = new SkilletData(new Location(null, 0, 0, 0), DEFAULT_COOKING_TIME);
        skillet.storedItem = new Stack(1);
        skillet.cookingProgress = DEFAULT_COOKING_TIME;
        skillet.ownerId = OWNER;
        skillet.ownerName = "cook";

        SkilletManager.CookingSettlement settlement = SkilletManager.settleFinishedCooking(skillet, false);

        assertNotNull(settlement);
        assertFalse(settlement.keepStored(), "nothing is left to stack");
        assertNull(skillet.storedItem);
        assertNull(skillet.currentRecipe);
        assertNull(skillet.ownerId);
        assertNull(skillet.ownerName);
        assertEquals(0, skillet.cookingProgress);
        assertEquals(1, settlement.previous().amount(), "the rollback state still holds the consumed item");
    }

    @Test
    void aSettlementThatNeedsARecipeIsRefusedWithoutOne() {
        SkilletData skillet = new SkilletData(new Location(null, 0, 0, 0), DEFAULT_COOKING_TIME);
        Stack food = new Stack(2);
        skillet.storedItem = food;

        assertNull(SkilletManager.settleFinishedCooking(skillet, true), "no recipe, nothing to settle");
        assertSame(food, skillet.storedItem, "the refused settlement must not consume the item");
        assertEquals(2, food.getAmount());
    }

    @Test
    void theRollbackPutsTheItemItsAmountProgressAndOwnerBack() {
        SkilletData skillet = new SkilletData(new Location(null, 0, 0, 0), DEFAULT_COOKING_TIME);
        Stack food = new Stack(2);
        skillet.storedItem = food;
        skillet.cookingProgress = 80;
        skillet.ownerId = OWNER;
        skillet.ownerName = "cook";
        SkilletManager.CookingState previous = SkilletManager.captureCookingState(skillet);

        SkilletManager.settleFinishedCooking(skillet, false);
        assertEquals(1, food.getAmount());

        SkilletManager.restoreCookingState(skillet, previous);

        assertSame(food, skillet.storedItem);
        assertEquals(2, food.getAmount(), "the amount is restored on the same stack");
        assertEquals(80, skillet.cookingProgress);
        assertEquals(OWNER, skillet.ownerId);
        assertEquals("cook", skillet.ownerName);
    }

    @Test
    void aHopperInsertNeverSeesTheSettlementHalfApplied() throws Exception {
        SkilletData skillet = new SkilletData(new Location(null, 0, 0, 0), DEFAULT_COOKING_TIME);
        Stack food = new Stack(1);
        skillet.storedItem = food;
        skillet.cookingProgress = DEFAULT_COOKING_TIME;

        CountDownLatch holderInside = new CountDownLatch(1);
        AtomicBoolean holderSawTheStackCleared = new AtomicBoolean();
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            // Stands for the paths that already hold the per-block monitor while they touch the stored stack;
            // insertHopperInput is the one that runs on another region thread. The presence check reads the field
            // rather than SkilletData.hasItem() because a real Material's air check needs a live server, and the
            // production check is bound to the monitor by the source contract in the sibling test.
            synchronized (skillet) {
                if (skillet.storedItem == null) return;
                holderInside.countDown();
                // Holding the monitor across this window is what makes the outcome deterministic: the settlement
                // below cannot run beside it, whatever order the two threads are scheduled in.
                sleep(50L);
                if (skillet.storedItem == null) {
                    holderSawTheStackCleared.set(true);
                    return;
                }
                int free = Math.max(0, skillet.storedItem.getMaxStackSize() - skillet.storedItem.getAmount());
                skillet.storedItem.setAmount(skillet.storedItem.getAmount() + free);
            }
        });
        holder.setUncaughtExceptionHandler((thread, failure) -> holderFailure.set(failure));
        holder.start();
        assertTrue(holderInside.await(10, TimeUnit.SECONDS),
                "the monitor holder must reach its critical section; failure: " + holderFailure.get());

        SkilletManager.CookingSettlement settlement = SkilletManager.settleFinishedCooking(skillet, false);
        holder.join(2000L);

        assertNull(holderFailure.get(), "the monitor holder must not die: " + holderFailure.get());
        assertFalse(holderSawTheStackCleared.get(),
                "the settlement held the monitor, so the holder must not see the stack cleared underneath it");
        assertNotNull(settlement);
        assertTrue(settlement.keepStored(), "the holder refilled the stack before the settlement ran");
        assertEquals(63, food.getAmount(), "one item was consumed from the refilled stack");
    }

    @Test
    void theTickCreditWaitsForTheHopperMonitor() throws Exception {
        SkilletData skillet = new SkilletData(new Location(null, 0, 0, 0), DEFAULT_COOKING_TIME);
        skillet.storedItem = new Stack(1);

        CountDownLatch holderInside = new CountDownLatch(1);
        AtomicReference<Boolean> creditedWhileTheMonitorWasHeld = new AtomicReference<>(false);
        AtomicReference<Boolean> finished = new AtomicReference<>();
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            synchronized (skillet) {
                holderInside.countDown();
                sleep(50L);
                creditedWhileTheMonitorWasHeld.set(finished.get() != null);
            }
        });
        holder.setUncaughtExceptionHandler((thread, failure) -> holderFailure.set(failure));
        holder.start();
        assertTrue(holderInside.await(10, TimeUnit.SECONDS),
                "the monitor holder must reach its critical section; failure: " + holderFailure.get());

        finished.set(SkilletManager.creditPlacedCooking(skillet, 1000L, true, 2));
        holder.join(2000L);

        assertNull(holderFailure.get(), "the monitor holder must not die: " + holderFailure.get());
        assertFalse(creditedWhileTheMonitorWasHeld.get(),
                "the credit must run inside the monitor, not beside it");
        assertFalse(finished.get(), "one interval is far from a finished skillet");
        assertEquals(SkilletManager.PLACED_TICK_INTERVAL, skillet.cookingProgress,
                "the first visit credits one interval");
    }

    @Test
    void theTickChainRoutesItsStateChangesThroughTheLockedSeams() throws IOException {
        String source = read(SKILLET_MANAGER);
        assertEquals(3, count(source, "creditPlacedCooking("),
                "the declaration and the two tick credit sites are the only ones");
        assertEquals(2, count(source, "settleFinishedCooking("),
                "the declaration and the finished-cooking site are the only ones");
        assertTrue(methodBody(source, "private void advancePlacedCooking(Location location, SkilletData skillet)")
                .contains("creditPlacedCooking("), "the placed tick must credit through the locked seam");
        assertTrue(methodBody(source, "private void finishCooking(Location location, SkilletData skillet)")
                .contains("settleFinishedCooking("), "the settlement must go through the locked seam");
        String hopper = methodBody(source, "public ItemStack insertHopperInput(Location location, ItemStack item)");
        int monitor = hopper.indexOf("synchronized (skillet)");
        int presenceCheck = hopper.indexOf("skillet.hasItem()");
        int arithmetic = hopper.indexOf("skillet.storedItem.getMaxStackSize()");
        assertTrue(monitor >= 0 && presenceCheck > monitor && arithmetic > presenceCheck,
                "the hopper insert must take the monitor before it checks the stored stack and moves items");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method: " + signature);
        Matcher next = Pattern.compile("\\n    (?:public |private |protected |record |static )").matcher(source);
        next.region(start + signature.length(), source.length());
        return next.find() ? source.substring(start, next.start()) : source.substring(start);
    }

    private static int count(String source, String token) {
        int found = 0;
        int at = source.indexOf(token);
        while (at >= 0) {
            found++;
            at = source.indexOf(token, at + token.length());
        }
        return found;
    }

    private static String read(Path path) throws IOException {
        Path resolved = path;
        if (!Files.isRegularFile(resolved)) {
            Path nested = Path.of("FarmersDelight").resolve(path);
            if (Files.isRegularFile(nested)) {
                resolved = nested;
            }
        }
        assertTrue(Files.isRegularFile(resolved), "missing source file: " + path + " (working directory "
                + Path.of("").toAbsolutePath() + ")");
        return Files.readString(resolved).replace("\r\n", "\n");
    }

    /** An ItemStack without a live server behind it; these tests only carry the amount and the stack size. */
    private static final class Stack extends ItemStack {
        private int amount;

        Stack(int amount) {
            super();
            this.amount = amount;
        }

        @Override public int getAmount() {
            return amount;
        }

        @Override public void setAmount(int value) {
            amount = value;
        }

        @Override public int getMaxStackSize() {
            return 64;
        }

        @Override public Material getType() {
            return Material.STONE;
        }
    }
}
