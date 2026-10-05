package com.huidu.farmersdelight.tool;

import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes minecraft:enchantable in the shape the running server actually accepts.
 *
 *
 * The component's payload is a single positive integer (Paper's Enchantable.value() is an int and its codec is
 * a plain int range), but the value reaches the item through two layers that disagree on the shape: CraftEngine
 * first converts a Java component value into its own component form, and only then does the server's codec read
 * it. So the map form ({"value": n}, the shape CraftEngine's generic conversion expects) is tried first and the
 * bare integer second, and a shape the running versions reject is skipped instead of being written. Writing
 * exactly one shape silently produced a 0 enchantment value — no clickable table options, no preview, no anvil
 * — which is what took so long to find. If both shapes fail, say so out loud exactly once instead of leaving a
 * silent zero behind.
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
