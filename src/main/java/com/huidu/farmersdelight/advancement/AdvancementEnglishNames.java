package com.huidu.farmersdelight.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the English names out of a resource pack's {@code lang/en_us.json}.
 *
 * Used only when the installed UltimateAdvancementAPI cannot render client translation keys: the display then
 * carries plain text, and the English name keeps the advancement page readable instead of showing the raw key.
 *
 * The installed copy under CraftEngine's resource directory wins, so operator edits are honoured; this plugin's own
 * namespace also reads the copy bundled in the jar, which covers a disabled or failed resource extraction. A
 * namespace without a readable file (an addon that ships no resource pack, for example) keeps the key as the name.
 */
final class AdvancementEnglishNames {

    private static final String CRAFTENGINE = "CraftEngine";
    private static final String RESOURCES = "resources";
    private static final String LANG_PATH = "resourcepack/assets/%s/lang/en_us.json";
    private static final String BUNDLED_NAMESPACE = "farmersdelight";
    private static final String BUNDLED_LANG =
            "craftengine/farmersdelight/resourcepack/assets/farmersdelight/lang/en_us.json";

    // One map per namespace, filled on first use: a language file does not change while the server runs.
    private final Map<String, Map<String, String>> namespaces = new ConcurrentHashMap<>();

    /** The English name for a client language key, or the key itself when no pack supplies one. */
    String lookup(Plugin plugin, String namespace, String key) {
        String name = namespaces.computeIfAbsent(namespace, missing -> load(plugin, missing)).get(key);
        return name == null ? key : name;
    }

    private static Map<String, String> load(Plugin plugin, String namespace) {
        Map<String, String> names = new HashMap<>();
        Path installed = installedPath(plugin, namespace);
        if (installed != null && Files.isRegularFile(installed)) {
            try (InputStream stream = Files.newInputStream(installed)) {
                read(stream, names);
            } catch (Exception unreadable) {
                // An unreadable or malformed file is not worth failing a build over: the next source may still work.
            }
        }
        if (names.isEmpty() && BUNDLED_NAMESPACE.equals(namespace) && plugin != null) {
            try (InputStream stream = plugin.getResource(BUNDLED_LANG)) {
                if (stream != null) {
                    read(stream, names);
                }
            } catch (Exception ignored) {
                // No bundled copy (repackaged jar): keep the keys.
            }
        }
        return names;
    }

    private static Path installedPath(Plugin plugin, String namespace) {
        if (plugin == null || plugin.getDataFolder() == null) {
            return null;
        }
        Path pluginsFolder = plugin.getDataFolder().toPath().getParent();
        if (pluginsFolder == null) {
            return null;
        }
        return pluginsFolder.resolve(CRAFTENGINE)
                .resolve(RESOURCES)
                .resolve(namespace)
                .resolve(String.format(Locale.ROOT, LANG_PATH, namespace));
    }

    private static void read(InputStream stream, Map<String, String> names) {
        JsonElement root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                names.put(entry.getKey(), value.getAsString());
            }
        }
    }
}
