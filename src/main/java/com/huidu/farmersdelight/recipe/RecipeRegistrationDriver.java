package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.scheduler.PluginTask;

import java.util.List;
import java.util.Objects;

/**
 * Owns the one active recipe registration batch and drives it on the thread that owns recipe state.
 *
 *
 * A reload used to register every entry of a recipe file in a single tick, which is the spike behind the
 * reported TPS dip. The driver runs the first slice inline — so a file no bigger than the budget behaves
 * exactly as before, with no tick of delay — and only then arms a repeating task that keeps registering the
 * remainder, at most budget entries per tick, on the same thread. Nothing here is asynchronous and
 * nothing here hands work to another thread.
 *
 *
 * Re-entrancy is defined as cancel and restart: starting a new pass cancels the previous batch (its
 * remaining entries are never registered) and begins again from the first id, so a reload that arrives
 * mid-pass never double-registers and never leaves two passes interleaved.
 */
public final class RecipeRegistrationDriver {

    /** Schedules the per-tick slice; production passes the plugin's scheduler, tests pass a fake. */
    public interface TaskArm {

        PluginTask arm(Runnable tick);
    }

    private final TaskArm taskArm;
    private volatile RecipeRegistrationBatch batch;
    private volatile PluginTask task = PluginTask.NOOP;
    private volatile int budget = 1;
    private volatile Runnable onDone;

    public RecipeRegistrationDriver(TaskArm taskArm) {
        this.taskArm = Objects.requireNonNull(taskArm, "taskArm");
    }

    /** Whether a pass is still registering entries. */
    public boolean isRunning() {
        return batch != null;
    }

    /** i/N of the running pass, or an empty string when there is none. */
    public String progress() {
        RecipeRegistrationBatch current = batch;
        return current == null ? "" : current.cursor() + "/" + current.size();
    }

    /**
     * Registers up to budget entries now and schedules the rest; onDone runs once, on the
     * thread that finished the pass, after the last entry.
     */
    public void start(List<String> ids, RecipeRegistrationBatch.Step step, int budget, Runnable onDone) {
        cancel();
        this.budget = Math.max(1, budget);
        this.onDone = onDone;
        RecipeRegistrationBatch fresh = new RecipeRegistrationBatch(ids, step);
        this.batch = fresh;
        tick();
        if (this.batch == fresh && !fresh.isDone()) {
            this.task = this.taskArm.arm(this::tick);
        }
    }

    /** Drops the running pass without registering anything else: plugin disable and a replaced reload. */
    public void cancel() {
        RecipeRegistrationBatch current = this.batch;
        this.batch = null;
        this.onDone = null;
        if (current != null) {
            current.cancel();
        }
        stopTask();
    }

    private void tick() {
        RecipeRegistrationBatch current = this.batch;
        if (current == null) {
            stopTask();
            return;
        }
        try {
            current.run(this.budget);
        } catch (RuntimeException | Error error) {
            // Loud failure: the batch is dropped so nothing half-registered keeps running.
            this.batch = null;
            current.cancel();
            stopTask();
            throw error;
        }
        if (current.isDone()) {
            this.batch = null;
            stopTask();
            Runnable done = this.onDone;
            this.onDone = null;
            if (done != null) {
                done.run();
            }
        }
    }

    private void stopTask() {
        PluginTask running = this.task;
        this.task = PluginTask.NOOP;
        if (running != null && !running.isCancelled()) {
            running.cancel();
        }
    }
}
