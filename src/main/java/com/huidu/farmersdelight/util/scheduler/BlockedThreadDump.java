package com.huidu.farmersdelight.util.scheduler;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A bounded snapshot of the threads that are still running when a shutdown step times out.
 *
 * A shutdown that waits out its deadline leaves the server stopping with work still in flight, and the only
 * useful evidence is which thread is stuck and where. This captures thread names, their state and the top of
 * each stack once, renders them as text, and writes that text to a sink. Nothing runs on a tick: the report
 * is produced from the timeout branch of a shutdown wait.
 *
 * The snapshot is bounded in both directions - at most MAX_THREADS threads and at most MAX_FRAMES frames per
 * thread - so a server with hundreds of threads cannot turn a stuck shutdown into a huge log file. Threads
 * whose stack mentions this plugin come first, then the threads that are blocked or waiting, because those
 * are the ones that hold a shutdown up.
 */
public final class BlockedThreadDump {

    /** At most this many threads appear in one dump. */
    public static final int MAX_THREADS = 32;

    /** Frames kept per thread by toLogger(Logger); render takes the cap as an argument. */
    public static final int MAX_FRAMES = 8;

    private static final String OWN_PACKAGE = "com.huidu.farmersdelight";

    /** One thread as it appears in a dump: its name, its state and the top of its stack. */
    public record Sample(String name, String state, List<String> frames) {

        public Sample {
            frames = List.copyOf(frames);
        }
    }

    private final Consumer<String> sink;
    private final Supplier<List<Sample>> snapshot;
    private final AtomicBoolean reported = new AtomicBoolean();

    private BlockedThreadDump(Consumer<String> sink, Supplier<List<Sample>> snapshot) {
        this.sink = sink;
        this.snapshot = snapshot;
    }

    /** A dump that writes to a logger, or to nothing when the caller has no logger. */
    public static BlockedThreadDump toLogger(@Nullable Logger logger) {
        if (logger == null) {
            return new BlockedThreadDump(text -> {
            }, BlockedThreadDump::capture);
        }
        return new BlockedThreadDump(logger::warning, BlockedThreadDump::capture);
    }

    /**
     * A dump that writes the rendered text to the given sink and reads its samples from the given supplier,
     * so the report can be exercised without threads or a logger.
     */
    @ApiStatus.Internal
    public static BlockedThreadDump toSink(Consumer<String> sink, Supplier<List<Sample>> snapshot) {
        return new BlockedThreadDump(sink, snapshot);
    }

    /**
     * Renders the snapshot once. The first call reports and returns true; later calls do nothing and return
     * false, so a shutdown that times out in several steps still produces a single dump.
     */
    public boolean report(String reason) {
        if (!reported.compareAndSet(false, true)) {
            return false;
        }
        sink.accept(render(reason, snapshot.get(), MAX_FRAMES));
        return true;
    }

    /** Renders one reason line followed by the snapshot. Pure: same input, same output, input left alone. */
    public static String render(String reason, List<Sample> samples, int maxFrames) {
        StringBuilder out = new StringBuilder(256);
        out.append("Stuck shutdown");
        if (reason != null && !reason.isBlank()) {
            out.append(": ").append(reason.trim());
        }
        out.append(" - ").append(samples == null ? 0 : samples.size()).append(" thread(s) still running");
        if (samples == null || samples.isEmpty()) {
            return out.toString();
        }
        int frames = Math.max(1, maxFrames);
        for (Sample sample : samples) {
            out.append(System.lineSeparator()).append("  ").append(sample.name())
                    .append(" [").append(sample.state()).append(']');
            List<String> stack = sample.frames();
            int shown = Math.min(frames, stack.size());
            for (int index = 0; index < shown; index++) {
                out.append(System.lineSeparator()).append("      at ").append(stack.get(index));
            }
            if (stack.size() > shown) {
                out.append(System.lineSeparator()).append("      ... ").append(stack.size() - shown)
                        .append(" more frame(s)");
            }
        }
        return out.toString();
    }

    /**
     * Snapshots the running threads: those whose stack mentions this plugin first, then the blocked or
     * waiting ones, both in name order and cut off at MAX_THREADS.
     */
    public static List<Sample> capture() {
        List<Sample> own = new ArrayList<>();
        List<Sample> stalled = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            if (thread == null || stack == null) {
                continue;
            }
            List<String> frames = new ArrayList<>(Math.min(stack.length, MAX_FRAMES));
            boolean ours = false;
            for (int index = 0; index < stack.length; index++) {
                StackTraceElement element = stack[index];
                String frame = element.getClassName() + "." + element.getMethodName()
                        + "(" + element.getFileName() + ":" + element.getLineNumber() + ")";
                if (index < MAX_FRAMES) {
                    frames.add(frame);
                }
                if (element.getClassName().startsWith(OWN_PACKAGE)) {
                    ours = true;
                }
            }
            Sample sample = new Sample(thread.getName(), String.valueOf(thread.getState()), frames);
            if (ours) {
                own.add(sample);
            } else if (holdsUpShutdown(thread.getState())) {
                stalled.add(sample);
            }
        }
        Comparator<Sample> byName = Comparator.comparing(Sample::name);
        own.sort(byName);
        stalled.sort(byName);
        List<Sample> result = new ArrayList<>(Math.min(MAX_THREADS, own.size() + stalled.size()));
        for (Sample sample : own) {
            if (result.size() >= MAX_THREADS) {
                break;
            }
            result.add(sample);
        }
        for (Sample sample : stalled) {
            if (result.size() >= MAX_THREADS) {
                break;
            }
            result.add(sample);
        }
        return result;
    }

    private static boolean holdsUpShutdown(Thread.State state) {
        return state == Thread.State.BLOCKED
                || state == Thread.State.WAITING
                || state == Thread.State.TIMED_WAITING;
    }
}
