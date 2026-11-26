package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Shared thread-safe storage for the public rule registries; not part of the public API. */
final class RuleStore<T> {

    private final Map<ContentId, T> entries = new ConcurrentHashMap<>();
    // Registration order: matching picks the first registered rule, not a hash-map iteration accident.
    private final List<T> ordered = new CopyOnWriteArrayList<>();

    boolean register(ContentId id, T rule) {
        if (id == null || rule == null) {
            return false;
        }
        if (this.entries.putIfAbsent(id, rule) != null) {
            return false;
        }
        this.ordered.add(rule);
        return true;
    }

    boolean unregister(ContentId id) {
        if (id == null) {
            return false;
        }
        T removed = this.entries.remove(id);
        if (removed == null) {
            return false;
        }
        this.ordered.remove(removed);
        return true;
    }

    Optional<T> find(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(this.entries.get(id));
    }

    Set<ContentId> ids() {
        return Collections.unmodifiableSet(Set.copyOf(this.entries.keySet()));
    }

    /** First rule whose predicate accepts the value; a broken rule is skipped rather than aborting the scan. */
    T firstMatching(Predicate<T> predicate) {
        if (predicate == null) {
            return null;
        }
        for (T rule : this.ordered) {
            try {
                if (predicate.test(rule)) {
                    return rule;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // One addon's rule must not break station/crop queries for every other addon.
            }
        }
        return null;
    }

    void clearForTests() {
        this.entries.clear();
        this.ordered.clear();
    }

    int size() {
        return this.entries.size();
    }
}
