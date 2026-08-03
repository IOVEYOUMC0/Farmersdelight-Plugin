package com.huidu.farmersdelight.api.scheduler;

import org.jetbrains.annotations.ApiStatus;

/**
 * Addon-facing handle for a scheduled repeating task. Lives in the name-stable api package so
 * addons can hold it across obfuscation (the internal scheduler task type is renamed and must not leak).
 */
@ApiStatus.NonExtendable
public interface ApiTask {

    ApiTask NOOP = new ApiTask() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    void cancel();

    boolean isCancelled();
}
