package com.huidu.farmersdelight.api.util;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApiStatus.NonExtendable
public final class DebugToolRegistry {

    private static final Map<String, DebugToolExtension> EXTENSIONS = new ConcurrentHashMap<>();

    private DebugToolRegistry() {}

    public static void register(DebugToolExtension extension) {
        if (extension == null || extension.name() == null) return;
        EXTENSIONS.put(extension.name().toLowerCase(Locale.ROOT), extension);
    }

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
