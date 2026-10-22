package com.huidu.farmersdelight.tool;

import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes minecraft:enchantable in the shape the running server actually accepts.
 */
public final class ToolEnchantableComponent {

    /** Applies one candidate value; may throw when the server rejects that shape. */
    public interface ComponentWriter {

        void set(Object value);
    }

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private ToolEnchantableComponent() {
    }

    /**
     * Writes enchantability through writer. Returns whether a shape was accepted; when neither
     * is, warn runs (at most once per process, so a server full of tools logs a single line).
     */
    public static boolean write(int enchantability, ComponentWriter writer, Runnable warn) {
        try {
            writer.set(Map.of("value", enchantability));
            return true;
        } catch (RuntimeException | LinkageError mapRejected) {
            try {
                writer.set(enchantability);
                return true;
            } catch (RuntimeException | LinkageError integerRejected) {
                if (WARNED.compareAndSet(false, true) && warn != null) {
                    warn.run();
                }
                return false;
            }
        }
    }

    /** Forgets the one-shot warning; tests only. */
    @ApiStatus.Internal
    public static void resetWarning() {
        WARNED.set(false);
    }
}
