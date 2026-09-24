package com.huidu.farmersdelight.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.pack.Pack;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** Loads optional advancement trees shipped inside a CraftEngine pack. */
final class AddonAdvancementPackLoader {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private AddonAdvancementPackLoader() {}

    record Config(String namespace, Path file, YamlConfiguration yaml) {}

    static void load(FarmersDelightPlugin plugin, Consumer<List<Config>> callback) {
        BukkitCraftEngine ce = plugin.getCraftEngine();
        if (ce == null || ce.packManager() == null) {
            callback.accept(List.of());
            return;
        }
        // Capture CE objects on the calling thread. Only immutable paths and YAML parsing leave it.
        // Skip packs turned off with enable: false. Their items are never registered, so an advancement
        // built from one would carry an unresolvable icon and a trigger nothing can satisfy.
        List<Path> roots = ce.packManager().loadedPacks().stream()
                .filter(Pack::enabled)
                .map(Pack::folder)
                .toList();
        plugin.scheduler().runAsync(() -> {
            List<Config> configs = new ArrayList<>();
            for (Path root : roots) {
                scanRoot(plugin, root, configs);
            }
            plugin.scheduler().run(() -> callback.accept(List.copyOf(configs)));
        });
    }

    private static void scanRoot(FarmersDelightPlugin plugin, Path root, List<Config> configs) {
        Path own = root.resolve("advancements.yml");
        try (var stream = Files.list(root)) {
            if (Files.isRegularFile(own)) {
                String namespace = namespace(plugin, root, root.getFileName().toString());
                if (namespace != null) {
                    configs.add(new Config(namespace, own, YamlConfiguration.loadConfiguration(own.toFile())));
                }
            }
            stream.filter(Files::isDirectory).forEach(namespace -> {
                Path file = namespace.resolve("advancements.yml");
                if (Files.isRegularFile(file)) {
                    String id = namespace(plugin, namespace, namespace.getFileName().toString());
                    if (id != null) {
                        configs.add(new Config(id, file, YamlConfiguration.loadConfiguration(file.toFile())));
                    }
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to scan pack advancements from " + root + ": " + e.getMessage());
        }
    }

    private static String namespace(FarmersDelightPlugin plugin, Path packRoot, String fallback) {
        String value = fallback;
        boolean configured = false;
        Path manifest = packRoot.resolve("pack.yml");
        if (Files.isRegularFile(manifest)) {
            String configuredValue = YamlConfiguration.loadConfiguration(manifest.toFile()).getString("namespace");
            if (configuredValue != null && !configuredValue.isBlank()) {
                value = configuredValue.trim();
                configured = true;
            }
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!NAMESPACE.matcher(normalized).matches()) {
            plugin.getLogger().warning("Invalid advancement namespace '" + value + "' in " + packRoot
                    + (configured ? "; pack skipped" : "; pack skipped because its directory name is invalid"));
            return null;
        }
        return normalized;
    }

    static List<AdvancementDef> parse(FarmersDelightPlugin plugin, String namespace, Path file,
                                      YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("advancements");
        if (section == null) {
            plugin.getLogger().warning("Invalid advancement config at " + file + ": missing 'advancements' section");
            return List.of();
        }
        List<AdvancementDef> result = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            String path = "advancements." + id;
            if (s == null) {
                plugin.getLogger().warning("Invalid advancement config at " + file + ": " + path + " must be a section");
                continue;
            }
            if (id.isBlank()) {
                plugin.getLogger().warning("Invalid advancement config at " + file + ": empty advancement id");
                continue;
            }
            warnUnknownKeys(plugin, file, path, s, Set.of("parent", "icon", "title", "description", "frame",
                    "x", "y", "criteria", "required-ids", "criterion-requirements", "background",
                    "show-toast", "announce-chat", "hidden"));
            warnType(plugin, file, path + ".frame", s, "frame", String.class);
            warnType(plugin, file, path + ".x", s, "x", Number.class);
            warnType(plugin, file, path + ".y", s, "y", Number.class);
            warnListType(plugin, file, path + ".criteria", s, "criteria");
            warnListType(plugin, file, path + ".required-ids", s, "required-ids");
            if (s.contains("criterion-requirements")
                    && !(s.get("criterion-requirements") instanceof ConfigurationSection)) {
                plugin.getLogger().warning("Invalid advancement config at " + file + ": " + path
                        + ".criterion-requirements must be a section");
            }
            for (String key : List.of("show-toast", "announce-chat", "hidden")) {
                warnType(plugin, file, path + "." + key, s, key, Boolean.class);
            }
            String frame = s.getString("frame", "task");
            if (frame != null && !Set.of("task", "goal", "challenge").contains(frame.toLowerCase(Locale.ROOT))) {
                plugin.getLogger().warning("Invalid advancement config at " + file + ": " + path
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
                    warnListType(plugin, file, path + ".criterion-requirements." + key, req, key);
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

    private static void warnUnknownKeys(FarmersDelightPlugin plugin, Path file, String path,
                                        ConfigurationSection section, Set<String> allowed) {
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) {
                plugin.getLogger().warning("Invalid advancement config at " + file + ": " + path + "." + key
                        + " is not a recognized field");
            }
        }
    }

    private static void warnType(FarmersDelightPlugin plugin, Path file, String path,
                                 ConfigurationSection section, String key, Class<?> expected) {
        if (!section.contains(key)) return;
        Object value = section.get(key);
        if (value != null && !expected.isInstance(value)) {
            plugin.getLogger().warning("Invalid advancement config at " + file + ": " + path
                    + " must be " + expected.getSimpleName());
        }
    }

    private static void warnListType(FarmersDelightPlugin plugin, Path file, String path,
                                     ConfigurationSection section, String key) {
        if (section.contains(key) && !(section.get(key) instanceof List<?>)) {
            plugin.getLogger().warning("Invalid advancement config at " + file + ": " + path + " must be a list");
        }
    }
}
