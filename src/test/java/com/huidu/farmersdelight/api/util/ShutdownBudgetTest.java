package com.huidu.farmersdelight.api.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget reports step failures itself, so its wording is the single line the whole plugin family prints
 * when a shutdown step fails: the language layer hands it in through withMessages, and an addon that passes
 * nothing keeps the built-in English sentence. Three properties are pinned here — the injected wording is
 * used verbatim, a failed step never aborts the steps after it (the shutdown path has no retry), and a spent
 * budget is reported once instead of once per skipped step.
 */
class ShutdownBudgetTest {

    private static final String ZH_STEP_FAILURE = "禁用插件时执行 {step} 失败；将继续关闭流程。";
    private static final String ZH_EXHAUSTED = "关闭预算在步骤“{step}”处耗尽；剩余步骤已跳过。";

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static Logger silentLogger(RecordingHandler handler) {
        Logger logger = Logger.getLogger("shutdown-budget-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        return logger;
    }

    @Test
    void stepFailureUsesTheInjectedLanguageLayerMessage() {
        RecordingHandler handler = new RecordingHandler();
        ShutdownBudget budget = ShutdownBudget.ofMillis(5000, silentLogger(handler))
                .withMessages(ZH_STEP_FAILURE, ZH_EXHAUSTED);

        IllegalStateException failure = new IllegalStateException("boom");
        boolean completed = budget.step("保存方块数据", () -> {
            throw failure;
        });

        assertFalse(completed, "a failing step must report failure to the caller");
        assertEquals(1, handler.records.size(), "one warning per failed step");
        LogRecord record = handler.records.get(0);
        assertEquals(Level.WARNING, record.getLevel());
        assertEquals("禁用插件时执行 保存方块数据 失败；将继续关闭流程。", record.getMessage());
        assertEquals(failure, record.getThrown(), "the stack trace must stay attached to the report");
    }

    @Test
    void stepFailureKeepsTheBuiltInWordingWithoutTemplates() {
        RecordingHandler handler = new RecordingHandler();
        ShutdownBudget budget = ShutdownBudget.ofMillis(5000, silentLogger(handler));

        budget.step("flush kegs", () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals("Shutdown step 'flush kegs' failed", handler.records.get(0).getMessage());
    }

    @Test
    void failedStepDoesNotAbortTheStepsAfterIt() {
        RecordingHandler handler = new RecordingHandler();
        ShutdownBudget budget = ShutdownBudget.ofMillis(5000, silentLogger(handler))
                .withMessages(ZH_STEP_FAILURE, ZH_EXHAUSTED);
        List<String> ran = new ArrayList<>();

        assertTrue(budget.step("first", () -> ran.add("first")));
        assertFalse(budget.step("second", () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(budget.step("third", () -> ran.add("third")));

        assertEquals(List.of("first", "third"), ran, "a failing step must not skip the remaining steps");
    }

    @Test
    void spentBudgetIsReportedOnceWithTheInjectedMessage() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        ShutdownBudget budget = ShutdownBudget.ofMillis(ShutdownBudget.MIN_TOTAL_MILLIS, silentLogger(handler))
                .withMessages(ZH_STEP_FAILURE, ZH_EXHAUSTED);

        // Spends the whole deadline, so every step after it is skipped.
        budget.step("slow", () -> {
            try {
                Thread.sleep(ShutdownBudget.MIN_TOTAL_MILLIS + 200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertFalse(budget.step("skipped", () -> {
                throw new AssertionError("a skipped step must not run");
            }));
            assertFalse(budget.awaitTermination("worker pool", executor));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, handler.records.size(), "the spent budget is reported once, not per skipped step");
        LogRecord record = handler.records.get(0);
        assertEquals(Level.WARNING, record.getLevel());
        assertEquals("关闭预算在步骤“skipped”处耗尽；剩余步骤已跳过。", record.getMessage());
    }
}
