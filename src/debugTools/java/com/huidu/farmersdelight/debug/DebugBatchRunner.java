package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.TickBudget;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
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
            job.cursor = processSlice((int) cursor, job.count, job.action);
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

    // One slice takes at most this many entries, and gives up earlier after 2 ms: a single world operation
    // cannot be interrupted safely, so the time check only runs between two of them.
    static final int SLICE_BUDGET = 16;

    static int processSlice(int cursor, int count, IntConsumer action) {
        long started = System.nanoTime();
        int end = TickBudget.at(cursor, count, SLICE_BUDGET).sliceEnd();
        while (cursor < end) {
            action.accept(cursor++);
            if (System.nanoTime() - started >= 2_000_000L) break;
        }
        return cursor;
    }

    private static final class Job {
        final UUID owner;
        final int count;
        final IntConsumer action;
        final Runnable finished;
        long cursor;

        Job(UUID owner, int count, IntConsumer action, Runnable finished) {
            this.owner = owner;
            this.count = count;
            this.action = action;
            this.finished = finished;
        }
    }
}
