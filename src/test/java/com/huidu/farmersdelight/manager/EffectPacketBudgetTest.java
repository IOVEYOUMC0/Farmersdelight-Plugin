package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunk effect allowance is what keeps a dense pocket of stations from flooding nearby clients. A
 * plain "read, compare, increment" cannot hold it for an effect that costs more than one packet: with a
 * single packet left, a two-packet effect passed the comparison and then pushed the chunk over its limit.
 */
class EffectPacketBudgetTest {

    @Test
    void reservesWhileThereIsRoom() {
        AtomicInteger used = new AtomicInteger();
        assertTrue(EffectPacketBudget.tryReserve(used, 5, 1));
        assertEquals(1, used.get());
        assertTrue(EffectPacketBudget.tryReserve(used, 5, 2));
        assertEquals(3, used.get());
        assertTrue(EffectPacketBudget.tryReserve(used, 5, 2));
        assertEquals(5, used.get());
    }

    @Test
    void refusesAnEffectThatWouldExceedTheLimit() {
        AtomicInteger used = new AtomicInteger(4);
        assertFalse(EffectPacketBudget.tryReserve(used, 5, 2), "two packets do not fit in one packet of room");
        assertEquals(4, used.get(), "a refused reservation must not move the counter");
    }

    @Test
    void refusesAnEffectLargerThanTheWholeAllowance() {
        AtomicInteger used = new AtomicInteger();
        assertFalse(EffectPacketBudget.tryReserve(used, 1, 2));
        assertEquals(0, used.get());
    }

    @Test
    void refusesWithoutACounterOrCost() {
        assertFalse(EffectPacketBudget.tryReserve(null, 5, 1), "no viewers means no packets to send");
        assertFalse(EffectPacketBudget.tryReserve(new AtomicInteger(), 5, 0));
    }

    @Test
    void concurrentReservationsNeverOvershoot() throws InterruptedException {
        AtomicInteger used = new AtomicInteger();
        int limit = 200;
        Thread[] workers = new Thread[8];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                while (EffectPacketBudget.tryReserve(used, limit, 2)) {
                    // keep reserving until the budget is exhausted
                }
            });
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertEquals(limit, used.get(), "reservations must add up to the limit exactly, never past it");
    }
}
