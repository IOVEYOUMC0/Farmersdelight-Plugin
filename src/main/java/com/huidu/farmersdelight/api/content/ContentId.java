package com.huidu.farmersdelight.api.content;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A CraftEngine-independent namespaced content id (namespace:path).
 *
 * This is the neutral counterpart of CraftEngine's Key: ordinary addon code can pass
 * content ids around, store them in config and compare them without importing CraftEngine. The
 * conversion to a CraftEngine key stays inside FarmersDelight's implementation.
 *
 * Namespaces and paths follow Minecraft resource-location rules: lowercase letters, digits,
 * '_', '-', '.'; paths additionally allow '/'.
 */
@ApiStatus.Experimental
public final class ContentId implements Comparable<ContentId> {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    private final String namespace;
    private final String path;

    private ContentId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    /** Parses a namespace:path string. */
    public static ContentId parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("content id must not be null");
        }
        String trimmed = value.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1 || trimmed.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("content id must be namespace:path, got: " + value);
        }
        return of(trimmed.substring(0, separator), trimmed.substring(separator + 1));
    }

    /** Creates an id from separate namespace and path parts. */
    public static ContentId of(String namespace, String path) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid content namespace: " + namespace);
        }
        if (path == null || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("invalid content path: " + path);
        }
        return new ContentId(namespace, path);
    }

    /** Returns null for null/blank/invalid input; useful when reading optional config values. */
    public static ContentId parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parse(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public String namespace() {
        return this.namespace;
    }

    public String path() {
        return this.path;
    }

    /** The canonical namespace:path representation. */
    public String value() {
        return this.namespace + ":" + this.path;
    }

    @Override
    public int compareTo(ContentId other) {
        return value().compareTo(other.value());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ContentId contentId)) {
            return false;
        }
        return this.namespace.equals(contentId.namespace) && this.path.equals(contentId.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.namespace, this.path);
    }

    @Override
    public String toString() {
        return value();
    }
}
