package com.huidu.farmersdelight.manager;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A slot change followed by a failing display or persistence step must not survive that failure: the item would
 * otherwise sit in the stove and in the hand at the same time. The rollback has to restore every field of the
 * slot, run the caller's repair step, and still propagate the failure that caused it.
 */
class StoveSlotRollbackTest {

    private static final int DEFAULT_COOK_TIME = 600;
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Path STOVE_MANAGER = Path.of("src", "main", "java", "com", "huidu",
            "farmersdelight", "manager", "StoveManager.java");
    private static final String[] COMPOSITE_EXCHANGES = {
            "public boolean handleInteract(Player player, Block block, ItemStack itemInHand)",
            "private boolean retrieveItem(Player player, Location location, StoveData stove)",
            "private void finishCooking(Location location, StoveData stove, int slot)"};

    @Test
    void aFailingStepLeavesTheSlotExactlyAsItWasAndRethrows() {
        StoveData stove = new StoveData(new Location(null, 0, 0, 0), DEFAULT_COOK_TIME);
        Stack stored = new Stack();
        stove.items[1] = stored;
        stove.cookingTime[1] = 7;
        stove.maxTime[1] = 9;
        stove.ownerIds[1] = OWNER;
        stove.ownerNames[1] = "owner";
        StoveManager.SlotState previous = StoveManager.captureSlot(stove, 1);

        // The change the interaction makes before the fallible step.
        stove.items[1] = new Stack();
        stove.cookingTime[1] = 0;
        stove.maxTime[1] = 120;
        stove.ownerIds[1] = OTHER;
        stove.ownerNames[1] = "other";

        AtomicInteger repairs = new AtomicInteger();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> StoveManager.applySlotStep(stove, 1,
                previous, () -> {
                    throw new IllegalStateException("block entity changed");
                }, repairs::incrementAndGet));

        assertEquals("block entity changed", thrown.getMessage());
        assertSame(stored, stove.items[1], "the stored item comes back");
        assertEquals(7, stove.cookingTime[1]);
        assertEquals(9, stove.maxTime[1]);
        assertEquals(OWNER, stove.ownerIds[1]);
        assertEquals("owner", stove.ownerNames[1]);
        assertEquals(1, repairs.get(), "the repair step runs once");
    }

    @Test
    void aClaimedEmptySlotGoesBackToEmptyWhenTheStepFails() {
        StoveData stove = new StoveData(new Location(null, 0, 0, 0), DEFAULT_COOK_TIME);
        StoveManager.SlotState previous = StoveManager.captureSlot(stove, 4);
        stove.items[4] = new Stack();
        stove.cookingTime[4] = 0;
        stove.maxTime[4] = 300;
        stove.ownerIds[4] = OWNER;
        stove.ownerNames[4] = "owner";

        assertThrows(NoClassDefFoundError.class, () -> StoveManager.applySlotStep(stove, 4, previous, () -> {
            throw new NoClassDefFoundError("craftengine");
        }, () -> {
        }));

        assertNull(stove.items[4], "the claimed slot is empty again");
        assertEquals(0, stove.cookingTime[4]);
        assertEquals(DEFAULT_COOK_TIME, stove.maxTime[4]);
        assertNull(stove.ownerIds[4]);
        assertNull(stove.ownerNames[4]);
    }

    @Test
    void theRepairRunsAfterTheSlotIsBack() {
        StoveData stove = new StoveData(new Location(null, 0, 0, 0), DEFAULT_COOK_TIME);
        Stack stored = new Stack();
        stove.items[2] = stored;
        StoveManager.SlotState previous = StoveManager.captureSlot(stove, 2);
        stove.items[2] = null;

        AtomicInteger repairs = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> StoveManager.applySlotStep(stove, 2, previous, () -> {
            throw new IllegalStateException("persist");
        }, () -> {
            assertSame(stored, stove.items[2], "the repair must see the slot already rolled back");
            repairs.incrementAndGet();
        }));

        assertEquals(1, repairs.get());
        assertSame(stored, stove.items[2]);
    }

    @Test
    void aSuccessfulStepKeepsTheChangeAndSkipsTheRepair() {
        StoveData stove = new StoveData(new Location(null, 0, 0, 0), DEFAULT_COOK_TIME);
        StoveManager.SlotState previous = StoveManager.captureSlot(stove, 3);
        Stack placed = new Stack();
        AtomicInteger repairs = new AtomicInteger();

        StoveManager.applySlotStep(stove, 3, previous, () -> stove.items[3] = placed, repairs::incrementAndGet);

        assertSame(placed, stove.items[3]);
        assertEquals(0, repairs.get(), "no failure, no repair");
    }

    @Test
    void everyCompositeSlotExchangeRunsThroughTheRollbackSeam() throws IOException {
        String source = read(STOVE_MANAGER);
        assertEquals(4, count(source, "applySlotStep("),
                "the declaration plus the three composite exchanges are the only sites");
        for (String signature : COMPOSITE_EXCHANGES) {
            assertTrue(methodBody(source, signature).contains("applySlotStep("),
                    signature + " must run its display, persistence and hand-over step through the seam");
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

    /** An ItemStack without a live server behind it; the tests only carry the instance around. */
    private static final class Stack extends ItemStack {
        Stack() {
            super();
        }
    }
}
