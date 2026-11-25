package com.huidu.farmersdelight.compat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The placeholder expansion is registered for the plugin's whole lifetime, so a runtime disable must unregister
 * it: a disabled plugin must not keep receiving PAPI callbacks through its closed classloader.
 */
class PlaceholderApiLifecycleTest {

    @Test
    void theExpansionIsUnregisteredOnDisable() throws IOException {
        String plugin = read("src/main/java/com/huidu/farmersdelight/FarmersDelightPlugin.java");
        assertTrue(plugin.contains("placeholderApiHook = hook;"),
                "the hook has to be kept so shutdown can reach it");
        assertTrue(plugin.contains("hook.unregisterExpansion();"),
                "plugin disable must unregister the expansion");

        String hook = read("src/main/java/com/huidu/farmersdelight/compat/PlaceholderApiHook.java");
        assertTrue(hook.contains("void unregisterExpansion()"), "the hook needs an unregister entry point");
        assertTrue(hook.contains("PlaceholderAPI.unregisterExpansion(this)"),
                "unregister must go through PlaceholderAPI instead of dropping the reference silently");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/", ".."}) {
            Path path = Path.of(prefix, relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("source not found: " + relative);
    }
}
