package com.huidu.farmersdelight.listener;

/**
 * Defers the datapack "restart to apply" line to the post-startup export, so a startup that installs a pack
 * twice prints the line once. A write before startup completes is remembered and announced by the finish call.
 */
final class DatapackStartupNotice {

    private boolean pending;
    private boolean startupComplete;

    /** Records a changed install; true when the caller should announce it now. */
    boolean record(boolean changed) {
        if (!changed) {
            return false;
        }
        if (startupComplete) {
            return true;
        }
        pending = true;
        return false;
    }

    /** Ends the startup phase; true when a deferred install still has to be announced. */
    boolean finish(boolean changed) {
        startupComplete = true;
        boolean announce = changed || pending;
        pending = false;
        return announce;
    }
}
