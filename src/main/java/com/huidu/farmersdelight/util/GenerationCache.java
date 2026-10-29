package com.huidu.farmersdelight.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Bounded read-mostly cache; invalidation detaches in-flight writes from the current generation. */
final class GenerationCache<K, V> {
    private final int limit;
    private volatile Epoch<K, V> current = new Epoch<>(Long.MIN_VALUE);

    GenerationCache(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
    }

    V get(long version, K key, Function<K, V> loader) {
        Epoch<K, V> epoch = epoch(version);
        // A delayed reader of an older registry must not replace a newer cache generation.
        if (epoch == null) return loader.apply(key);
        V found = epoch.values.get(key);
        if (found != null) return found;
        V loaded = loader.apply(key);
        if (loaded == null) return null;
        synchronized (epoch) {
            found = epoch.values.get(key);
            if (found != null) return found;
            if (epoch.values.size() < limit) epoch.values.put(key, loaded);
        }
        return loaded;
    }

    synchronized void invalidate() {
        current = new Epoch<>(current.version);
    }

    private Epoch<K, V> epoch(long version) {
        Epoch<K, V> epoch = current;
        if (epoch.version == version) return epoch;
        synchronized (this) {
            epoch = current;
            if (version < epoch.version) return null;
            if (version > epoch.version) current = epoch = new Epoch<>(version);
            return epoch;
        }
    }

    private static final class Epoch<K, V> {
        private final long version;
        private final ConcurrentHashMap<K, V> values = new ConcurrentHashMap<>();

        private Epoch(long version) {
            this.version = version;
        }
    }
}
