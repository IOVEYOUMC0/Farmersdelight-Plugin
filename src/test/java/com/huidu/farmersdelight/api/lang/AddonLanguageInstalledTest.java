package com.huidu.farmersdelight.api.lang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared language holder: the API answers are asserted directly, and the shape that keeps several addons
 * apart is pinned on the source text. The addons load one copy of the api class, so the prefix, not a single
 * static field, is what separates their language files.
 *
 * The installed paths cannot be exercised here: an AddonLanguage needs a plugin, and a paper plugin cannot be
 * constructed without a running server, so the wrapper contracts are pinned on the source of each addon.
 */
class AddonLanguageInstalledTest {

    private static final Path API = Path.of("src", "main", "java", "com", "huidu", "farmersdelight", "api",
            "lang", "AddonLanguage.java");
    private static final Path WORKSPACE = workspace();

    @Test
    void nothingInstalledMeansTheKeyComesBack() {
        assertNull(AddonLanguage.of("no.addon.installed.this"));
        assertNull(AddonLanguage.of(null));
        assertEquals("some.key", AddonLanguage.text("no.addon.installed.this", "some.key"));
        assertEquals("some.key", AddonLanguage.text("no.addon.installed.this", "some.key", "name", "value"));
    }

    @Test
    void reloadingNothingIsANoOp() {
        AddonLanguage.reload("no.addon.installed.this");
        AddonLanguage.reload(null);
    }

    @Test
    void theHolderIsKeyedByPrefixAndNotASingleStaticInstance() throws IOException {
        String api = flat(API);
        assertTrue(api.contains("private static final Map<String, AddonLanguage> INSTALLED = "
                        + "new ConcurrentHashMap<>();"),
                "the holder has to be keyed by the addon's prefix");
        assertTrue(api.contains("public static AddonLanguage install(JavaPlugin plugin, String keyPrefix) {"),
                "install takes the prefix");
        assertTrue(api.contains("return register(keyPrefix, created);"),
                "install stores the instance under that prefix");
        assertTrue(api.contains("public static String text(String keyPrefix, String key, Object... args) { "
                        + "AddonLanguage installed = of(keyPrefix); return installed == null ? key "
                        + ": installed.get(key, args); }"),
                "text has to resolve through the prefix, falling back to the key");
        assertTrue(api.contains("public static void reload(String keyPrefix) { AddonLanguage installed = "
                        + "of(keyPrefix); if (installed != null) { installed.reload(); } }"),
                "reload has to resolve through the prefix too");
        assertFalse(api.contains("private static volatile AddonLanguage "),
                "a single static instance would let one addon overwrite another's language files");
    }

    @Test
    void everyAddonWrapperDelegatesToTheSharedHolder() throws IOException {
        List<Path> wrappers = List.of(
                Path.of("..", "BrewinAndChewin", "src", "main", "java", "com", "huidu", "brewinandchewin",
                        "util", "BrewinLang.java"),
                Path.of("..", "BarbequesDelight", "src", "main", "java", "com", "huidu", "barbequesdelight",
                        "BbqdLang.java"),
                Path.of("..", "EndsDelight", "src", "main", "java", "com", "huidu", "endsdelight",
                        "EndsLang.java"));
        List<String> prefixes = List.of("brewinandchewin", "barbequesdelight", "endsdelight");
        for (int i = 0; i < wrappers.size(); i++) {
            String prefix = prefixes.get(i);
            String wrapper = flat(sibling(wrappers.get(i)));
            assertTrue(wrapper.contains("private static final String KEY_PREFIX = \"" + prefix + "\";"),
                    prefix + " needs its prefix as its only own state");
            assertTrue(wrapper.contains("AddonLanguage.install(plugin, KEY_PREFIX);"),
                    prefix + " has to install through the shared holder");
            assertTrue(wrapper.contains("AddonLanguage.reload(KEY_PREFIX);"),
                    prefix + " has to reload through the shared holder");
            assertTrue(wrapper.contains("return AddonLanguage.text(KEY_PREFIX, key, args);"),
                    prefix + " has to resolve text through the shared holder");
            assertFalse(wrapper.contains("private static volatile AddonLanguage lang;"),
                    prefix + " must not keep its own static instance any more");
            assertFalse(wrapper.contains("new AddonLanguage(plugin,"),
                    prefix + " must not build its own instance any more");
        }
    }

    private static String flat(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").replaceAll("\\s+", " ");
    }

    private static Path sibling(Path path) throws IOException {
        for (Path candidate : List.of(path, WORKSPACE.resolve(path).normalize())) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("missing addon source: " + path + " (working directory "
                + Path.of("").toAbsolutePath() + ")");
    }

    private static Path workspace() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("FarmersDelight"))
                    && Files.isDirectory(cursor.resolve("BarbequesDelight"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return Path.of("").toAbsolutePath();
    }
}
