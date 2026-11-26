package com.huidu.farmersdelight.api.content;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A CraftEngine-independent description of a custom block state: a block id plus optional string
 * properties. It mirrors the state syntax used by CraftEngine content, but deliberately exposes no
 * CraftEngine types.
 *
 * <p>Properties are kept in a case-insensitive map and rendered in sorted order so the canonical
 * {@link #value()} is stable across calls and suitable for config keys and logging.</p>
 */
@ApiStatus.Experimental
public final class BlockStateRef {

    private final ContentId blockId;
    private final Map<String, String> properties;

    private BlockStateRef(ContentId blockId, Map<String, String> properties) {
        this.blockId = blockId;
        this.properties = properties;
    }

    public static BlockStateRef of(ContentId blockId) {
        return of(blockId, Map.of());
    }

    public static BlockStateRef of(ContentId blockId, Map<String, String> properties) {
        Objects.requireNonNull(blockId, "blockId");
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                copy.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue().trim());
            }
        }
        return new BlockStateRef(blockId, Collections.unmodifiableMap(copy));
    }

    /**
     * Parses {@code namespace:path} or {@code namespace:path[key=value,key2=value]}. Malformed
     * properties are rejected rather than silently ignored.
     */
    public static BlockStateRef parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("block state must not be null");
        }
        String trimmed = value.trim();
        int open = trimmed.indexOf('[');
        int close = trimmed.lastIndexOf(']');
        if (open < 0 && close < 0) {
            return of(ContentId.parse(trimmed));
        }
        if (open <= 0 || close != trimmed.length() - 1 || close <= open) {
            throw new IllegalArgumentException("invalid block state: " + value);
        }
        ContentId blockId = ContentId.parse(trimmed.substring(0, open));
        String body = trimmed.substring(open + 1, close).trim();
        if (body.isEmpty()) {
            return of(blockId);
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (String part : body.split(",", -1)) {
            int equals = part.indexOf('=');
            if (equals <= 0 || equals == part.length() - 1) {
                throw new IllegalArgumentException("invalid block state property: " + part);
            }
            String key = part.substring(0, equals).trim();
            String propertyValue = part.substring(equals + 1).trim();
            if (key.isEmpty() || propertyValue.isEmpty()) {
                throw new IllegalArgumentException("invalid block state property: " + part);
            }
            properties.put(key, propertyValue);
        }
        return of(blockId, properties);
    }

    /** Returns null for null/blank/invalid input; useful when reading optional config values. */
    public static BlockStateRef parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parse(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public ContentId blockId() {
        return this.blockId;
    }

    /** Immutable properties; keys are case-insensitive. */
    public Map<String, String> properties() {
        return this.properties;
    }

    /** The canonical {@code namespace:path[key=value,...]} representation. */
    public String value() {
        if (this.properties.isEmpty()) {
            return this.blockId.value();
        }
        StringBuilder builder = new StringBuilder(this.blockId.value()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : this.properties.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return builder.append(']').toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockStateRef state)) {
            return false;
        }
        return this.blockId.equals(state.blockId) && this.properties.equals(state.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.blockId, this.properties);
    }

    @Override
    public String toString() {
        return value();
    }
}
