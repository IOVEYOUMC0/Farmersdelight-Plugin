package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.util.yaml.YamlFileTransaction;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The disk half of an editor save.
 *
 *
 * The editor builds what has to be written on the thread that owns the player and the menu — that is where the
 * recipe is serialised against CraftEngine's items — and hands the plan over here. The file work runs on the
 * plugin's bounded async pool, and the outcome is handed back through the caller's owner hop so the menu and
 * the chat feedback are only ever touched on their own thread.
 *
 *
 * A full queue is a failure the caller shows, never a silent drop: nothing is written, and the file is exactly
 * as it was. Writes still in flight when the plugin stops are drained by the scheduler's shutdown budget; what
 * is left after that is reported rather than lost quietly.
 */
public final class EditorWriteQueue {

    /** What one editor action writes, built on the owner thread and applied later. */
    public record EditPlan(Path file, List<YamlFileTransaction.Edit> edits,
                           YamlFileTransaction.ValueWriter writer, YamlFileTransaction.Check check) {

        public EditPlan {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(edits, "edits");
            Objects.requireNonNull(writer, "writer");
        }
    }

    /** Runs one file write off the owner thread; false when the queue is full or the pool has stopped. */
    public interface Worker {

        boolean submit(Runnable task);
    }

    private final Worker worker;
    private final AtomicInteger inFlight = new AtomicInteger();

    public EditorWriteQueue(Worker worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    /**
     * Applies the plan to its file off the owner thread and reports the outcome through {@code ownerHop}; a null
     * reason means the file was replaced.
     *
     * @return false when the write was refused outright, in which case nothing ran and nothing will be reported
     */
    public boolean submit(EditPlan plan, Consumer<Runnable> ownerHop, Consumer<String> completion) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(ownerHop, "ownerHop");
        Objects.requireNonNull(completion, "completion");
        inFlight.incrementAndGet();
        boolean accepted = worker.submit(() -> {
            YamlFileTransaction.Outcome outcome;
            try {
                outcome = YamlFileTransaction.apply(plan.file(), plan.edits(), plan.writer(), plan.check());
            } catch (RuntimeException | LinkageError failure) {
                outcome = YamlFileTransaction.Outcome.refused(String.valueOf(failure.getMessage()));
            } finally {
                inFlight.decrementAndGet();
            }
            String reason = outcome.written() ? null : String.valueOf(outcome.reason());
            try {
                ownerHop.accept(() -> completion.accept(reason));
            } catch (RuntimeException retired) {
                // The player or the plugin is gone: the file is written, and there is no menu left to update.
            }
        });
        if (!accepted) {
            inFlight.decrementAndGet();
            return false;
        }
        return true;
    }

    /** Writes that have been accepted and not finished yet. */
    public int pendingWrites() {
        return inFlight.get();
    }

    /**
     * Waits for the writes that are still in flight, within the given budget.
     *
     * @return true when nothing is in flight any more
     */
    public boolean drain(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        while (inFlight.get() > 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return inFlight.get() == 0;
            }
        }
        return true;
    }
}
