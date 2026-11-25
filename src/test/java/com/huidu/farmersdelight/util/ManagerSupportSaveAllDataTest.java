package com.huidu.farmersdelight.util;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** One broken entry in a bulk save must not skip the remaining entries. */
class ManagerSupportSaveAllDataTest {

    @Test
    void aThrowingSaverDoesNotAbortTheRest() {
        Map<Location, String> entries = new LinkedHashMap<>();
        entries.put(new Location(null, 0, 0, 0), "first");
        entries.put(new Location(null, 1, 0, 0), "second");
        entries.put(new Location(null, 2, 0, 0), "third");
        AtomicInteger attempted = new AtomicInteger();

        assertDoesNotThrow(() -> ManagerSupport.saveAllData(entries, (location, value) -> {
            attempted.incrementAndGet();
            if ("second".equals(value)) {
                throw new IllegalStateException("broken entry");
            }
        }));

        assertEquals(3, attempted.get(), "every entry must be attempted despite the middle failure");
    }
}
