package com.huidu.farmersdelight.util.scheduler;

public interface PluginTask {

    PluginTask NOOP = new PluginTask() {
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
