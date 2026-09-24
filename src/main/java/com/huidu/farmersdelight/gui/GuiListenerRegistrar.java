package com.huidu.farmersdelight.gui;

import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registers a GUI event listener exactly once per plugin instance. Each GUI owns its own listener class and
 * only needs the "register once, then forget it on disable so a soft re-enable registers again" step; that
 * step had grown into one copy of the same static flag plus synchronized double-check per GUI.
 */
public final class GuiListenerRegistrar {

    private static final Set<Class<?>> REGISTERED = ConcurrentHashMap.newKeySet();

    private GuiListenerRegistrar() {
    }

    /**
     * Registers a fresh listener of the given type, once. Returns true when this call did the registration,
     * and false when it was already registered or no plugin is available yet.
     */
    public static boolean ensureRegistered(Class<?> listenerType, Supplier<Listener> factory, Plugin plugin) {
        if (plugin == null || REGISTERED.contains(listenerType)) {
            return false;
        }
        synchronized (REGISTERED) {
            if (REGISTERED.contains(listenerType)) {
                return false;
            }
            Bukkit.getPluginManager().registerEvents(factory.get(), plugin);
            REGISTERED.add(listenerType);
            return true;
        }
    }

    /**
     * Forgets one listener so a later ensureRegistered registers a new instance. Bukkit removes the
     * listener itself on disable, so without this a soft re-enable would keep the GUI click handlers off.
     */
    public static void reset(Class<?> listenerType) {
        synchronized (REGISTERED) {
            REGISTERED.remove(listenerType);
        }
    }
}
