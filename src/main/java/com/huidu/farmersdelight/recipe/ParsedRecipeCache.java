package com.huidu.farmersdelight.recipe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reuses parsed recipe entries within one content generation.
 *
 *
 * Parsing a recipe reads the file's values and asks CraftEngine about the items they name, so a cached result
 * may only be handed out while both are unchanged. The key therefore carries the content generation, the source
 * the entry came from, the entry's own path and a fingerprint of the entry's values: a reload of CraftEngine's
 * content starts a new generation and a changed entry gets a new fingerprint, and either one means the entry is
 * parsed again rather than reused.
 *
 *
 * The store is bounded: entries are held in insertion order and the oldest is dropped once the limit is
 * reached, so a server that keeps editing recipes cannot grow it without end. Counters report what the store
 * reused, parsed and evicted, which is what a reload report or a debug command needs to explain its cost.
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
     * Returns the parsed entry, parsing it through {@code parser} when this generation does not already hold it.
     *
     * The same inputs always produce the same value: what the parser returned once is what every caller of the
     * same key gets back, until the generation or the entry's own values change.
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
