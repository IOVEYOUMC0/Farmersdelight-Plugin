package com.huidu.farmersdelight.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The restore actions of one readiness pass, run when that pass fails instead of publishing.
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
