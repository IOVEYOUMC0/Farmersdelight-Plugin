package com.huidu.farmersdelight.recipe;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reuses parsed recipe entries within one content generation.
 */
public final class ParsedRecipeCache<R> {

    /** How many entries one store may hold; the whole loaded recipe set is a few hundred today. */
    public static final int DEFAULT_LIMIT = 4096;

    private record Key(long generation, String source, String path, String fingerprint) {
    }

    private final int limit;
    private final LinkedHashMap<Key, Object> entries = new LinkedHashMap<>();
    private long generation = Long.MIN_VALUE;
    private long reused;
    private long parsed;
    private long evicted;

    public ParsedRecipeCache() {
        this(DEFAULT_LIMIT);
    }

    public ParsedRecipeCache(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("A cache needs room for at least one entry");
        }
        this.limit = limit;
    }

    /**
     * Returns a cached entry or parses it through parser for the current content generation.
     * A generation change or changed entry values invalidates that cached result.
     */
    @SuppressWarnings("unchecked")
    public synchronized R parse(long generation, String source, String path, String fingerprint,
                                Supplier<R> parser) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(parser, "parser");
        if (generation != this.generation) {
            // A new content generation: nothing parsed against the previous one may be reused.
            this.generation = generation;
            this.entries.clear();
        }
        Key key = new Key(generation, source, path, fingerprint);
        Object cached = this.entries.get(key);
        if (cached != null) {
            this.reused++;
            return (R) cached;
        }
        R value = parser.get();
        this.parsed++;
        this.entries.put(key, value);
        while (this.entries.size() > this.limit) {
            Key oldest = this.entries.keySet().iterator().next();
            this.entries.remove(oldest);
            this.evicted++;
        }
        return value;
    }

    public synchronized long generation() {
        return generation == Long.MIN_VALUE ? -1L : generation;
    }

    public synchronized long reused() {
        return reused;
    }

    public synchronized long parsed() {
        return parsed;
    }

    public synchronized long evicted() {
        return evicted;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Drops everything, for a content change that is not a new generation. */
    public synchronized void clear() {
        entries.clear();
    }
}
