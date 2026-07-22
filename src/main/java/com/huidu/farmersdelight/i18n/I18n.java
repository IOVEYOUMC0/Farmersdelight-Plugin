package com.huidu.farmersdelight.i18n;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class I18n {

    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}(_[a-z]{2})?$");
    private static final String FALLBACK_LOCALE = "zh_cn";
    
    private static FarmersDelightPlugin plugin;
    private static volatile LocaleState state = new LocaleState(Map.of(), null, FALLBACK_LOCALE);

    /**
     * Immutable snapshot of the locale lookup state: locales, currentLocale, and defaultLocale are always consistent.
     * Reload replaces only the single volatile state reference wholesale, so readers see either the complete old
     * snapshot or the complete new one, avoiding clear()/put() corrupting the underlying HashMap while a region thread reads it
     * (which could cause wrong results, NPEs, or a region thread spinning forever on a corrupted bucket chain).
     */
    private record LocaleState(Map<String, YamlConfiguration> locales,
                               YamlConfiguration currentLocale,
                               String defaultLocale) {
    }

    public static void init(FarmersDelightPlugin pluginInstance) {
        plugin = pluginInstance;
        loadLocales();
    }

    private static void loadLocales() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        saveDefaultLanguages();

        // Build everything in a local map, then publish atomically all at once, never touching the old snapshot being read.
        Map<String, YamlConfiguration> loaded = new HashMap<>();
        File[] langFiles = langFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (langFiles != null) {
            for (File file : langFiles) {
                String localeName = file.getName().replace(".yml", "").toLowerCase();
                YamlConfiguration config = loadYamlUtf8(file);
                if (config != null) {
                    loaded.put(localeName, config);
                }
            }
        }

        String resolvedDefault = selectServerLocale(loaded);

        YamlConfiguration resolvedCurrent = loaded.get(resolvedDefault);
        if (resolvedCurrent == null) {
            resolvedCurrent = loaded.get(FALLBACK_LOCALE);
            if (resolvedCurrent == null && !loaded.isEmpty()) {
                resolvedCurrent = loaded.values().iterator().next();
            }
        }

        state = new LocaleState(Map.copyOf(loaded), resolvedCurrent, resolvedDefault);

        // The locale set is resolved on plugin load, again on enable, and once more on every CraftEngine
        // reload. Only the first resolution, and any later one that actually changes the file count or the
        // selected locale, is worth a console line; the rest go to the startup detail channel.
        String signature = loaded.size() + "/" + resolvedDefault;
        if (signature.equals(lastLoggedLocaleSignature)) {
            logDetail("startup", "i18n.loaded", "count", loaded.size(), "locale", resolvedDefault);
        } else {
            lastLoggedLocaleSignature = signature;
            logInfo("i18n.loaded", "count", loaded.size(), "locale", resolvedDefault);
        }
    }

    // Locale count + selected locale last reported at INFO, so a repeated resolution with an unchanged
    // result stays off the console. Null until the first resolution of this plugin lifecycle.
    private static volatile String lastLoggedLocaleSignature;

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
                ConfigFileUpdater.tidy(existing);
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

    private static String selectServerLocale(Map<String, YamlConfiguration> locales) {
        String configured = normalizeLocale(plugin.getConfig().getString("language", ""), true);
        if (configured != null) {
            String installed = matchInstalledLocale(locales, configured);
            if (installed != null) {
                return installed;
            }
            logWarning("i18n.configured_missing", "locale", configured);
        }

        String craftEngineLocale = selectCraftEngineLocale();
        if (craftEngineLocale != null) {
            String installed = matchInstalledLocale(locales, craftEngineLocale);
            if (installed != null) {
                return installed;
            }
        }

        Locale systemLocale = Locale.getDefault();
        String fullLocale = normalizeLocale(systemLocale.toString(), false);
        if (fullLocale != null) {
            String installed = matchInstalledLocale(locales, fullLocale);
            if (installed != null) {
                return installed;
            }
        }

        String languageOnly = normalizeLocale(systemLocale.getLanguage(), false);
        String matched = matchInstalledLocale(locales, languageOnly);
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

    private static String matchInstalledLocale(Map<String, YamlConfiguration> locales, String locale) {
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
        return get(key, state.defaultLocale());
    }

    public static String getDefaultLocale() {
        return state.defaultLocale();
    }

    public static String get(String key, String locale) {
        if (key == null) return "";
        LocaleState snapshot = state;
        if (locale == null) locale = snapshot.defaultLocale();

        Map<String, YamlConfiguration> locales = snapshot.locales();
        YamlConfiguration currentLocale = snapshot.currentLocale();

        YamlConfiguration lang = locales.get(locale.toLowerCase(Locale.ROOT));
        if (lang == null) {
            // Before fully falling back to the server language, try matching by language prefix (e.g. player en_gb -> installed en_us),
            // so the client still gets its own language.
            String matched = matchInstalledLocale(locales, locale);
            if (matched != null) {
                lang = locales.get(matched);
            }
        }
        if (lang == null) {
            lang = currentLocale;
        }

        if (lang != null) {
            String value = lang.getString(key);
            if (value != null) {
                return value;
            }
        }

        if (!locale.equalsIgnoreCase(snapshot.defaultLocale()) && currentLocale != null) {
            String value = currentLocale.getString(key);
            if (value != null) {
                return value;
            }
        }

        // Last resort: the plugin's own bundled language (this build). Covers a stale or partially-merged
        // deployed lang file, or a lookup before init completes, so a raw key never leaks to the console / UI.
        String bundled = bundledValue(key, locale);
        if (bundled != null) {
            return bundled;
        }

        return key;
    }

    // Jar-bundled language fallback. The jar content is immutable at runtime, so parsed configs are cached for
    // the process lifetime; BUNDLED_ABSENT marks a locale whose bundled file does not exist (cache the miss).
    private static final Map<String, YamlConfiguration> BUNDLED_LANG = new ConcurrentHashMap<>();
    private static final YamlConfiguration BUNDLED_ABSENT = new YamlConfiguration();

    private static String bundledValue(String key, String locale) {
        if (plugin == null || key == null) {
            return null;
        }
        String value = bundledValueForLocale(key, locale);
        if (value == null && !FALLBACK_LOCALE.equalsIgnoreCase(locale)) {
            value = bundledValueForLocale(key, FALLBACK_LOCALE);
        }
        if (value == null && !"en_us".equalsIgnoreCase(locale) && !"en_us".equalsIgnoreCase(FALLBACK_LOCALE)) {
            value = bundledValueForLocale(key, "en_us");
        }
        return value;
    }

    private static String bundledValueForLocale(String key, String locale) {
        if (locale == null || locale.isEmpty()) {
            return null;
        }
        YamlConfiguration cfg = BUNDLED_LANG.computeIfAbsent(locale.toLowerCase(Locale.ROOT), loc -> {
            try (InputStream stream = plugin.getResource("lang/" + loc + ".yml")) {
                if (stream == null) {
                    return BUNDLED_ABSENT;
                }
                YamlConfiguration bundled = new YamlConfiguration();
                try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    bundled.load(reader);
                }
                return bundled;
            } catch (Exception e) {
                return BUNDLED_ABSENT;
            }
        });
        return cfg == BUNDLED_ABSENT ? null : cfg.getString(key);
    }

    public static String get(String key, Player player) {
        if (key == null) return "";
        if (player == null) return get(key);
        String locale = getPlayerLocale(player);
        return get(key, locale);
    }

    private static String getPlayerLocale(Player player) {
        try {
            return player.locale().toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return state.defaultLocale();
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

    /**
     * Startup / reload detail that a healthy boot does not need on the console: per-subsystem census
     * counts, "loaded successfully" notices, and steps that legitimately run more than once per
     * lifecycle. Printed at INFO when the matching debug category is enabled (debug.enabled plus
     * debug.categories in config.yml), and recorded at FINE otherwise so raising the logger level
     * still surfaces it. Nothing routed here is ever dropped.
     */
    public static void logDetail(String category, String key, Object... args) {
        FarmersDelightPlugin pluginInstance = plugin;
        if (pluginInstance == null) {
            return;
        }
        Logger logger = pluginInstance.getLogger();
        if (logger == null) {
            return;
        }
        if (category != null && pluginInstance.isDebugEnabled(category)) {
            logger.info(formatConsole(key, args));
        } else {
            logger.fine(formatConsole(key, args));
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
        return Text.deserialize(get(key));
    }

    public static Component getComponent(String key, Player player) {
        return Text.deserialize(get(key, player));
    }

    public static Component getComponent(String key, Map<String, String> placeholders) {
        return Text.deserialize(formatNamed(key, placeholders));
    }

    public static Component getComponent(String key, Player player, Map<String, String> placeholders) {
        return Text.deserialize(formatNamed(key, player, placeholders));
    }

    /** Returns a Component.translatable(key) that each player's
     * client renders from its own resource-pack lang file. Use this for any user-visible GUI text so the
     * server sends a translation key (not a pre-rendered string) and the player sees their own language
     * without needing to re-open the GUI when they switch client language. The key must exist in the
     * resource pack's assets/farmersdelight/lang/<locale>.json. */
    public static Component translatable(String key) {
        return Component.translatable(key);
    }

    /** Plain-text translation in the server's default locale via com.huidu.farmersdelight.util.ItemUtils#translate.
     *  Walks FD lang files, then CraftEngine's TranslationManager, then Adventure's GlobalTranslator, then
     *  returns the key itself if nothing has a value. Use for bossbar titles and lore lines where the
     *  visible text must NOT depend on the receiving client's locale or resource-pack contents. */
    public static String serverText(String key) {
        return com.huidu.farmersdelight.util.ItemUtils.translate(key, state.defaultLocale());
    }

    /** serverText(String) formatted with positional %s args and wrapped in a
     *  Component.text. Falls back to the unformatted text if the args don't match the placeholders.
     *  Args that are themselves Component get serialized through plain-text first so colored
     *  sub-components are not silently dropped (the result is a flat text Component anyway). */
    public static Component serverComponent(String key, Object... args) {
        String resolved = serverText(key);
        if (args == null || args.length == 0) {
            return Text.deserialize(resolved);
        }
        Object[] flat = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            flat[i] = a instanceof Component c
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c)
                    : String.valueOf(a);
        }
        String formatted;
        try {
            formatted = String.format(resolved, flat);
        } catch (java.util.IllegalFormatException ex) {
            formatted = resolved;
        }
        return Text.deserialize(formatted);
    }

    /** Component.translatable(key, args) carrying a server-resolved .fallback(...) string,
     *  so clients whose resource pack lacks the lang entry see readable text in the server's default locale
     *  instead of the raw key. Non-Component args get wrapped in Component.text(String.valueOf(arg))
     *  for the client-side render; the fallback string formats the %s placeholders with the args'
     *  plain-text serialisation. */
    public static Component translatableWithFallback(String key, Object... args) {
        if (args == null) args = new Object[0];
        Component[] argComponents = new Component[args.length];
        Object[] flat = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Component c) {
                argComponents[i] = c;
                flat[i] = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
            } else {
                String s = String.valueOf(a);
                argComponents[i] = Component.text(s);
                flat[i] = s;
            }
        }
        String template = serverText(key);
        String fallback;
        if (args.length == 0) {
            fallback = template;
        } else {
            try {
                fallback = String.format(template, flat);
            } catch (java.util.IllegalFormatException ex) {
                fallback = template;
            }
        }
        return Component.translatable(key, argComponents).fallback(fallback);
    }

    /** Translatable with positional %s args. Each arg is wrapped in a Component.text(...)
     * unless it's already a Component, so colored sub-components pass through unchanged. Order of args must
     * match the order of %s placeholders in the lang value. */
    public static Component translatable(String key, Object... args) {
        if (args.length == 0) return Component.translatable(key);
        Component[] components = new Component[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            components[i] = a instanceof Component c ? c : Component.text(String.valueOf(a));
        }
        return Component.translatable(key, components);
    }

    public static void cleanup() {
        plugin = null;
        lastLoggedLocaleSignature = null;
        state = new LocaleState(Map.of(), null, FALLBACK_LOCALE);
    }
}

