package com.huidu.farmersdelight;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Optional CE resource exclusion, resolved once without binding the CE 26.9-only lease type. */
final class CraftEngineResourceLease {
    private static final AutoCloseable NO_OPERATION = () -> { };
    private final MethodHandle acquire;
    private final Class<?> busyException;

    CraftEngineResourceLease(Object engine, Logger logger) {
        MethodHandle candidate = null;
        Class<?> busy = null;
        if (engine != null) {
            try {
                Object coordinator = engine.getClass().getMethod("resourceOperations").invoke(engine);
                var method = coordinator.getClass().getMethod("acquire");
                if (AutoCloseable.class.isAssignableFrom(method.getReturnType())) {
                    candidate = MethodHandles.publicLookup().unreflect(method).bindTo(coordinator);
                    for (Class<?> nested : coordinator.getClass().getClasses()) {
                        if ("BusyException".equals(nested.getSimpleName())) {
                            busy = nested;
                            break;
                        }
                    }
                }
            } catch (NoSuchMethodException unsupported) {
                // shortcut: CE 26.8.2 has no resource exclusion; remove this fallback when its support ends.
            } catch (ReflectiveOperationException | RuntimeException failure) {
                candidate = null;
                busy = null;
                logger.log(Level.WARNING, "CraftEngine resource exclusion unavailable; using readiness checks", failure);
            }
        }
        this.acquire = candidate;
        this.busyException = busy;
    }

    AutoCloseable tryAcquire() {
        if (acquire == null) {
            return NO_OPERATION;
        }
        try {
            return (AutoCloseable) acquire.invoke();
        } catch (Throwable failure) {
            if (busyException != null && busyException.isInstance(failure)) {
                return null;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cannot acquire CraftEngine resource exclusion", failure);
        }
    }
}
