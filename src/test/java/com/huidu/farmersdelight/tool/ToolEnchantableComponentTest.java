package com.huidu.farmersdelight.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enchantable writer: integer first, record as the fallback, loud (once) when neither works.
 */
class ToolEnchantableComponentTest {

    @AfterEach
    void forgetWarning() {
        ToolEnchantableComponent.resetWarning();
    }

    @Test
    void theRecordShapeIsPreferred() {
        AtomicReference<Object> written = new AtomicReference<>();

        assertTrue(ToolEnchantableComponent.write(14, written::set, () -> {
        }));
        assertEquals(Map.of("value", 14), written.get(),
                "the pack's component path wants the record form ({value: N}), so it goes first");
    }

    @Test
    void aRejectedRecordFallsBackToTheBareInteger() {
        AtomicReference<Object> written = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        ToolEnchantableComponent.ComponentWriter writer = value -> {
            attempts.incrementAndGet();
            if (value instanceof Map) {
                throw new IllegalStateException("record shape rejected by this server");
            }
            written.set(value);
        };

        assertTrue(ToolEnchantableComponent.write(22, writer, () -> {
        }), "the bare integer has to be tried when the record form is refused");
        assertEquals(2, attempts.get());
        assertEquals(22, written.get());
    }

    @Test
    void twoRejectionsWarnExactlyOnce() {
        AtomicInteger warnings = new AtomicInteger();
        Runnable warn = warnings::incrementAndGet;
        ToolEnchantableComponent.ComponentWriter alwaysFails = value -> {
            throw new IllegalArgumentException("nope");
        };

        assertFalse(ToolEnchantableComponent.write(14, alwaysFails, warn));
        assertFalse(ToolEnchantableComponent.write(14, alwaysFails, warn));

        assertEquals(1, warnings.get(), "a silent zero is what hid this defect; it must be said once, not twice");
    }

    @Test
    void aLinkageErrorIsTreatedAsARejectionToo() {
        AtomicReference<Object> written = new AtomicReference<>();
        ToolEnchantableComponent.ComponentWriter writer = value -> {
            if (value instanceof Map) {
                throw new NoClassDefFoundError("older server");
            }
            written.set(value);
        };

        assertTrue(ToolEnchantableComponent.write(15, writer, () -> {
        }));
        assertEquals(15, written.get());
    }
}
