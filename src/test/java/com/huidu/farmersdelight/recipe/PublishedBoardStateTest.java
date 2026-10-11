package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PublishedBoardStateTest {
    @Test void rollbackAndReloadNeverExposeMixedIndexes() throws Exception {
        var manager = new CuttingBoardRecipeManager(null);
        var first = new CuttingBoardRecipeManager.PublishedState(Map.of(), 1, List.of(),
                Map.of("a", Set.of("first")), Set.of("first"), Map.of(), List.of());
        var second = new CuttingBoardRecipeManager.PublishedState(Map.of(), 2, List.of(),
                Map.of("b", Set.of("second")), Set.of("second"), Map.of(), List.of());
        manager.restorePublished(first);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < 20000; i++) {
                    var captured = manager.capturePublished();
                    assertTrue(captured == first || captured == second);
                    assertEquals(captured.packRecipeCount() == 1 ? Set.of("first") : Set.of("second"),
                            captured.tagInputRecipeIds());
                }
            } catch (Throwable problem) { failure.set(problem); }
        });
        reader.start();
        for (int i = 0; i < 2000; i++) manager.restorePublished(i % 2 == 0 ? second : first);
        reader.join();
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        manager.restorePublished(first);
        assertSame(first, manager.capturePublished());
    }
}
