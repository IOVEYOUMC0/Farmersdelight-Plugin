package com.huidu.farmersdelight.api.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One deadline shared by every step of a plugin shutdown.
 *
 *
 * The alternative — each step waiting its own fixed timeout — makes the worst case the SUM of the
 * timeouts, which is how a plugin turns a stuck flush into a minute of server hang. Here the whole
 * sequence gets a single budget: steps run in order until it runs out, and a step that would exceed it
 * is skipped with a warning instead of blocking. A shutdown must always finish.
 *
 *
 * Steps are expected to be best-effort persistence (flush caches, write back open views, drain an
 * executor). Anything that must not be skipped does not belong behind a budget.
 *
 * ShutdownBudget budget = ShutdownBudget.ofMillis(5000, getLogger());
 * budget.step("flush kegs", () -> KegChunkListener.passivateLoadedChunks());
 * budget.step("write back backpacks", this::flushBackpacks);
 * budget.awaitTermination("worker pool", executor);
 *
 * Failures are reported as one warning that names the step, so every plugin in this family logs the same
 * sentence: pass the wording in with withMessages from the caller's language layer instead of
 * relying on the built-in English default.
 */
public final class ShutdownBudget {

    /** Floor so a misconfigured value cannot turn every step into an immediate skip. */
    public static final long MIN_TOTAL_MILLIS = 200L;

    /** Built-in wording, kept for callers that inject no language-layer text. */
    private static final String DEFAULT_STEP_FAILURE = "Shutdown step '{step}' failed";
    private static final String DEFAULT_EXHAUSTED = "Shutdown budget exhausted at step '{step}';"
            + " remaining steps are skipped so the server can finish stopping."
            + " Unsaved best-effort state may be lost.";

    private final long deadlineNanos;
    private final Logger logger;
    private String stepFailureMessage = DEFAULT_STEP_FAILURE;
    private String exhaustedMessage = DEFAULT_EXHAUSTED;
    private boolean exhaustedReported;

    private ShutdownBudget(long totalMillis, Logger logger) {
        this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMillis);
        this.logger = logger;
    }

    public static ShutdownBudget ofMillis(long totalMillis, Logger logger) {
        return new ShutdownBudget(Math.max(MIN_TOTAL_MILLIS, totalMillis), logger);
    }

    /**
     * Replaces the built-in wording with the caller's own sentences. Both templates may contain {step};
     * an unresolved or blank template keeps the built-in text. The caller is expected to read them from the
     * language layer (FarmersDelight's I18n, or FarmersDelightApi.consoleMessage from an addon) so a failed
     * step reads the same in every plugin and in both locales.
     */
    public ShutdownBudget withMessages(String stepFailure, String budgetExhausted) {
        if (stepFailure != null && !stepFailure.isBlank()) {
            this.stepFailureMessage = stepFailure;
        }
        if (budgetExhausted != null && !budgetExhausted.isBlank()) {
            this.exhaustedMessage = budgetExhausted;
        }
        return this;
    }

    public long remainingMillis() {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.max(0L, remaining);
    }

    public boolean expired() {
        return System.nanoTime() >= deadlineNanos;
    }

    /**
     * Runs one shutdown step unless the budget is already spent. Throwables are contained so one failing
     * step cannot abort the rest of the shutdown; the step name goes into the log line.
     *
     * @return true when the step ran to completion
     */
    public boolean step(String name, Runnable action) {
        if (action == null) {
            return false;
        }
        if (expired()) {
            reportExhausted(name);
            return false;
        }
        try {
            action.run();
            return true;
        } catch (Throwable t) {
            if (logger != null) {
                logger.log(Level.WARNING, render(stepFailureMessage, name), t);
            }
            return false;
        }
    }

    /**
     * Drains an executor within whatever budget is left, then force-cancels. Never waits longer than the
     * shared deadline, so a wedged task cannot extend the shutdown.
     *
     * @return true when the executor terminated cleanly
     */
    public boolean awaitTermination(String name, ExecutorService executor) {
        if (executor == null) {
            return true;
        }
        executor.shutdown();
        long wait = remainingMillis();
        try {
            if (wait > 0L && executor.awaitTermination(wait, TimeUnit.MILLISECONDS)) {
                return true;
            }
            reportExhausted(name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        return false;
    }

    // Reported once: a spent budget usually means every remaining step is skipped, and one line explains
    // the whole tail better than one line per step.
    private void reportExhausted(String name) {
        if (exhaustedReported || logger == null) {
            return;
        }
        exhaustedReported = true;
        logger.warning(render(exhaustedMessage, name));
    }

    private static String render(String template, String step) {
        return template.replace("{step}", step == null ? "" : step);
    }
}
