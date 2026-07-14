package com.huidu.farmersdelight.api.util;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Static registry of addon-provided DebugToolExtensions. Looked up by lowercase target name. */
public final class DebugToolRegistry {

    private static final Map<String, DebugToolExtension> EXTENSIONS = new ConcurrentHashMap<>();

    private DebugToolRegistry() {}

    /** Idempotent — re-registering the same name replaces the previous extension. */
    public static void register(DebugToolExtension extension) {
        if (extension == null || extension.name() == null) return;
        EXTENSIONS.put(extension.name().toLowerCase(Locale.ROOT), extension);
    }

    /** Removes the extension registered under name. Call from your plugin's onDisable so
     *  a stale reference (e.g. a manager that's been torn down) doesn't outlive the plugin lifecycle. */
    public static void unregister(String name) {
        if (name == null) return;
        EXTENSIONS.remove(name.toLowerCase(Locale.ROOT));
    }

    @Nullable
    public static DebugToolExtension find(String name) {
        if (name == null) return null;
        return EXTENSIONS.get(name.toLowerCase(Locale.ROOT));
    }

    public static Collection<String> registeredNames() {
        return Collections.unmodifiableCollection(EXTENSIONS.keySet());
    }

    public static Collection<DebugToolExtension> all() {
        return Collections.unmodifiableCollection(EXTENSIONS.values());
    }
}
