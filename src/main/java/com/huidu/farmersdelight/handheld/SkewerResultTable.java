package com.huidu.farmersdelight.handheld;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Configured result overrides for items admitted by the handheld cooking tag. */
public final class SkewerResultTable {

    private final Map<String, String> results;

    public SkewerResultTable(@Nullable Map<String, String> configured) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (configured != null) {
            for (Map.Entry<String, String> entry : configured.entrySet()) {
                String raw = normalize(entry.getKey());
                String cooked = normalize(entry.getValue());
                if (raw == null || cooked == null) {
                    continue;
                }
                copy.putIfAbsent(raw, cooked);
            }
        }
        this.results = copy;
    }

    /** The cooked id for this raw id, or null when this table does not cook it. */
    @Nullable
    public String resolve(@Nullable String rawId) {
        String raw = normalize(rawId);
        return raw == null ? null : results.get(raw);
    }

    /** Whether a raw id can be cooked by this path at all. */
    public boolean cooks(@Nullable String rawId) {
        return resolve(rawId) != null;
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }

    public int size() {
        return results.size();
    }

    @Nullable
    private static String normalize(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
