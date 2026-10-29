package com.huidu.farmersdelight.shutdown;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.api.util.ShutdownBudget;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the teardown steps of one disable in order. A step that throws is logged and the sequence continues,
 * because the plugin is already going away and every later step still has to run. Steps that only persist
 * best-effort state share one deadline: once the budget is spent they are skipped, so a stuck write cannot hold
 * the server open.
 */
public final class ShutdownSteps {

    private final Logger logger;
    private volatile ShutdownBudget budget;

    public ShutdownSteps(Logger logger) {
        this.logger = logger;
    }

    /** Opens the shared deadline every budgeted step measures itself against. */
    public void beginBudget(ShutdownBudget newBudget) {
        this.budget = newBudget;
    }

    /** The running deadline, or null when the sequence was not opened with one. */
    @Nullable
    public ShutdownBudget budget() {
        return this.budget;
    }

    /** Runs one structural step; a failure is reported under the step's own language key. */
    public void run(String stepKey, Runnable action, Object... stepArgs) {
        try {
            action.run();
        } catch (Throwable throwable) {
            this.logger.log(Level.WARNING, failureText(stepKey, stepArgs), throwable);
        }
    }

    /** Runs one persistence step, or skips it once the shared deadline is spent. */
    public void runBudgeted(String stepKey, Runnable action) {
        ShutdownBudget current = this.budget;
        if (current == null) {
            run(stepKey, action);
            return;
        }
        current.step(I18n.formatConsole(stepKey), action);
    }

    /**
     * One sentence for every shutdown step failure: the outer text and the step name both come from the language
     * layer, so the line reads as a sentence in either locale and names the step that failed.
     */
    private static String failureText(String stepKey, Object... stepArgs) {
        return I18n.formatConsole("plugin.disable_step_failed",
                "step", I18n.formatConsole(stepKey, stepArgs));
    }
}
