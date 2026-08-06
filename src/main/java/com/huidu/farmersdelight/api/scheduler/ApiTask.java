package com.huidu.farmersdelight.api.scheduler;

import org.jetbrains.annotations.ApiStatus;

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
