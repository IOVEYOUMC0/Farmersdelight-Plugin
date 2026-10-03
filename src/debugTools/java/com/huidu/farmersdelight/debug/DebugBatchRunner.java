package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.LongPredicate;
import java.util.logging.Level;

final class DebugBatchRunner {
    private final FarmersDelightPlugin plugin;
    private final AtomicReference<Job> current = new AtomicReference<>();

    DebugBatchRunner(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    boolean busy() { return current.get() != null; }

    boolean start(Player player, int count, IntConsumer action, Runnable finished) {
        Job job = new Job(player.getUniqueId(), count, action, finished);
        if (!current.compareAndSet(null, job)) return false;
        schedule(player, job);
        return true;
    }

    /**
     * Runs step until it returns false (done) rather than until a caller-supplied index is exhausted.
     * The count is only an upper bound on the slices that may be scheduled, so the cost of <em>maintaining</em>
     * the work set — resolving the next candidate, checking it is still editable — is yielded with the work
     * itself. That matters for a cursor over tracked blocks instead of a fixed grid: the existing
     * start(Player, int, IntConsumer, Runnable) would need the whole, possibly large, candidate list
     * materialised synchronously before its first slice.
     */
    boolean start(Player player, int limit, LongPredicate step, Runnable finished) {
        Job job = new Job(player.getUniqueId(), limit, step, finished);
        if (!current.compareAndSet(null, job)) return false;
        schedule(player, job);
        return true;
    }

    boolean stop(Player player) {
        Job job = current.get();
        return job != null && job.owner.equals(player.getUniqueId()) && current.compareAndSet(job, null);
    }

    private void schedule(Player player, Job job) {
        try {
            plugin.scheduler().runLater(() -> plugin.scheduler().runForEntity(player,
                    () -> run(player, job), () -> current.compareAndSet(job, null)), 1L);
        } catch (RuntimeException e) {
            current.compareAndSet(job, null);
            throw e;
        }
    }

    private void run(Player player, Job job) {
        if (current.get() != job) return;
        if (!player.isOnline()) {
            current.compareAndSet(job, null);
            return;
        }
        try {
            long cursor = job.cursor;
            job.cursor = job.action != null
                    ? processSlice((int) cursor, job.count, job.action)
                    : processSlice(cursor, job.count, job.step);
            if (job.cursor < job.count) {
                schedule(player, job);
            } else if (current.compareAndSet(job, null)) {
                job.finished.run();
            }
        } catch (RuntimeException e) {
            current.compareAndSet(job, null);
            plugin.getLogger().log(Level.WARNING, "Debug batch failed", e);
            player.sendMessage(I18n.getComponent("command.debug_batch_failed", player,
                    Map.of("count", String.valueOf(job.cursor))));
        }
    }

    // Yield after 16 operations or 2 ms; a single world operation cannot be interrupted safely.
    static int processSlice(int cursor, int count, IntConsumer action) {
        long started = System.nanoTime();
        int end = Math.min(count, cursor + 16);
        while (cursor < end) {
            action.accept(cursor++);
            if (System.nanoTime() - started >= 2_000_000L) break;
        }
        return cursor;
    }

    // The same slice contract for the predicate form: at most 16 steps or 2 ms, whichever comes first.
    static long processSlice(long cursor, long limit, LongPredicate step) {
        long started = System.nanoTime();
        long end = Math.min(limit, cursor + 16);
        while (cursor < end && step.test(cursor)) {
            cursor++;
            if (System.nanoTime() - started >= 2_000_000L) break;
        }
        return cursor;
    }

    private static final class Job {
        final UUID owner;
        final int count;
        final IntConsumer action;
        final LongPredicate step;
        final Runnable finished;
        long cursor;

        Job(UUID owner, int count, IntConsumer action, Runnable finished) {
            this(owner, count, action, null, finished);
        }

        Job(UUID owner, int count, LongPredicate step, Runnable finished) {
            this(owner, count, null, step, finished);
        }

        private Job(UUID owner, int count, IntConsumer action, LongPredicate step, Runnable finished) {
            this.owner = owner;
            this.count = count;
            this.action = action;
            this.step = step;
            this.finished = finished;
        }
    }
}
