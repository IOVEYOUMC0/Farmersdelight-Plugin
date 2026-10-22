package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.util.Key;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Reads CraftEngine's item model obfuscation mappings, which map an authored model id to the
 * obfuscated id the client is sent.
 */
public final class CraftEngineModelMappings {

    private static final String CURRENT_CLASS =
            "net.momirealms.craftengine.core.item.network.ItemModelMappings";
    private static final String LEGACY_CLASS =
            "net.momirealms.craftengine.core.item.processor.ObfuscatedItemModelProcessor";

    private static final Method GET_MAPPINGS = resolve();

    private CraftEngineModelMappings() {
    }

    /** The current mappings, or an empty map when this CraftEngine build stores none. */
    public static Map<Key, Key> get() {
        Method method = GET_MAPPINGS;
        if (method == null) {
            return Map.of();
        }
        try {
            Object mappings = method.invoke(null);
            if (mappings instanceof Map<?, ?> map && !map.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<Key, Key> typed = (Map<Key, Key>) map;
                return typed;
            }
            return Map.of();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Map.of();
        }
    }

    private static Method resolve() {
        Method method = find(CURRENT_CLASS);
        return method != null ? method : find(LEGACY_CLASS);
    }

    private static Method find(String className) {
        try {
            return Class.forName(className).getMethod("getMappings");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
