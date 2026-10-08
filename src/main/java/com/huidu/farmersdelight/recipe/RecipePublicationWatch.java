package com.huidu.farmersdelight.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Watches the registration rounds one readiness pass started and reports once every one of them has published.
 *
 *
 * A round's tail is what publishes an owner's new recipe set, so "the pass is published" is only known after the
 * last tail ran. A pass that wants to read the published set — the warm-up and the content summary do — takes a
 * watch before it starts the load and registers its continuation here: a load whose files fit the budget has
 * already published by then and the continuation runs in the same tick, a sharded load runs it on the tick that
 * finished the last round, and a load that failed or that a newer pass replaced never leaves the continuation
 * hanging.
 *
 *
 * Everything here happens on the recipe-state thread: rounds join and settle on it, and the continuation is
 * registered from it, so no state below needs to be concurrent. The continuation runs on whichever thread
 * settled the watch, which is the thread that ran the last publish.
 */
public final class RecipePublicationWatch {

    /** What a settled pass reports: whether its rounds published, and why they did not. */
    public interface Outcome {

        /** True when every round that joined has published, or when the pass started no round at all. */
        boolean published();

        /** The failure that stopped the pass, or null when a newer pass replaced it or it started no round. */
        Throwable failure();
    }

    private record Settled(boolean published, Throwable failure) implements Outcome {
    }

    private static final Outcome PUBLISHED = new Settled(true, null);

    /** A pass that had a round replaced or cancelled did not publish its full set, but nothing failed. */
    private static final Outcome NOT_PUBLISHED = new Settled(false, null);

    private final List<Consumer<Outcome>> listeners = new ArrayList<>(2);
    private int pending;
    private boolean dropped;
    private boolean joining = true;
    private boolean settled;
    private Outcome outcome;

    RecipePublicationWatch() {
    }

    /** One round of this pass joined; it has to publish, fail or be dropped before the pass settles. */
    void join() {
        if (joining && !settled) {
            pending++;
        }
    }

    /** The pass stops taking rounds; every load call has returned. */
    void closeJoining() {
        joining = false;
        settleIfReady();
    }

    /** One round's tail published its owner's new set. */
    void published() {
        finishOne(true, null);
    }

    /** One round failed; the pass reports the failure even if another round is still registering. */
    void failed(Throwable error) {
        finishOne(false, Objects.requireNonNull(error, "error"));
    }

    /** One round was dropped before it published: a newer pass replaced it, or the plugin is stopping. */
    void dropped() {
        if (settled || pending <= 0) {
            return;
        }
        pending--;
        // A dropped round means this pass will not publish its whole set, but it is not a failure: the pass
        // still settles once whatever else it started has finished.
        dropped = true;
        settleIfReady();
    }

    /** Reports the pass once it settles, or immediately when it already has. Runs on the calling thread. */
    public void whenPublished(Consumer<Outcome> listener) {
        Objects.requireNonNull(listener, "listener");
        if (settled) {
            listener.accept(outcome);
            return;
        }
        listeners.add(listener);
        settleIfReady();
    }

    public boolean isSettled() {
        return settled;
    }

    private void finishOne(boolean published, Throwable failure) {
        if (settled || pending <= 0) {
            return;
        }
        pending--;
        if (!published) {
            // A failure or a dropped round settles the pass right away: the continuation has to be told that
            // this pass is not publishing, rather than waiting for rounds that may never run again.
            settle(new Settled(false, failure));
            return;
        }
        settleIfReady();
    }

    private void settleIfReady() {
        if (settled || joining || pending > 0) {
            return;
        }
        settle(dropped ? NOT_PUBLISHED : PUBLISHED);
    }

    private void settle(Outcome settledOutcome) {
        if (settled) {
            return;
        }
        this.settled = true;
        this.outcome = settledOutcome;
        List<Consumer<Outcome>> waiting = List.copyOf(listeners);
        listeners.clear();
        for (Consumer<Outcome> listener : waiting) {
            listener.accept(settledOutcome);
        }
    }
}
