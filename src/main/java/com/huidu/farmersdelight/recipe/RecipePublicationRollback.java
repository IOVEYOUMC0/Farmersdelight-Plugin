package com.huidu.farmersdelight.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The restore actions of one readiness pass, run when that pass fails instead of publishing.
 *
 *
 * A pass replaces more than one component — the cooking pot set, the cutting board set, and the indexes derived
 * from them — and each of them publishes on its own. Without a common restore point a pass that fails halfway
 * leaves a pot set from the new files next to a cutting board set from the old ones. Whoever starts the pass
 * captures each component's published state first and hands the restores over in publication order; a failure
 * runs them in reverse, so the derived pieces are rebuilt before the sets they read are put back.
 *
 *
 * Restoring is best effort and reported rather than thrown: the caller is already handling the failure that
 * caused it, and one component that cannot be put back must not stop the others from being.
 */
public final class RecipePublicationRollback {

    private final List<Runnable> restores;
    private boolean restored;

    public RecipePublicationRollback(List<Runnable> restores) {
        Objects.requireNonNull(restores, "restores");
        for (Runnable restore : restores) {
            Objects.requireNonNull(restore, "restore");
        }
        this.restores = List.copyOf(restores);
    }

    /**
     * Runs every restore action in reverse publication order, at most once, and reports the ones that failed.
     * A second call does nothing: the pass is already back on its captured state.
     */
    public List<Throwable> restore() {
        if (restored) {
            return List.of();
        }
        restored = true;
        List<Throwable> failures = new ArrayList<>(0);
        for (int index = restores.size() - 1; index >= 0; index--) {
            try {
                restores.get(index).run();
            } catch (RuntimeException | LinkageError failure) {
                failures.add(failure);
            }
        }
        return List.copyOf(failures);
    }

    public boolean isRestored() {
        return restored;
    }
}
