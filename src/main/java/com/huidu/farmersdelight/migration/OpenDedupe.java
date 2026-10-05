package com.huidu.farmersdelight.migration;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Have I already handled this object once?" for the container-open hook.
 *
 *
 * The map is a synchronized WeakHashMap: regions call this concurrently (players in different
 * regions open containers at the same time), and a closed inventory must still be collectable. The
 * compound check-and-record is done under the map's own monitor so two threads cannot both see "first".
 */
final class OpenDedupe {

    private final Map<Object, Boolean> seen = Collections.synchronizedMap(new WeakHashMap<>());

    /** True the first time this key is seen, false afterwards. A null key is never "first". */
    boolean firstOpen(Object key) {
        if (key == null) {
            return false;
        }
        synchronized (seen) {
            return seen.putIfAbsent(key, Boolean.TRUE) == null;
        }
    }

    /** How many live keys are recorded; used by the concurrency test. */
    int size() {
        synchronized (seen) {
            return seen.size();
        }
    }
}
