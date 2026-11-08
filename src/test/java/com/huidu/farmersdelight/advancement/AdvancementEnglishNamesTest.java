package com.huidu.farmersdelight.advancement;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The English fallback only runs when the installed UltimateAdvancementAPI cannot render components, so its lookup
 * rules are checked here rather than through an advancement build.
 */
class AdvancementEnglishNamesTest {

    private static final String BUNDLED_PATH =
            "craftengine/farmersdelight/resourcepack/assets/farmersdelight/lang/en_us.json";

    @TempDir
    Path pluginsFolder;

    @Test
    void readTheInstalledResourcePack() throws IOException {
        writeLang("crabbersdelight", "{\"crabbersdelight.advancement.root\": \"Crabber's Delight\"}");

        assertEquals("Crabber's Delight", new AdvancementEnglishNames().lookup(plugin(null), "crabbersdelight",
                "crabbersdelight.advancement.root"));
    }

    @Test
    void keepTheKeyWhenNoResourcePackExists() {
        assertEquals("crabbersdelight.advancement.root", new AdvancementEnglishNames().lookup(plugin(null),
                "crabbersdelight", "crabbersdelight.advancement.root"));
    }

    @Test
    void readTheBundledCopyWhenTheInstalledPackIsMissing() {
        String json = "{\"farmersdelight.advancement.master_chef\": \"Master Chef\"}";

        assertEquals("Master Chef", new AdvancementEnglishNames().lookup(plugin(json), "farmersdelight",
                "farmersdelight.advancement.master_chef"));
    }

    @Test
    void preferTheInstalledCopyOverTheBundledOne() throws IOException {
        writeLang("farmersdelight", "{\"farmersdelight.advancement.master_chef\": \"Edited Chef\"}");
        String json = "{\"farmersdelight.advancement.master_chef\": \"Master Chef\"}";

        assertEquals("Edited Chef", new AdvancementEnglishNames().lookup(plugin(json), "farmersdelight",
                        "farmersdelight.advancement.master_chef"),
                "an operator edit in the installed pack wins over the jar copy");
    }

    /** Minimal Plugin answering only the two methods the lookup uses. */
    private Plugin plugin(String bundledJson) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            switch (method.getName()) {
                case "getDataFolder":
                    return new File(pluginsFolder.toFile(), "FarmersDelight");
                case "getResource":
                    // Only the bundled language file resolves; everything else is absent from this fake jar.
                    return BUNDLED_PATH.equals(args[0]) && bundledJson != null
                            ? new ByteArrayInputStream(bundledJson.getBytes(StandardCharsets.UTF_8))
                            : (InputStream) null;
                case "toString":
                    return "english-names-test-plugin";
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        };
        return (Plugin) Proxy.newProxyInstance(
                AdvancementEnglishNamesTest.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }

    private void writeLang(String namespace, String json) throws IOException {
        Path file = pluginsFolder.resolve("CraftEngine")
                .resolve("resources")
                .resolve(namespace)
                .resolve("resourcepack")
                .resolve("assets")
                .resolve(namespace)
                .resolve("lang")
                .resolve("en_us.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
