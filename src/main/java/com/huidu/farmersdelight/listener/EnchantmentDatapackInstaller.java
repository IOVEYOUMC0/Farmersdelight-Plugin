package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Writes the configured backstabbing definition as a normal Minecraft datapack. */
public final class EnchantmentDatapackInstaller implements Listener {

    private final FarmersDelightPlugin plugin;

    public EnchantmentDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** Installs or updates generated files in every loaded world. */
    public void installToAllWorlds() {
        EnchantmentSettings settings = plugin.getEnchantmentSettings();
        if (!shouldInstall(settings)) {
            return;
        }
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            plugin.getLogger().warning("[FarmersDelight] No loaded worlds — enchantment datapack will install when a world loads");
            return;
        }
        int changedWorlds = 0;
        for (World world : worlds) {
            if (installToWorld(world, settings)) {
                changedWorlds++;
            }
        }
        if (changedWorlds > 0) {
            printRestartBanner(changedWorlds);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        EnchantmentSettings settings = plugin.getEnchantmentSettings();
        if (shouldInstall(settings) && installToWorld(event.getWorld(), settings)) {
            printRestartBanner(1);
        }
    }

    private boolean shouldInstall(EnchantmentSettings settings) {
        return settings.enabled()
                && settings.datapack().enabled()
                && plugin.isBackstabEnchantmentEnabled()
                && settings.backstabbing().enabled();
    }

    private boolean installToWorld(World world, EnchantmentSettings settings) {
        Path datapackDir = getWorldRoot(world)
                .resolve("datapacks")
                .resolve(settings.datapack().directory());
        try {
            EnchantmentSettings.Backstabbing backstabbing = settings.backstabbing();
            EnchantmentSettings.Backstabbing.Definition definition = backstabbing.definition();
            NamespacedId enchantmentId = NamespacedId.parse(backstabbing.id());
            NamespacedId supportedTag = NamespacedId.parse(definition.supportedItemsTag());

            List<GeneratedFile> generated = List.of(
                    new GeneratedFile(datapackDir.resolve("pack.mcmeta"), renderPackMetadata(settings.datapack())),
                    new GeneratedFile(datapackDir.resolve("data")
                            .resolve(enchantmentId.namespace())
                            .resolve("enchantment")
                            .resolve(enchantmentId.path() + ".json"), renderDefinition(backstabbing)),
                    new GeneratedFile(datapackDir.resolve("data")
                            .resolve(supportedTag.namespace())
                            .resolve("tags")
                            .resolve("item")
                            .resolve(supportedTag.path() + ".json"), renderSupportedItems(definition.supportedItems()))
            );

            int changed = 0;
            for (GeneratedFile file : generated) {
                if (writeIfChanged(file.path(), file.content())) {
                    changed++;
                }
            }
            if (changed > 0) {
                I18n.logDetail("startup", "plugin.enchantment_datapack_written",
                        "count", changed, "dir", datapackDir);
            }
            return changed > 0;
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "[FarmersDelight] Enchantment datapack install FAILED (world=" + world.getName()
                            + "): " + exception.getMessage(), exception);
            return false;
        }
    }

    private void printRestartBanner(int worlds) {
        plugin.getLogger().warning("==================================================================");
        plugin.getLogger().warning(" Updated FarmersDelight enchantment datapack in " + worlds + " world(s).");
        plugin.getLogger().warning(" RESTART the server to apply registry-level enchantment changes.");
        plugin.getLogger().warning("==================================================================");
    }

    static String renderPackMetadata(EnchantmentSettings.Datapack datapack) {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + datapack.packFormat() + ",\n"
                + "    \"description\": \"" + json(datapack.description()) + "\"\n"
                + "  }\n"
                + "}\n";
    }

    static String renderDefinition(EnchantmentSettings.Backstabbing backstabbing) {
        EnchantmentSettings.Backstabbing.Definition definition = backstabbing.definition();
        NamespacedId id = NamespacedId.parse(backstabbing.id());
        StringBuilder slots = new StringBuilder();
        for (String slot : definition.slots()) {
            if (!slots.isEmpty()) {
                slots.append(", ");
            }
            slots.append('"').append(json(slot.toLowerCase(java.util.Locale.ROOT))).append('"');
        }
        return "{\n"
                + "  \"description\": {\n"
                + "    \"translate\": \"enchantment." + json(id.namespace()) + "." + json(id.path()) + "\",\n"
                + "    \"fallback\": \"" + json(definition.fallbackName()) + "\"\n"
                + "  },\n"
                + "  \"supported_items\": \"#" + json(definition.supportedItemsTag()) + "\",\n"
                + "  \"weight\": " + definition.weight() + ",\n"
                + "  \"max_level\": " + definition.maxLevel() + ",\n"
                + "  \"min_cost\": {\"base\": " + definition.minCostBase()
                + ", \"per_level_above_first\": " + definition.minCostPerLevel() + "},\n"
                + "  \"max_cost\": {\"base\": " + definition.maxCostBase()
                + ", \"per_level_above_first\": " + definition.maxCostPerLevel() + "},\n"
                + "  \"anvil_cost\": " + definition.anvilCost() + ",\n"
                + "  \"slots\": [" + slots + "],\n"
                + "  \"effects\": {}\n"
                + "}\n";
    }

    static String renderSupportedItems(List<String> items) {
        StringBuilder values = new StringBuilder();
        for (String item : items) {
            if (!values.isEmpty()) {
                values.append(",\n");
            }
            values.append("    \"").append(json(item)).append('"');
        }
        return "{\n  \"replace\": true,\n  \"values\": ["
                + (values.isEmpty() ? "" : "\n" + values + "\n  ")
                + "]\n}\n";
    }

    private static boolean writeIfChanged(Path destination, String content) throws IOException {
        byte[] desired = content.getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(destination) && java.util.Arrays.equals(Files.readAllBytes(destination), desired)) {
            return false;
        }
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(
                destination.getParent(), destination.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, desired);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value == null ? 0 : value.length() + 8);
        if (value == null) {
            return "";
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static Path getWorldRoot(World world) {
        Path folder = world.getWorldFolder().toPath();
        while (folder != null && !Files.exists(folder.resolve("level.dat"))) {
            folder = folder.getParent();
        }
        return folder != null ? folder : world.getWorldFolder().toPath();
    }

    private record GeneratedFile(Path path, String content) {
    }

    private record NamespacedId(String namespace, String path) {
        static NamespacedId parse(String value) {
            int separator = value.indexOf(':');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Invalid namespaced id: " + value);
            }
            return new NamespacedId(value.substring(0, separator), value.substring(separator + 1));
        }
    }
}
