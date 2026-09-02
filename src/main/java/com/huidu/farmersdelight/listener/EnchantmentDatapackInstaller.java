package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.enchant.EnchantmentDefinition;
import com.huidu.farmersdelight.api.enchant.FarmersDelightEnchantments;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.DatapackSupport;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnchantmentDatapackInstaller implements Listener {

    private static final String DATAPACK_DIRECTORY = "farmersdelight_enchant";
    private static final String PACK_DESCRIPTION = "FarmersDelight configurable enchantments";

    private static final int MIN_COST_BASE = 15;
    private static final int MIN_COST_PER_LEVEL = 9;
    private static final int MAX_COST_BASE = 50;
    private static final int MAX_COST_PER_LEVEL = 8;
    private static final int ANVIL_COST = 2;
    private static final String FALLBACK_NAME = "Backstabbing";
    private static final List<String> SLOTS = List.of("mainhand");
    private static final String SUPPORTED_ITEMS_TAG = "farmersdelight:enchantable/knife";
    private static final List<String> SUPPORTED_ITEMS = List.of();
    private static final List<String> DISTRIBUTION_TAGS = List.of(
            "tradeable",
            "treasure",
            "on_random_loot"
    );

    private final FarmersDelightPlugin plugin;
    private final boolean installEnabled;

    public EnchantmentDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.installEnabled = plugin.getConfig().getBoolean("enchantments.install-datapack", true);
    }

    // Registry-scoped datapack content (enchantments, enchantment tags) is loaded by the server from
    // the primary world's datapacks folder and shared by every world, so the pack is written once into
    // the primary world. Copies in non-primary worlds are removed.
    public void installToPrimaryWorld(World primaryWorld) {
        if (primaryWorld == null) {
            I18n.logWarning("enchantment_datapack_no_worlds");
            return;
        }
        EnchantmentSettings settings = plugin.getEnchantmentSettings();
        boolean wrote;
        if (shouldInstall(settings)) {
            wrote = installToWorld(primaryWorld, settings);
        } else {
            wrote = deletePack(DatapackSupport.worldRoot(primaryWorld)
                    .resolve("datapacks").resolve(DATAPACK_DIRECTORY));
        }
        int cleaned = cleanupRedundantDatapacks(primaryWorld);
        if (wrote || cleaned > 0) {
            printRestartBanner();
        }
    }

    // Deletes the farmersdelight_enchant datapack from every non-primary world. The server scans only
    // the primary world's datapacks/ folder, so these copies are ignored. Skips the primary world.
    private int cleanupRedundantDatapacks(World primaryWorld) {
        Path primaryDatapack = DatapackSupport.worldRoot(primaryWorld)
                .resolve("datapacks")
                .resolve(DATAPACK_DIRECTORY)
                .toAbsolutePath()
                .normalize();
        String primaryName = primaryWorld.getName();
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            if (primaryName.equals(world.getName())) {
                continue;
            }
            Path redundant = world.getWorldFolder().toPath()
                    .resolve("datapacks")
                    .resolve(DATAPACK_DIRECTORY);
            // Modern dimension folders resolve to the same level.dat root as the primary world.
            // Do not delete the primary pack while iterating those worlds.
            if (DatapackSupport.sameNormalizedPath(primaryDatapack, redundant)) {
                continue;
            }
            try {
                if (Files.exists(redundant)) {
                    DatapackSupport.deleteRecursively(redundant);
                    removed++;
                    I18n.logInfo("enchantment_datapack_redundant_removed", "world", world.getName());
                }
            } catch (IOException e) {
                plugin.getLogger().warning("FarmersDelight enchant datapack: failed to remove redundant folder under "
                        + redundant + ": " + e.getMessage());
            }
        }
        return removed;
    }

    // Startup plugin-enable order is not guaranteed, so a conflicting enchantment plugin that enabled after
    // this one is only visible once the whole server has loaded. Re-run the conflict check here so it stands
    // the enchantment system down even when it lost the boot race.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        plugin.recheckEnchantmentConflict();
    }

    private boolean shouldInstall(EnchantmentSettings settings) {
        if (!installEnabled || !settings.enabled()) {
            return false;
        }
        return isBackstabEnabled(settings) || !FarmersDelightEnchantments.managedDefinitions().isEmpty();
    }

    private boolean isBackstabEnabled(EnchantmentSettings settings) {
        return plugin.isBackstabEnchantmentEnabled() && settings.backstabbing().enabled();
    }

    private boolean installToWorld(World world, EnchantmentSettings settings) {
        Path datapackDir = DatapackSupport.worldRoot(world)
                .resolve("datapacks")
                .resolve(DATAPACK_DIRECTORY);
        try {
            NamespacedId supportedTag = NamespacedId.parse(SUPPORTED_ITEMS_TAG);
            List<GeneratedFile> generated = new ArrayList<>();
            generated.add(new GeneratedFile(datapackDir.resolve("pack.mcmeta"), renderPackMetadata()));
            // Every FarmersDelight-managed enchant shares the one (empty) supported-items tag: the enchant
            // filter is the distributor, so vanilla must never offer them by supported_items.
            generated.add(new GeneratedFile(datapackDir.resolve("data")
                    .resolve(supportedTag.namespace())
                    .resolve("tags")
                    .resolve("item")
                    .resolve(supportedTag.path() + ".json"), renderSupportedItems()));

            // Enchant ids to place into each vanilla distribution tag, accumulated as each enchant is written.
            Map<String, List<String>> distribution = new LinkedHashMap<>();
            for (String tag : DISTRIBUTION_TAGS) {
                distribution.put(tag, new ArrayList<>());
            }

            if (isBackstabEnabled(settings)) {
                generated.add(new GeneratedFile(
                        enchantmentFile(datapackDir, NamespacedId.parse(settings.backstabbing().id())),
                        renderDefinition(settings.backstabbing())));
                // The built-in backstab enchant belongs to all three distribution tags.
                for (String tag : DISTRIBUTION_TAGS) {
                    distribution.get(tag).add(settings.backstabbing().id());
                }
            }
            for (EnchantmentDefinition definition : FarmersDelightEnchantments.managedDefinitions()) {
                generated.add(new GeneratedFile(
                        enchantmentFile(datapackDir, NamespacedId.parse(definition.id())),
                        renderDefinition(definition)));
                if (definition.tradeable()) {
                    distribution.get("tradeable").add(definition.id());
                }
                if (definition.treasure()) {
                    distribution.get("treasure").add(definition.id());
                }
                if (definition.onRandomLoot()) {
                    distribution.get("on_random_loot").add(definition.id());
                }
            }

            for (Map.Entry<String, List<String>> entry : distribution.entrySet()) {
                generated.add(new GeneratedFile(datapackDir.resolve("data")
                        .resolve("minecraft")
                        .resolve("tags")
                        .resolve("enchantment")
                        .resolve(entry.getKey() + ".json"), renderDistributionTag(entry.getValue())));
            }

            int changed = 0;
            for (GeneratedFile file : generated) {
                if (writeIfChanged(file.path(), file.content())) {
                    changed++;
                }
            }
            changed += deleteStaleFiles(datapackDir, generated);
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

    private static Path enchantmentFile(Path datapackDir, NamespacedId id) {
        return datapackDir.resolve("data")
                .resolve(id.namespace())
                .resolve("enchantment")
                .resolve(id.path() + ".json");
    }

    private void printRestartBanner() {
        plugin.getLogger().warning("==================================================================");
        plugin.getLogger().warning(" Updated FarmersDelight enchantment datapack (primary world only).");
        plugin.getLogger().warning(" RESTART the server to apply registry-level enchantment changes.");
        plugin.getLogger().warning("==================================================================");
    }

    static String renderPackMetadata() {
        return DatapackSupport.renderPackMetadata(PACK_DESCRIPTION);
    }

    static String renderDefinition(EnchantmentSettings.Backstabbing backstabbing) {
        NamespacedId id = NamespacedId.parse(backstabbing.id());
        return renderEnchantJson(
                "enchantment." + id.namespace() + "." + id.path(), FALLBACK_NAME,
                backstabbing.definition().weight(), backstabbing.definition().maxLevel(),
                MIN_COST_BASE, MIN_COST_PER_LEVEL, MAX_COST_BASE, MAX_COST_PER_LEVEL, ANVIL_COST, SLOTS);
    }

    static String renderDefinition(EnchantmentDefinition definition) {
        return renderEnchantJson(
                definition.translationKey(), definition.fallbackName(),
                definition.weight(), definition.maxLevel(),
                definition.minCostBase(), definition.minCostPerLevel(),
                definition.maxCostBase(), definition.maxCostPerLevel(),
                definition.anvilCost(), definition.slots());
    }

    // All FarmersDelight-managed enchants (built-in backstab + API-registered) share the one empty
    // supported-items tag, since the enchant filter — not vanilla supported_items — distributes them.
    private static String renderEnchantJson(String translationKey, String fallbackName, int weight, int maxLevel,
                                            int minCostBase, int minCostPerLevel, int maxCostBase,
                                            int maxCostPerLevel, int anvilCost, List<String> slots) {
        StringBuilder slotsJson = new StringBuilder();
        for (String slot : slots) {
            if (!slotsJson.isEmpty()) {
                slotsJson.append(", ");
            }
            slotsJson.append('"').append(json(slot.toLowerCase(Locale.ROOT))).append('"');
        }
        return "{\n"
                + "  \"description\": {\n"
                + "    \"translate\": \"" + json(translationKey) + "\",\n"
                + "    \"fallback\": \"" + json(fallbackName) + "\"\n"
                + "  },\n"
                + "  \"supported_items\": \"#" + json(SUPPORTED_ITEMS_TAG) + "\",\n"
                + "  \"weight\": " + weight + ",\n"
                + "  \"max_level\": " + maxLevel + ",\n"
                + "  \"min_cost\": {\"base\": " + minCostBase
                + ", \"per_level_above_first\": " + minCostPerLevel + "},\n"
                + "  \"max_cost\": {\"base\": " + maxCostBase
                + ", \"per_level_above_first\": " + maxCostPerLevel + "},\n"
                + "  \"anvil_cost\": " + anvilCost + ",\n"
                + "  \"slots\": [" + slotsJson + "],\n"
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

    static String renderDistributionTag(List<String> enchantmentIds) {
        StringBuilder values = new StringBuilder();
        for (String id : enchantmentIds) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append('"').append(json(id)).append('"');
        }
        return "{\n"
                + "  \"replace\": false,\n"
                + "  \"values\": [" + values + "]\n"
                + "}\n";
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

    private boolean deletePack(Path datapackDir) {
        try {
            if (!Files.exists(datapackDir)) {
                return false;
            }
            DatapackSupport.deleteRecursively(datapackDir);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to remove enchantment datapack: " + exception.getMessage());
            return false;
        }
    }

    private static int deleteStaleFiles(Path root, List<GeneratedFile> generated) throws IOException {
        java.util.Set<Path> expected = new java.util.HashSet<>();
        for (GeneratedFile file : generated) {
            expected.add(file.path().toAbsolutePath().normalize());
        }
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) {
            return 0;
        }
        int removed = 0;
        try (java.util.stream.Stream<Path> paths = Files.walk(data)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                if (!expected.contains(path.toAbsolutePath().normalize())) {
                    Files.deleteIfExists(path);
                    removed++;
                }
            }
        }
        return removed;
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
