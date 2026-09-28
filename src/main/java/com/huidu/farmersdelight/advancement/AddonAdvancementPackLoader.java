package com.huidu.farmersdelight.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Reads the optional advancement trees a CraftEngine pack declares under farmersdelight_advancements. */
final class AddonAdvancementPackLoader {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    private AddonAdvancementPackLoader() {}

    /** One pack section, already bridged by PackSections; source names the pack file. */
    record Config(String namespace, String source, YamlConfiguration yaml) {}

    static void load(FarmersDelightPlugin plugin, Consumer<List<Config>> callback) {
        // CraftEngine read the files while loading packs; only the definition tree is parsed here, and that
        // resolves CraftEngine item ids for the icons, so it stays on the calling (main) thread.
        List<Config> configs = new ArrayList<>();
        for (PackSections.Section section : plugin.packSectionsOf(PackSection.ADVANCEMENTS)) {
            // The namespace is the pack's own, or the suffix of a "farmersdelight_advancements#namespace"
            // key, which keeps the old ability to publish trees for more than one namespace from one pack.
            String namespace = validateNamespace(plugin, section.namespace(), section.source());
            if (namespace != null) {
                configs.add(new Config(namespace, section.source(), section.yaml()));
            }
        }
        callback.accept(List.copyOf(configs));
    }

    private static String validateNamespace(FarmersDelightPlugin plugin, String value, String source) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!NAMESPACE.matcher(normalized).matches()) {
            plugin.getLogger().warning("Invalid advancement namespace '" + value + "' in " + source
                    + "; section skipped");
            return null;
        }
        return normalized;
    }

    static List<AdvancementDef> parse(FarmersDelightPlugin plugin, String namespace, String source,
                                      YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection(PackSection.ADVANCEMENTS.rootKey());
        if (section == null) {
            plugin.getLogger().warning("Invalid advancement config at " + source + ": missing '"
                    + PackSection.ADVANCEMENTS.rootKey() + "' section");
            return List.of();
        }
        List<AdvancementDef> result = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            String path = PackSection.ADVANCEMENTS.rootKey() + "." + id;
            if (s == null) {
                plugin.getLogger().warning("Invalid advancement config at " + source + ": " + path + " must be a section");
                continue;
            }
            if (id.isBlank()) {
                plugin.getLogger().warning("Invalid advancement config at " + source + ": empty advancement id");
                continue;
            }
            warnUnknownKeys(plugin, source, path, s, Set.of("parent", "icon", "title", "description", "frame",
                    "x", "y", "criteria", "required-ids", "criterion-requirements", "background",
                    "show-toast", "announce-chat", "hidden"));
            warnType(plugin, source, path + ".frame", s, "frame", String.class);
            warnType(plugin, source, path + ".x", s, "x", Number.class);
            warnType(plugin, source, path + ".y", s, "y", Number.class);
            warnListType(plugin, source, path + ".criteria", s, "criteria");
            warnListType(plugin, source, path + ".required-ids", s, "required-ids");
            if (s.contains("criterion-requirements")
                    && !(s.get("criterion-requirements") instanceof ConfigurationSection)) {
                plugin.getLogger().warning("Invalid advancement config at " + source + ": " + path
                        + ".criterion-requirements must be a section");
            }
            for (String key : List.of("show-toast", "announce-chat", "hidden")) {
                warnType(plugin, source, path + "." + key, s, key, Boolean.class);
            }
            String frame = s.getString("frame", "task");
            if (frame != null && !Set.of("task", "goal", "challenge").contains(frame.toLowerCase(Locale.ROOT))) {
                plugin.getLogger().warning("Invalid advancement config at " + source + ": " + path
                        + ".frame has unknown value '" + frame + "'");
            }
            String parent = s.getString("parent");
            String iconId = s.getString("icon", namespace + ":" + id);
            ItemStack icon = ItemUtils.createItem(iconId);
            if (icon == null || icon.getType().isAir()) icon = new ItemStack(Material.BOOK);
            String title = s.getString("title", namespace + ".advancement." + id);
            String description = s.getString("description", title + ".desc");
            List<String> criteria = s.getStringList("criteria");
            List<String> required = s.getStringList("required-ids");
            Map<String, List<String>> criterionReq = new LinkedHashMap<>();
            ConfigurationSection req = s.getConfigurationSection("criterion-requirements");
            if (req != null) {
                for (String key : req.getKeys(false)) {
                    warnListType(plugin, source, path + ".criterion-requirements." + key, req, key);
                    criterionReq.put(key, req.getStringList(key));
                }
            }
            result.add(new AdvancementDef(id, parent, icon, title, description,
                    frame, (float) s.getDouble("x", 0), (float) s.getDouble("y", 0), criteria,
                    s.getString("background"), required, criterionReq,
                    s.getBoolean("show-toast", parent != null), s.getBoolean("announce-chat", parent != null),
                    s.getBoolean("hidden", false)));
        }
        return List.copyOf(result);
    }

    private static void warnUnknownKeys(FarmersDelightPlugin plugin, String source, String path,
                                        ConfigurationSection section, Set<String> allowed) {
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) {
                plugin.getLogger().warning("Invalid advancement config at " + source + ": " + path + "." + key
                        + " is not a recognized field");
            }
        }
    }

    private static void warnType(FarmersDelightPlugin plugin, String source, String path,
                                 ConfigurationSection section, String key, Class<?> expected) {
        if (!section.contains(key)) return;
        Object value = section.get(key);
        if (value != null && !expected.isInstance(value)) {
            plugin.getLogger().warning("Invalid advancement config at " + source + ": " + path
                    + " must be " + expected.getSimpleName());
        }
    }

    private static void warnListType(FarmersDelightPlugin plugin, String source, String path,
                                     ConfigurationSection section, String key) {
        if (section.contains(key) && !(section.get(key) instanceof List<?>)) {
            plugin.getLogger().warning("Invalid advancement config at " + source + ": " + path + " must be a list");
        }
    }
}
