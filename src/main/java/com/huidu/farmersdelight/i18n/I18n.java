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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class I18n {

    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}(_[a-z]{2})?$");
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final String FALLBACK_LOCALE = "zh_cn";
    
    private static FarmersDelightPlugin plugin;
    private static String defaultLocale = FALLBACK_LOCALE;
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

        defaultLocale = selectServerLocale();

        currentLocale = locales.get(defaultLocale);
        if (currentLocale == null) {
            currentLocale = locales.get(FALLBACK_LOCALE);
            if (currentLocale == null && !locales.isEmpty()) {
                currentLocale = locales.values().iterator().next();
            }
        }

        logInfo("i18n.loaded", "count", locales.size(), "locale", defaultLocale);
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
                    logInfo("i18n.saved_default", "locale", lang);
                    continue;
                }

                if (shouldRestoreBundledLanguage(langFile.toPath())) {
                    backupBrokenLanguage(langFile.toPath());
                    writeBundledLanguage(langFile.toPath(), lang);
                    logWarning("i18n.corrupted_restored", "locale", lang);
                } else {
                    mergeMissingBundledLanguageKeys(langFile.toPath(), lang);
                }
            } catch (IOException e) {
                logWarning("i18n.save_failed", "locale", lang, "error", e.getMessage());
            }
        }
    }

    private static void mergeMissingBundledLanguageKeys(Path langFile, String lang) throws IOException {
        try (InputStream stream = plugin.getResource("lang/" + lang + ".yml")) {
            if (stream == null) {
                return;
            }

            YamlConfiguration bundled = new YamlConfiguration();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                bundled.load(reader);
            }

            YamlConfiguration existing = loadYamlUtf8(langFile.toFile());
            if (existing == null) {
                return;
            }

            boolean changed = false;
            for (String key : bundled.getKeys(true)) {
                if (bundled.isConfigurationSection(key)) {
                    continue;
                }
                if (!existing.contains(key)) {
                    existing.set(key, bundled.get(key));
                    changed = true;
                }
            }

            if (changed) {
                Files.writeString(langFile, existing.saveToString(), StandardCharsets.UTF_8);
                logInfo("i18n.merged_missing", "locale", lang);
            }
        } catch (Exception e) {
            logWarning("i18n.merge_failed", "locale", lang, "error", e.getMessage());
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
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String backupName = langFile.getFileName() + "." + timestamp + ".bak";
        Files.copy(langFile, langFile.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeBundledLanguage(Path langFile, String lang) throws IOException {
        try (InputStream stream = plugin.getResource("lang/" + lang + ".yml")) {
            if (stream == null) {
                logWarning("i18n.resource_missing", "locale", lang);
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
            logWarning("i18n.load_failed_utf8", "file", file.getName(), "error", e.getMessage());
            return null;
        }
    }

    public static void reload() {
        loadLocales();
    }

    private static String selectServerLocale() {
        String configured = normalizeLocale(plugin.getConfig().getString("language", ""), true);
        if (configured != null) {
            String installed = matchInstalledLocale(configured);
            if (installed != null) {
                return installed;
            }
            logWarning("i18n.configured_missing", "locale", configured);
        }

        String craftEngineLocale = selectCraftEngineLocale();
        if (craftEngineLocale != null) {
            String installed = matchInstalledLocale(craftEngineLocale);
            if (installed != null) {
                return installed;
            }
        }

        Locale systemLocale = Locale.getDefault();
        String fullLocale = normalizeLocale(systemLocale.toString(), false);
        if (fullLocale != null) {
            String installed = matchInstalledLocale(fullLocale);
            if (installed != null) {
                return installed;
            }
        }

        String languageOnly = normalizeLocale(systemLocale.getLanguage(), false);
        String matched = matchInstalledLocale(languageOnly);
        if (matched != null) {
            return matched;
        }

        if (!locales.containsKey(FALLBACK_LOCALE)) {
            logWarning("i18n.fallback_missing", "locale", FALLBACK_LOCALE);
            return locales.isEmpty() ? FALLBACK_LOCALE : locales.keySet().iterator().next();
        }
        logWarning("i18n.jvm_locale_missing", "locale", systemLocale, "fallback", FALLBACK_LOCALE);
        return FALLBACK_LOCALE;
    }

    private static String normalizeLocale(String locale) {
        return normalizeLocale(locale, true);
    }

    private static String normalizeLocale(String locale, boolean warn) {
        if (locale == null || locale.isBlank()) {
            return null;
        }
        String normalized = locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        if (!LOCALE_PATTERN.matcher(normalized).matches()) {
            if (warn) {
                logWarning("i18n.invalid_code", "locale", locale);
            }
            return null;
        }
        return normalized;
    }

    private static String matchInstalledLocale(String locale) {
        String normalized = normalizeLocale(locale, false);
        if (normalized == null) {
            return null;
        }
        if (locales.containsKey(normalized)) {
            return normalized;
        }
        int separator = normalized.indexOf('_');
        String language = separator >= 0 ? normalized.substring(0, separator) : normalized;
        return locales.keySet().stream()
                .filter(installed -> installed.equals(language) || installed.startsWith(language + "_"))
                .findFirst()
                .orElse(null);
    }

    private static String selectCraftEngineLocale() {
        try {
            Class<?> managerClass = Class.forName("net.momirealms.craftengine.core.plugin.locale.TranslationManager");
            Object manager = managerClass.getMethod("instance").invoke(null);
            Locale locale = readCraftEngineSelectedLocale(manager);
            return locale != null ? normalizeLocale(formatLocale(locale), false) : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Locale readCraftEngineSelectedLocale(Object manager) {
        if (manager == null) {
            return null;
        }
        try {
            Field selectedLocale = manager.getClass().getDeclaredField("selectedLocale");
            selectedLocale.setAccessible(true);
            Object value = selectedLocale.get(manager);
            if (value instanceof Locale locale) {
                return locale;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        try {
            Class<?> configClass = Class.forName("net.momirealms.craftengine.core.plugin.config.Config");
            Method forcedLocale = configClass.getMethod("forcedLocale");
            Object value = forcedLocale.invoke(null);
            if (value instanceof Locale locale) {
                return locale;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return null;
    }

    private static String formatLocale(Locale locale) {
        if (locale == null) {
            return null;
        }
        String language = locale.getLanguage();
        String country = locale.getCountry();
        if (language == null || language.isBlank()) {
            return null;
        }
        return country == null || country.isBlank() ? language : language + "_" + country;
    }

    public static String get(String key) {
        if (key == null) return "";
        return get(key, defaultLocale);
    }

    public static String getDefaultLocale() {
        return defaultLocale;
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

    public static String formatNamedArgs(String key, Object... args) {
        return replacePlaceholderArgs(get(key), args);
    }

    public static String formatConsole(String key, Object... args) {
        return formatNamedArgs(key.startsWith("console.") ? key : "console." + key, args);
    }

    public static void logInfo(String key, Object... args) {
        Logger logger = plugin != null ? plugin.getLogger() : null;
        if (logger != null) {
            logger.info(formatConsole(key, args));
        }
    }

    public static void logWarning(String key, Object... args) {
        Logger logger = plugin != null ? plugin.getLogger() : null;
        if (logger != null) {
            logger.warning(formatConsole(key, args));
        }
    }

    public static void logSevere(String key, Object... args) {
        Logger logger = plugin != null ? plugin.getLogger() : null;
        if (logger != null) {
            logger.severe(formatConsole(key, args));
        }
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

    private static String replacePlaceholderArgs(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        for (int i = 0; i + 1 < args.length; i += 2) {
            Object key = args[i];
            Object value = args[i + 1];
            if (key != null) {
                message = message.replace("{" + key + "}", String.valueOf(value));
            }
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
        defaultLocale = FALLBACK_LOCALE;
        locales.clear();
        currentLocale = null;
    }
}

