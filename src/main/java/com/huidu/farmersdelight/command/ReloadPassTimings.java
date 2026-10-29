package com.huidu.farmersdelight.command;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The phase figures of one reload pass. Each phase records its own cost, and the totals are derived here so a
 * report can never print a total next to phase figures from a different pass.
 */
public final class ReloadPassTimings {

    private final AtomicLong configNanos = new AtomicLong();
    private final AtomicLong toolsNanos = new AtomicLong();
    private final AtomicLong managersNanos = new AtomicLong();
    private final AtomicLong listenersNanos = new AtomicLong();
    private final AtomicLong addonsNanos = new AtomicLong();
    private final Object lock = new Object();
    /** True while the running pass was started by the reload command, which reports to its own sender. */
    private volatile boolean drivenByCommand;

    /** Drops the previous pass's figures, so nothing can be reported next to a total it does not belong to. */
    public void begin(boolean byCommand) {
        this.drivenByCommand = byCommand;
        synchronized (lock) {
            reset();
        }
    }

    /** The pass is over: the next one is not command-driven until a command starts it again. */
    public void end() {
        this.drivenByCommand = false;
    }

    public void reset() {
        configNanos.set(0L);
        toolsNanos.set(0L);
        managersNanos.set(0L);
        listenersNanos.set(0L);
        addonsNanos.set(0L);
    }

    public void recordPhases(long config, long tools, long managers, long listeners) {
        configNanos.set(config);
        toolsNanos.set(tools);
        managersNanos.set(managers);
        listenersNanos.set(listeners);
    }

    public void recordAddons(long nanos) {
        addonsNanos.set(nanos);
    }

    public boolean drivenByCommand() {
        return drivenByCommand;
    }

    /** The phase figures in the order the report prints them. */
    public long[] phaseNanos() {
        return new long[]{configNanos.get(), toolsNanos.get(), managersNanos.get(), listenersNanos.get(),
                addonsNanos.get()};
    }

    /** Total milliseconds of the pass itself; the addon pass is reported separately. */
    public long totalMillis() {
        return ReloadTiming.totalMillis(configNanos.get() + toolsNanos.get() + managersNanos.get()
                + listenersNanos.get());
    }
}
