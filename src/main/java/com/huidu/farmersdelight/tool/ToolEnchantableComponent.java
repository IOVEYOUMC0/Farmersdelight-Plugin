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

    /**
     * Applies one candidate value and reports whether the component is really present afterwards.
     *
     * CraftEngine's setComponentInternal returns without writing when it cannot resolve the component
     * type, and it throws nothing, so "the call returned" is not "the item is enchantable".
     */
    public interface VerifyingWriter extends ComponentWriter {

        boolean applied();
    }

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private ToolEnchantableComponent() {
    }

    /**
     * Writes enchantability through writer. Returns whether a shape was accepted; when neither
     * is, warn runs (at most once per process, so a server full of tools logs a single line).
     */
    public static boolean write(int enchantability, ComponentWriter writer, Runnable warn) {
        boolean verified = writer instanceof VerifyingWriter;
        if (attempt(Map.of("value", enchantability), writer, verified)) {
            return true;
        }
        if (attempt(enchantability, writer, verified)) {
            return true;
        }
        if (WARNED.compareAndSet(false, true) && warn != null) {
            warn.run();
        }
        return false;
    }

    private static boolean attempt(Object value, ComponentWriter writer, boolean verified) {
        try {
            writer.set(value);
        } catch (RuntimeException | LinkageError rejected) {
            return false;
        }
        return !verified || ((VerifyingWriter) writer).applied();
    }

    /** Forgets the one-shot warning; tests only. */
    @ApiStatus.Internal
    public static void resetWarning() {
        WARNED.set(false);
    }
}
