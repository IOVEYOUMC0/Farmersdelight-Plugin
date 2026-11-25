package com.huidu.farmersdelight.shutdown;

/** One ordered teardown action; id is the language key the failure report uses. */
public record TeardownStep(String id, Runnable action) implements Runnable {

    public TeardownStep {
        if (id == null || id.isBlank() || action == null) {
            throw new IllegalArgumentException("teardown step needs an id and an action");
        }
    }

    @Override
    public void run() {
        action.run();
    }
}
