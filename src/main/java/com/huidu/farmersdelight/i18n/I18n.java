package com.huidu.farmersdelight.i18n;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class I18n {

    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}_[a-z]{2}$");
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    
    private static FarmersDelightPlugin plugin;
    private static String defaultLocale = "zh_cn";
    private static final Map<String, YamlConfiguration> locales = new HashMap<>();
    private static YamlConfiguration currentLocale;

    public static void init(FarmersDelightPlugin pluginInstance) {
        plugin = pluginInstance;
        loadLocales();
    }

    private static void loadLocales() {
        locales.clear();
        
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        saveDefaultLanguages();

        String configLocale = plugin.getConfig().getString("language", "zh_cn").toLowerCase();
        if (!LOCALE_PATTERN.matcher(configLocale).matches()) {
            plugin.getLogger().warning("Invalid language code format: " + configLocale + ", using default zh_cn");
            configLocale = "zh_cn";
        }
        defaultLocale = configLocale;

        File[] langFiles = langFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (langFiles != null) {
            for (File file : langFiles) {
                String localeName = file.getName().replace(".yml", "").toLowerCase();
                YamlConfiguration config = loadYamlUtf8(file);
                if (config != null) {
                    locales.put(localeName, config);
                }
            }
        }

        currentLocale = locales.get(defaultLocale);
        if (currentLocale == null) {
            currentLocale = locales.get("zh_cn");
            if (currentLocale == null && !locales.isEmpty()) {
                currentLocale = locales.values().iterator().next();
            }
        }

        plugin.getLogger().info("Loaded " + locales.size() + " language files, using: " + defaultLocale);
    }

    private static void saveDefaultLanguages() {
        String[] defaultLangs = {"zh_cn", "en_us"};
        
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        
        for (String lang : defaultLangs) {
            File langFile = new File(langFolder, lang + ".yml");
            try {
                if (!langFile.exists()) {
                    writeBundledLanguage(langFile.toPath(), lang);
                    plugin.getLogger().info("Saved default language file: " + lang);
                    continue;
                }

                if (shouldRestoreBundledLanguage(langFile.toPath())) {
                    backupBrokenLanguage(langFile.toPath());
                    writeBundledLanguage(langFile.toPath(), lang);
                    plugin.getLogger().warning("Detected a corrupted language file and restored the bundled UTF-8 default: " + lang);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save language file: " + lang + " - " + e.getMessage());
            }
        }
    }

    private static boolean shouldRestoreBundledLanguage(Path langFile) {
        if (!isYamlReadableUtf8(langFile)) {
            return true;
        }

        try {
            String content = Files.readString(langFile, StandardCharsets.UTF_8);
            return content.indexOf('\uFFFD') >= 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean isYamlReadableUtf8(Path langFile) {
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(langFile), StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void backupBrokenLanguage(Path langFile) throws IOException {
        String backupName = langFile.getFileName() + ".invalid." + System.currentTimeMillis() + ".bak";
        Files.copy(langFile, langFile.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeBundledLanguage(Path langFile, String lang) throws IOException {
        try (InputStream stream = plugin.getResource("lang/" + lang + ".yml")) {
            if (stream == null) {
                plugin.getLogger().warning("Language resource not found in jar: " + lang);
                return;
            }

            Path parent = langFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(stream, langFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static YamlConfiguration loadYamlUtf8(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            yaml.load(reader);
            return yaml;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load language file " + file.getName() + " as UTF-8: " + e.getMessage());
            return null;
        }
    }

    public static void reload() {
        loadLocales();
    }

    public static String get(String key) {
        if (key == null) return "";
        return get(key, defaultLocale);
    }

    public static String get(String key, String locale) {
        if (key == null) return "";
        if (locale == null) locale = defaultLocale;
        
        YamlConfiguration lang = locales.get(locale.toLowerCase());
        if (lang == null) {
            lang = currentLocale;
        }
        
        if (lang != null) {
            String value = lang.getString(key);
            if (value != null) {
                return colorize(value);
            }
        }

        if (!locale.equalsIgnoreCase(defaultLocale) && currentLocale != null) {
            String value = currentLocale.getString(key);
            if (value != null) {
                return colorize(value);
            }
        }

        return key;
    }

    public static String get(String key, Player player) {
        if (key == null) return "";
        if (player == null) return get(key);
        String locale = getPlayerLocale(player);
        return get(key, locale);
    }

    private static String getPlayerLocale(Player player) {
        try {
            return player.locale().toString().toLowerCase();
        } catch (Exception e) {
            return defaultLocale;
        }
    }

    public static String format(String key, Object... args) {
        String message = get(key);
        return String.format(message, args);
    }

    public static String format(String key, String locale, Object... args) {
        String message = get(key, locale);
        return String.format(message, args);
    }

    public static String formatNamed(String key, Map<String, String> placeholders) {
        String message = get(key);
        return replacePlaceholders(message, placeholders);
    }

    public static String formatNamed(String key, String locale, Map<String, String> placeholders) {
        String message = get(key, locale);
        return replacePlaceholders(message, placeholders);
    }

    public static String formatNamed(String key, Player player, Map<String, String> placeholders) {
        String locale = getPlayerLocale(player);
        return formatNamed(key, locale, placeholders);
    }

    private static String replacePlaceholders(String message, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return message;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    public static Component getComponent(String key) {
        return LEGACY_SERIALIZER.deserialize(get(key));
    }

    public static Component getComponent(String key, Player player) {
        return LEGACY_SERIALIZER.deserialize(get(key, player));
    }

    public static Component getComponent(String key, Map<String, String> placeholders) {
        return LEGACY_SERIALIZER.deserialize(formatNamed(key, placeholders));
    }

    public static Component getComponent(String key, Player player, Map<String, String> placeholders) {
        return LEGACY_SERIALIZER.deserialize(formatNamed(key, player, placeholders));
    }

    private static String colorize(String value) {
        return LegacyComponentSerializer.legacySection().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(value)
        );
    }

    public static void cleanup() {
        plugin = null;
        defaultLocale = "zh_cn";
        locales.clear();
        currentLocale = null;
    }
}
