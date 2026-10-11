package com.huidu.farmersdelight.api.util;

import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceProbeTest {
    @Test
    void anInactiveProbeDoesNotAllocateSamplesAndKeepsTheNonSnapshotHolderContract() {
        for (var operation : PerformanceProbe.Operation.values()) assertNull(PerformanceProbe.begin(operation));
        AtomicInteger calls = new AtomicInteger();
        Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[]{Inventory.class}, (proxy, method, args) -> {
                    assertEquals("getHolder", method.getName());
                    assertEquals(1, args.length);
                    assertFalse((boolean) args[0]);
                    calls.incrementAndGet();
                    return null;
                });
        assertNull(PerformanceProbe.holder(inventory));
        assertEquals(1, calls.get());
    }

    @Test
    void aSampleRecordsOnceAndPreservesTheOriginalOperationFailure() {
        AtomicInteger calls = new AtomicInteger();
        PerformanceProbe.Sample sample = new PerformanceProbe.Sample(nanos -> {
            assertTrue(nanos >= 0);
            calls.incrementAndGet();
        });
        RuntimeException failure = new IllegalArgumentException("operation failed");
        assertSame(failure, assertThrows(RuntimeException.class, () -> {
            try (sample) { throw failure; }
        }));
        sample.close();
        assertEquals(1, calls.get());
    }
}
