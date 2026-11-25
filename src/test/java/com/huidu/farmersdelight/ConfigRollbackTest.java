package com.huidu.farmersdelight;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed config reload must restore the fields it changed; a successful one must leave them alone. */
class ConfigRollbackTest {

    @Test
    void restoresEveryCapturedFieldWhenTheReloadThrows() {
        AtomicInteger first = new AtomicInteger(1);
        AtomicInteger second = new AtomicInteger(10);
        List<String> order = new ArrayList<>();
        ConfigRollback rollback = new ConfigRollback();
        rollback.capture(() -> {
            order.add("first");
            first.set(1);
        });
        rollback.capture(() -> {
            order.add("second");
            second.set(10);
        });

        first.set(2);
        second.set(20);
        assertThrows(IllegalStateException.class, () -> rollback.run(() -> {
            throw new IllegalStateException("config file is broken");
        }));

        assertEquals(1, first.get());
        assertEquals(10, second.get());
        assertEquals(List.of("second", "first"), order, "restores run in reverse capture order");
        assertEquals(2, rollback.restoreCount());
    }

    @Test
    void keepsTheNewValuesWhenTheReloadSucceeds() {
        AtomicInteger value = new AtomicInteger(1);
        ConfigRollback rollback = new ConfigRollback();
        rollback.capture(() -> value.set(1));

        rollback.run(() -> value.set(2));

        assertEquals(2, value.get());
    }

    @Test
    void oneBrokenRestoreDoesNotStopTheOthers() {
        AtomicInteger value = new AtomicInteger(1);
        ConfigRollback rollback = new ConfigRollback();
        rollback.capture(() -> value.set(1));
        rollback.capture(() -> {
            throw new IllegalStateException("restore failed");
        });

        value.set(2);
        assertThrows(IllegalStateException.class, () -> rollback.run(() -> {
            throw new IllegalStateException("load failed");
        }));

        assertEquals(1, value.get(), "the remaining fields still get restored");
    }

    @Test
    void theReloadPathUsesTheRollbackHelper() throws IOException {
        String assembly = read("src/main/java/com/huidu/farmersdelight/PluginConfigAssembly.java");
        assertTrue(assembly.contains("void applyWithRollback()"), "the assembly exposes the rollback entry point");
        assertTrue(assembly.contains("rollback.run(this::apply)"), "the whole load pass runs inside the rollback");
        assertTrue(assembly.contains("rollback.capture("), "at least the published config fields are captured");

        String plugin = read("src/main/java/com/huidu/farmersdelight/FarmersDelightPlugin.java");
        assertTrue(plugin.contains("configAssembly.applyWithRollback()"), "loadConfigs must use the rollback path");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/", ".."}) {
            Path path = Path.of(prefix, relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("source not found: " + relative);
    }
}
