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

/** Writes the bundled backstab enchantment datapack into each world's datapacks directory */
public final class EnchantmentDatapackInstaller implements Listener {

    /** Hardcoded datapack metadata previously read from the config datapack section */
    private static final String DATAPACK_DIRECTORY = "farmersdelight_enchant";
    private static final int PACK_FORMAT = 48;
    private static final String PACK_DESCRIPTION = "FarmersDelight configurable enchantments";

    /** Backstab definition values, kept in sync with the bundled datapack/enchantment/ */
    private static final int MIN_COST_BASE = 15;
    private static final int MIN_COST_PER_LEVEL = 9;
    private static final int MAX_COST_BASE = 50;
    private static final int MAX_COST_PER_LEVEL = 8;
    private static final int ANVIL_COST = 2;
    private static final String FALLBACK_NAME = "Backstabbing";
    private static final List<String> SLOTS = List.of("mainhand");
    private static final String SUPPORTED_ITEMS_TAG = "farmersdelight:enchantable/knife";
    private static final List<String> SUPPORTED_ITEMS = List.of();

    private final FarmersDelightPlugin plugin;

    public EnchantmentDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** Installs or updates the generated datapack files into every loaded world. */
    public void installToAllWorlds() {
        EnchantmentSettings settings = plugin.getEnchantmentSettings();
        if (!shouldInstall(settings)) {
            return;
        }
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            I18n.logWarning("enchantment_datapack_no_worlds");
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
                && plugin.isBackstabEnchantmentEnabled()
                && settings.backstabbing().enabled();
    }

    private boolean installToWorld(World world, EnchantmentSettings settings) {
        Path datapackDir = getWorldRoot(world)
                .resolve("datapacks")
                .resolve(DATAPACK_DIRECTORY);
        try {
            NamespacedId enchantmentId = NamespacedId.parse(settings.backstabbing().id());
            NamespacedId supportedTag = NamespacedId.parse(SUPPORTED_ITEMS_TAG);

            List<GeneratedFile> generated = List.of(
                    new GeneratedFile(datapackDir.resolve("pack.mcmeta"), renderPackMetadata()),
                    new GeneratedFile(datapackDir.resolve("data")
                            .resolve(enchantmentId.namespace())
                            .resolve("enchantment")
                            .resolve(enchantmentId.path() + ".json"), renderDefinition(settings.backstabbing())),
                    new GeneratedFile(datapackDir.resolve("data")
                            .resolve(supportedTag.namespace())
                            .resolve("tags")
                            .resolve("item")
                            .resolve(supportedTag.path() + ".json"), renderSupportedItems())
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
            I18n.logWarning("enchantment_datapack_install_failed",
                    "world", world.getName(), "error", exception.getMessage());
            return false;
        }
    }

    private void printRestartBanner(int worlds) {
        plugin.getLogger().warning("==================================================================");
        plugin.getLogger().warning(" Updated FarmersDelight enchantment datapack in " + worlds + " world(s).");
        plugin.getLogger().warning(" RESTART the server to apply registry-level enchantment changes.");
        plugin.getLogger().warning("==================================================================");
    }

    static String renderPackMetadata() {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + PACK_FORMAT + ",\n"
                + "    \"description\": \"" + json(PACK_DESCRIPTION) + "\"\n"
                + "  }\n"
                + "}\n";
    }

    static String renderDefinition(EnchantmentSettings.Backstabbing backstabbing) {
        NamespacedId id = NamespacedId.parse(backstabbing.id());
        StringBuilder slots = new StringBuilder();
        for (String slot : SLOTS) {
            if (!slots.isEmpty()) {
                slots.append(", ");
            }
            slots.append('"').append(json(slot.toLowerCase(java.util.Locale.ROOT))).append('"');
        }
        return "{\n"
                + "  \"description\": {\n"
                + "    \"translate\": \"enchantment." + json(id.namespace()) + "." + json(id.path()) + "\",\n"
                + "    \"fallback\": \"" + json(FALLBACK_NAME) + "\"\n"
                + "  },\n"
                + "  \"supported_items\": \"#" + json(SUPPORTED_ITEMS_TAG) + "\",\n"
                + "  \"weight\": " + backstabbing.definition().weight() + ",\n"
                + "  \"max_level\": " + backstabbing.definition().maxLevel() + ",\n"
                + "  \"min_cost\": {\"base\": " + MIN_COST_BASE
                + ", \"per_level_above_first\": " + MIN_COST_PER_LEVEL + "},\n"
                + "  \"max_cost\": {\"base\": " + MAX_COST_BASE
                + ", \"per_level_above_first\": " + MAX_COST_PER_LEVEL + "},\n"
                + "  \"anvil_cost\": " + ANVIL_COST + ",\n"
                + "  \"slots\": [" + slots + "],\n"
                + "  \"effects\": {}\n"
                + "}\n";
    }

    static String renderSupportedItems() {
        StringBuilder values = new StringBuilder();
        for (String item : SUPPORTED_ITEMS) {
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
