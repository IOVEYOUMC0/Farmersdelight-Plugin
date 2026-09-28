package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Keeps reloads serialised and spaces them out, mirroring how CraftEngine guards its own reload
 * (ResourceOperationCoordinator: one operation at a time, a second request is refused rather than
 * queued).
 *
 * <p>A full reload is not free: FarmersDelight's own pass is roughly 200 ms of server-thread work, its addons
 * rebuild on the following tick for roughly another 140 ms, and both land on the tick thread. Queueing a
 * second request would therefore stack two stalls back to back, so the later one is refused outright. The
 * cooldown covers the addon cascade, which runs after the command has already returned.
 */
final class ReloadBusyGuard {

    /** Reports a refused reload, with the remaining cooldown in seconds when one applies. */
    interface RejectionSink {
        void reject(Integer remainingSeconds);
    }

    private final LongSupplier cooldownMillis;
    private final LongSupplier clock;
    private final RejectionSink rejection;

    private boolean running;
    private boolean hasFinished;
    private long lastFinishedMillis;

    /**
     * @param cooldownMillis supplies the minimum spacing between two accepted reloads, read per call so an
     *                       edited config takes effect without a reload of its own; 0 still rejects an
     *                       in-flight reload
     * @param clock          supplies monotonic milliseconds, injected so the window is testable
     * @param rejection      notified when a request is refused
     */
    ReloadBusyGuard(LongSupplier cooldownMillis, LongSupplier clock, RejectionSink rejection) {
        this.cooldownMillis = cooldownMillis;
        this.clock = clock;
        this.rejection = rejection;
    }

    /**
     * Remaining cooldown in whole seconds, or null when the guard is open.
     *
     * <p>Split out from begin() so the rounding is testable on its own. Returns null while a
     * reload is still in flight: its cooldown has not started yet.
     */
    static Integer remainingSeconds(long cooldownMillis, long elapsedMillis, boolean running) {
        if (running) {
            return null;
        }
        if (cooldownMillis <= 0 || elapsedMillis >= cooldownMillis) {
            return null;
        }
        long remaining = cooldownMillis - elapsedMillis;
        // Round up so a sub-second remainder is never reported as "0 seconds".
        return (int) ((remaining + 999L) / 1000L);
    }

    /** True when the reload may proceed; otherwise the rejection has already been reported. */
    boolean begin() {
        if (running) {
            rejection.reject(null);
            return false;
        }
        if (hasFinished) {
            Integer remaining = remainingSeconds(
                    Math.max(0L, cooldownMillis.getAsLong()),
                    clock.getAsLong() - lastFinishedMillis,
                    false);
            if (remaining != null) {
                rejection.reject(remaining);
                return false;
            }
        }
        running = true;
        return true;
    }

    /** Releases the guard and starts the cooldown. Call from a finally block so a failure cannot wedge it. */
    void finish() {
        running = false;
        lastFinishedMillis = clock.getAsLong();
        hasFinished = true;
    }

    /** Builds the sink that reports a refusal to sender in their own locale. */
    static RejectionSink messageTo(CommandSender sender) {
        return remainingSeconds -> sender.sendMessage(remainingSeconds == null
                ? I18n.getComponent("command.reload_busy")
                : I18n.getComponent("command.reload_cooldown", Map.of(
                        "seconds", String.valueOf(remainingSeconds))));
    }
}
