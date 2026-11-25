package com.huidu.farmersdelight;

import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort rollback for a reload pass: every changed field registers how to restore its previous value, and
 * the loaders run as one action. If the action throws, the restores run in reverse order before the failure is
 * rethrown, so the plugin does not keep a half-old/half-new configuration. Side effects that are not simple field
 * assignments (static caches, item-source configuration) still have to be re-run by their owners and are not
 * covered here.
 */
final class ConfigRollback {

    private final List<Runnable> restores = new ArrayList<>();

    void capture(Runnable restore) {
        if (restore != null) {
            restores.add(restore);
        }
    }

    void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            for (int i = restores.size() - 1; i >= 0; i--) {
                try {
                    restores.get(i).run();
                } catch (RuntimeException | LinkageError ignored) {
                    // Keep restoring the remaining fields; the original failure is the one worth reporting.
                }
            }
            throw failure;
        }
    }

    int restoreCount() {
        return restores.size();
    }
}
