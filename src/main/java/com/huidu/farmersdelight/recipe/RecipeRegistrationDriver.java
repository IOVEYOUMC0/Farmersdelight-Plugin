package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.scheduler.PluginTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Drives the recipe registration rounds of this plugin.
 *
 *
 * A reload used to register every entry of a recipe file in a single tick, which is the spike behind the
 * reported TPS dip. Each round runs its first slice inline, on the calling thread — so a file no bigger than
 * the budget behaves exactly as before, with no tick of delay — and arms a repeating task for the remainder,
 * at most budget entries per tick. On Folia that task is the global region scheduler while the published sets
 * are read from region threads, which is why a round publishes only complete, immutable snapshots. Nothing
 * here is asynchronous and nothing here hands work to another thread.
 *
 *
 * Every source of one owner's reload (its own file plus one source per CraftEngine pack section) goes into
 * that owner's round, so exhausting one source hands the remaining budget to the next instead of cancelling
 * it. The owner is matched by identity and a new round for the same owner replaces that owner's previous one,
 * which keeps the single-active rule per owner: a reload that arrives mid-round never double-registers.
 * Different owners reloading in the same pass — the cooking pot and the cutting board are rebuilt back to back
 * by one reload — keep their own rounds and their own tails, and one armed task drains all of them.
 */
public final class RecipeRegistrationDriver {

    /** Schedules the per-tick slice; production passes the plugin's scheduler, tests pass a fake. */
    public interface TaskArm {

        PluginTask arm(Runnable tick);
    }

    /** One owner's round, the tail that runs once that owner's last entry went through, and its watcher. */
    private record OwnerRound(Object owner, RecipeRegistrationRound round, Runnable onDone,
                              RecipePublicationWatch watch) {
    }

    private final TaskArm taskArm;
    // Copy-on-write: rounds are added, replaced and retired on the recipe-state thread, while isRunning and
    // progress are read from the reload command thread.
    private volatile List<OwnerRound> rounds = List.of();
    private volatile PluginTask task = PluginTask.NOOP;
    // The pass a load call belongs to, while that call is running: the rounds it starts join this watch. Only
    // touched on the recipe-state thread, the same thread the rounds and their tails run on.
    private RecipePublicationWatch openWatch;

    public RecipeRegistrationDriver(TaskArm taskArm) {
        this.taskArm = Objects.requireNonNull(taskArm, "taskArm");
    }

    /** Whether any owner is still registering entries. */
    public boolean isRunning() {
        return !rounds.isEmpty();
    }

    /**
     * Opens the window one readiness pass belongs to: every round started until the window closes joins the
     * returned watch, which reports once all of them have published. A load whose files fit the budget publishes
     * during that very call, so a continuation registered on the watch then runs in the same tick as before.
     */
    public RecipePublicationWatch beginPublicationWatch() {
        RecipePublicationWatch watch = new RecipePublicationWatch();
        this.openWatch = watch;
        return watch;
    }

    /** Closes the window: no further round joins it, and a watch with nothing left to wait for settles. */
    public void endPublicationWatch() {
        RecipePublicationWatch watch = this.openWatch;
        this.openWatch = null;
        if (watch != null) {
            watch.closeJoining();
        }
    }

    /** i/N over the running rounds, or an empty string when there is none. */
    public String progress() {
        List<OwnerRound> current = rounds;
        if (current.isEmpty()) {
            return "";
        }
        int cursor = 0;
        int total = 0;
        for (OwnerRound entry : current) {
            cursor += entry.round().cursor();
            total += entry.round().size();
        }
        return cursor + "/" + total;
    }

    /**
     * Queues every source of one owner's reload as that owner's round, registers up to budget entries now and
     * schedules the rest; onDone runs once, on the thread that finished the round, after the last entry of the
     * last source. The sources are registered in the order they are handed over, and a round that replaces this
     * owner's previous one drops whatever that one had not reached.
     */
    public void start(Object owner, List<RecipeRegistrationRound.Segment> segments, int budget, Runnable onDone) {
        Objects.requireNonNull(owner, "owner");
        RecipeRegistrationRound fresh = new RecipeRegistrationRound(segments, budget);
        RecipePublicationWatch watch = openWatch;
        if (watch != null) {
            // The round joins before the previous one for this owner is dropped, so a replacement never settles
            // the pass before the round that replaces it has had its chance to publish.
            watch.join();
        }
        OwnerRound queued = new OwnerRound(owner, fresh, onDone == null ? () -> { } : onDone, watch);
        this.rounds = queue(owner, queued);
        runInline(fresh);
        if (!fresh.isDone() && this.rounds.contains(queued) && this.task == PluginTask.NOOP) {
            this.task = this.taskArm.arm(this::tick);
        }
    }

    /** Drops every round without registering anything else: plugin disable. */
    public void cancel() {
        dropAll();
    }

    /** Runs the first slice of the round that was just queued, without spending another round's budget. */
    private void runInline(RecipeRegistrationRound fresh) {
        try {
            fresh.run();
        } catch (RuntimeException | Error error) {
            // Loud failure: the round (and every other) is dropped so nothing half-registered keeps running, and
            // every waiting pass is told that this run is not publishing rather than waiting for a later tick.
            failWatches(error);
            dropAll();
            throw error;
        }
        retireFinished();
    }

    private void tick() {
        List<OwnerRound> current = this.rounds;
        if (current.isEmpty()) {
            stopTask();
            return;
        }
        try {
            for (OwnerRound entry : current) {
                entry.round().run();
            }
        } catch (RuntimeException | Error error) {
            failWatches(error);
            dropAll();
            throw error;
        }
        retireFinished();
    }

    /** Tells every pass that still has a round in flight that this run failed and nothing more will publish. */
    private void failWatches(Throwable error) {
        for (OwnerRound entry : this.rounds) {
            if (entry.watch() != null) {
                entry.watch().failed(error);
            }
        }
    }

    /** Retires the rounds that finished, publishing each tail and reporting it to that round's pass. */
    private void retireFinished() {
        List<OwnerRound> current = this.rounds;
        List<OwnerRound> remaining = new ArrayList<>(current.size());
        List<OwnerRound> finished = new ArrayList<>(0);
        for (OwnerRound entry : current) {
            if (entry.round().isDone()) {
                finished.add(entry);
            } else {
                remaining.add(entry);
            }
        }
        if (finished.isEmpty()) {
            return;
        }
        this.rounds = List.copyOf(remaining);
        if (remaining.isEmpty()) {
            stopTask();
        }
        for (OwnerRound entry : finished) {
            try {
                // The tail is the publish: the pass only counts as published after this returns, and a tail
                // that throws settles the pass as failed before the error keeps travelling outwards.
                entry.onDone().run();
            } catch (RuntimeException | Error error) {
                if (entry.watch() != null) {
                    entry.watch().failed(error);
                }
                throw error;
            }
            if (entry.watch() != null) {
                entry.watch().published();
            }
        }
    }

    /** This owner's round replaces its own previous round; other owners' rounds are left alone. */
    private List<OwnerRound> queue(Object owner, OwnerRound queued) {
        List<OwnerRound> current = this.rounds;
        List<OwnerRound> updated = new ArrayList<>(current.size() + 1);
        boolean replaced = false;
        for (OwnerRound entry : current) {
            if (entry.owner() == owner) {
                entry.round().cancel();
                if (entry.watch() != null) {
                    entry.watch().dropped();
                }
                updated.add(queued);
                replaced = true;
            } else {
                updated.add(entry);
            }
        }
        if (!replaced) {
            updated.add(queued);
        }
        return List.copyOf(updated);
    }

    private void dropAll() {
        List<OwnerRound> current = this.rounds;
        this.rounds = List.of();
        for (OwnerRound entry : current) {
            entry.round().cancel();
            if (entry.watch() != null) {
                entry.watch().dropped();
            }
        }
        stopTask();
    }

    private void stopTask() {
        PluginTask running = this.task;
        this.task = PluginTask.NOOP;
        if (running != null && !running.isCancelled()) {
            running.cancel();
        }
    }
}
