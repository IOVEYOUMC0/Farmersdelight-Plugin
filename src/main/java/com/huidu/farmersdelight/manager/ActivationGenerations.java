package com.huidu.farmersdelight.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Numbers the activations of tracked blocks, so a work pass that was already submitted can tell whether the
 * activation it belongs to is still the current one.
 *
 * @param <K> the tracked-block key
 */
final class ActivationGenerations<K> {

    private final Map<K, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    /** Starts a new activation and returns its generation. */
    long activate(K key) {
        long generation = counter.incrementAndGet();
        generations.put(key, generation);
        return generation;
    }

    void deactivate(K key) {
        generations.remove(key);
    }

    /** The current activation's generation, or null when the key is not active. */
    Long current(K key) {
        return generations.get(key);
    }

    /** Whether generation is still the generation of an active key. */
    boolean isCurrent(K key, long generation) {
        Long current = generations.get(key);
        return current != null && current == generation;
    }

    void clear() {
        generations.clear();
    }

    int size() {
        return generations.size();
    }
}
