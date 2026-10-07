package com.huidu.farmersdelight.api.lang;

import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.core.plugin.config.Config;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Language-file manager for addons. Bundled lang files are copied to plugins/<Plugin>/lang/ so
 * operators can edit them; init() merges missing keys and restores corrupted files, reload() re-reads
 * the data folder. The locale follows the addon's config.yml "language" setting; when it is left
 * empty it inherits FarmersDelight's own resolved server locale (I18n.getDefaultLocale()), then
 * CraftEngine's forced locale, then the JVM locale, then a configurable fallback (zh_cn by default).
 * Call init() from onEnable after saveDefaultConfig(), reload() from the addon's reload hook. get()
 * fills {name} placeholders from alternating key/value args.
 */
public final class AddonLanguage {

    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}(_[a-z]{2})?$");
    private static final String ENGLISH = "en_us";
    private static final String[] DEFAULT_LANGUAGES = {"zh_cn", "en_us"};
    private static final Map<String, AddonLanguage> INSTALLED = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;
    private final String keyPrefix;
    private final String fallback;
    private volatile Map<String, YamlConfiguration> locales = Map.of();
    private volatile String selected;

    public AddonLanguage(JavaPlugin plugin, String keyPrefix) {
        this(plugin, keyPrefix, "zh_cn");
    }

    public AddonLanguage(JavaPlugin plugin, String keyPrefix, String fallback) {
        this.plugin = plugin;
        this.keyPrefix = keyPrefix;
        this.fallback = fallback;
    }

    /**
     * Installs an addon's language files under its key prefix and remembers the instance, so an addon does
     * not need a static holder of its own. Installing a prefix again replaces the previous instance, which is
     * what an addon's reload does.
     */
    public static AddonLanguage install(JavaPlugin plugin, String keyPrefix) {
        AddonLanguage created = new AddonLanguage(plugin, keyPrefix);
        created.init();
        return register(keyPrefix, created);
    }

    /**
     * The instance installed under this prefix, or null when nothing installed one. Several addons load one
     * shared copy of this class, so the prefix is what keeps their language files apart.
     */
    public static AddonLanguage of(String keyPrefix) {
        return keyPrefix == null ? null : INSTALLED.get(keyPrefix);
    }

    /** Text of the addon installed under this prefix; the key itself while nothing is installed. */
    public static String text(String keyPrefix, String key, Object... args) {
        AddonLanguage installed = of(keyPrefix);
        return installed == null ? key : installed.get(key, args);
    }

    /** Re-reads the installed addon's language files; a no-op while nothing is installed. */
    public static void reload(String keyPrefix) {
        AddonLanguage installed = of(keyPrefix);
        if (installed != null) {
            installed.reload();
        }
    }

    static AddonLanguage register(String keyPrefix, AddonLanguage language) {
        INSTALLED.put(keyPrefix, language);
        return language;
    }

    static void clearInstalled() {
        INSTALLED.clear();
    }

    /**
     * Ensure the data-folder language files exist and are up to date, then load them and resolve the
     * selected locale.
     */
    public void init() {
        saveDefaultLanguages();
        reload();
    }

    /**
     * Re-read the data-folder language files and re-resolve the selected locale.
     */
    public void reload() {
        Map<String, YamlConfiguration> loaded = new HashMap<>();
        File langFolder = new File(plugin.getDataFolder(), "lang");
        File[] files = langFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                YamlConfiguration cfg = loadYamlUtf8(file);
                if (cfg != null) {
                    loaded.put(file.getName().replace(".yml", "").toLowerCase(Locale.ROOT), cfg);
                }
            }
        }
        String resolved = selectServerLocale(loaded);
        this.locales = Map.copyOf(loaded);
        this.selected = resolved;
    }

    /** The currently selected locale name (e.g. "zh_cn"), after the last init()/reload(). */
    public String selected() {
        return selected;
    }

    /**
     * Resolve a key in the selected locale, falling back to the configured fallback, then en_us, then the
     * raw key. {name} placeholders are filled from alternating key/value args.
     */
    public String get(String key, Object... args) {
        String message = null;
        if (selected != null) {
            YamlConfiguration cfg = locales.get(selected);
            if (cfg != null) {
                message = cfg.getString(key);
            }
        }
        if (message == null) {
            YamlConfiguration fb = locales.get(fallback);
            if (fb != null) {
                message = fb.getString(key);
            }
        }
        if (message == null) {
            YamlConfiguration en = locales.get(ENGLISH);
            if (en != null) {
                message = en.getString(key);
            }
        }
        if (message == null) {
            return key;
        }
        for (int i = 0; i + 1 < args.length; i += 2) {
            Object name = args[i];
            if (name != null) {
                message = message.replace("{" + name + "}", String.valueOf(args[i + 1]));
            }
        }
        return message;
    }

    private void saveDefaultLanguages() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists() && !langFolder.mkdirs()) {
            warning("i18n.save_failed", "error", "cannot create " + langFolder);
            return;
        }
        for (String lang : DEFAULT_LANGUAGES) {
            File langFile = new File(langFolder, lang + ".yml");
            try {
                if (!langFile.exists()) {
                    writeBundledLanguage(langFile.toPath(), lang);
                    info("i18n.saved_default", "locale", lang);
                    continue;
                }
                if (shouldRestoreBundledLanguage(langFile.toPath())) {
                    backupBrokenLanguage(langFile.toPath());
                    writeBundledLanguage(langFile.toPath(), lang);
                    warning("i18n.corrupted_restored", "locale", lang);
                } else {
                    mergeMissingBundledKeys(langFile.toPath(), lang);
                }
            } catch (IOException e) {
                warning("i18n.save_failed", "error", String.valueOf(e.getMessage()));
            }
        }
    }

    private void mergeMissingBundledKeys(Path langFile, String lang) {
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
                if (!bundled.isConfigurationSection(key) && !existing.contains(key)) {
                    existing.set(key, bundled.get(key));
                    changed = true;
                }
            }
            if (changed) {
                Files.writeString(langFile, existing.saveToString(), StandardCharsets.UTF_8);
                info("i18n.merged_missing", "locale", lang);
            }
        } catch (Exception e) {
            warning("i18n.merge_failed", "locale", lang, "error", String.valueOf(e.getMessage()));
        }
    }

    private boolean shouldRestoreBundledLanguage(Path langFile) {
        if (!isYamlReadableUtf8(langFile)) {
            return true;
        }
        try {
            return Files.readString(langFile, StandardCharsets.UTF_8).indexOf('\uFFFD') >= 0;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean isYamlReadableUtf8(Path langFile) {
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
        Files.copy(langFile, langFile.resolveSibling(langFile.getFileName() + "." + timestamp + ".bak"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeBundledLanguage(Path langFile, String lang) throws IOException {
        try (InputStream stream = plugin.getResource("lang/" + lang + ".yml")) {
            if (stream == null) {
                return;
            }
            Path parent = langFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(stream, langFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private YamlConfiguration loadYamlUtf8(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            yaml.load(reader);
            return yaml;
        } catch (Exception e) {
            warning("i18n.load_failed_utf8", "file", file.getName(), "error", String.valueOf(e.getMessage()));
            return null;
        }
    }

    private String selectServerLocale(Map<String, YamlConfiguration> locales) {
        String configured = normalizeLocale(plugin.getConfig().getString("language", ""), true);
        if (configured != null) {
            String installed = matchInstalledLocale(locales, configured);
            if (installed != null) {
                return installed;
            }
            warning("i18n.configured_missing", "locale", configured);
        }
        // When the addon leaves its own language empty, inherit FarmersDelight's resolved server
        // locale so a single config.yml entry drives every addon's console text.
        String farmersDelightLocale = selectFarmersDelightLocale();
        if (farmersDelightLocale != null) {
            String installed = matchInstalledLocale(locales, farmersDelightLocale);
            if (installed != null) {
                return installed;
            }
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

        if (locales.containsKey(fallback)) {
            return fallback;
        }
        warning("i18n.fallback_missing", "locale", fallback);
        return locales.isEmpty() ? fallback : locales.keySet().iterator().next();
    }

    private String selectFarmersDelightLocale() {
        try {
            return normalizeLocale(I18n.getDefaultLocale(), false);
        } catch (RuntimeException | LinkageError ignored) {
            // FarmersDelight not loaded / locale state not initialised yet; fall through to CE then JVM.
            return null;
        }
    }

    private String selectCraftEngineLocale() {
        try {
            Locale locale = Config.forcedLocale();
            if (locale != null) {
                return normalizeLocale(locale.toLanguageTag(), false);
            }
        } catch (LinkageError ignored) {
        }
        return null;
    }

    private String normalizeLocale(String locale, boolean warn) {
        if (locale == null || locale.isBlank()) {
            return null;
        }
        String normalized = locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        if (!LOCALE_PATTERN.matcher(normalized).matches()) {
            if (warn) {
                warning("i18n.invalid_code", "locale", locale);
            }
            return null;
        }
        return normalized;
    }

    private String matchInstalledLocale(Map<String, YamlConfiguration> locales, String locale) {
        String normalized = normalizeLocale(locale, false);
        if (normalized == null) {
            return null;
        }
        if (locales.containsKey(normalized)) {
            return normalized;
        }
        int separator = normalized.indexOf('_');
        String language = separator >= 0 ? normalized.substring(0, separator) : normalized;
        for (String installed : locales.keySet()) {
            if (installed.equals(language) || installed.startsWith(language + "_")) {
                return installed;
            }
        }
        return null;
    }

    private void info(String key, Object... args) {
        log(true, key, args);
    }

    private void warning(String key, Object... args) {
        log(false, key, args);
    }

    private void log(boolean info, String key, Object... args) {
        String message = get(keyPrefix + "." + key, args);
        if (key.startsWith("i18n.") && message.equals(keyPrefix + "." + key)) {
            // The language-file messages themselves are shared by every plugin in the family, so they live in
            // FarmersDelight's own language files; an addon can still override one by defining <prefix>.<key>.
            message = I18n.formatConsole(key, args);
        }
        if (info) {
            plugin.getLogger().info(message);
        } else {
            plugin.getLogger().warning(message);
        }
    }
}
