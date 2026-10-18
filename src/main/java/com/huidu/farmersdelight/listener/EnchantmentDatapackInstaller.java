package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.datapack.FarmersDelightDatapacks;
import com.huidu.farmersdelight.api.enchant.EnchantmentDefinition;
import com.huidu.farmersdelight.api.enchant.FarmersDelightEnchantments;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.api.util.DatapackSupport;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.huidu.farmersdelight.api.util.DatapackSupport.writeIfChanged;

public final class EnchantmentDatapackInstaller implements Listener {

    private static final String DATAPACK_DIRECTORY = "farmersdelight_enchant";
    private static final String PACK_DESCRIPTION = "FarmersDelight configurable enchantments";

    private static final String SUPPORTED_ITEMS_TAG = "farmersdelight:enchantable/knife";
    private static final List<String> DISTRIBUTION_TAGS = List.of(
            "tradeable",
            "treasure",
            "on_random_loot"
    );

    private final FarmersDelightPlugin plugin;

    public EnchantmentDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Read per install rather than at construction: the switch belongs to the config the operator may have
     * reloaded since, and keeping it out of the constructor lets the installer be built without a live plugin.
     */
    private boolean installEnabled() {
        return plugin == null || plugin.getConfig().getBoolean("enchantments.install-datapack", true);
    }

    // Export registry settings and migrate old world definitions.
    public void installToPrimaryWorld(World primaryWorld) {
        if (primaryWorld == null) {
            I18n.logWarning("plugin.enchantment_datapack_no_worlds");
            return;
        }
        try {
            DatapackSupport.migrateLegacyPack(primaryWorld,
                    plugin.getDataFolder().toPath(), DATAPACK_DIRECTORY);
        } catch (IOException | IllegalStateException failure) {
            plugin.getLogger().warning("Could not preserve legacy enchant data pack: " + failure.getMessage());
            return;
        }
        EnchantmentSettings settings = plugin.getEnchantmentSettings();
        boolean wrote;
        if (shouldInstall(settings)) {
            wrote = installToWorld(primaryWorld, settings);
        } else {
            wrote = deletePack(DatapackSupport.managedPack(plugin.getDataFolder().toPath(), DATAPACK_DIRECTORY));
        }
        int cleaned = cleanupRedundantDatapacks(primaryWorld);
        if (wrote || cleaned > 0) {
            announceInstalled(primaryWorld);
        }
    }

    // Remove ignored copies outside the primary world's pack scan.
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
                    I18n.logInfo("plugin.enchantment_datapack_redundant_removed", "world", world.getName());
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
        if (!installEnabled() || !settings.enabled()) {
            return false;
        }
        return isBackstabEnabled(settings) || !FarmersDelightEnchantments.managedDefinitions().isEmpty();
    }

    private boolean isBackstabEnabled(EnchantmentSettings settings) {
        return plugin.isBackstabEnchantmentEnabled() && settings.backstabbing().enabled();
    }

    private boolean installToWorld(World world, EnchantmentSettings settings) {
        Path datapackDir = DatapackSupport.managedPack(plugin.getDataFolder().toPath(), DATAPACK_DIRECTORY);
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
            // Ids the running server has not loaded yet; see addDistributionEntry.
            List<String> pendingRestart = new ArrayList<>();

            if (isBackstabEnabled(settings)) {
                generated.add(new GeneratedFile(
                        enchantmentFile(datapackDir, NamespacedId.parse(settings.backstabbing().id())),
                        renderDefinition(settings.backstabbing())));
                // The built-in backstab enchant belongs to all three distribution tags.
                for (String tag : DISTRIBUTION_TAGS) {
                    addDistributionEntry(distribution, tag, settings.backstabbing().id(), pendingRestart);
                }
            }
            for (EnchantmentDefinition definition : FarmersDelightEnchantments.managedDefinitions()) {
                generated.add(new GeneratedFile(
                        enchantmentFile(datapackDir, NamespacedId.parse(definition.id())),
                        renderDefinition(definition)));
                if (definition.tradeable()) {
                    addDistributionEntry(distribution, "tradeable", definition.id(), pendingRestart);
                }
                if (definition.treasure()) {
                    addDistributionEntry(distribution, "treasure", definition.id(), pendingRestart);
                }
                if (definition.onRandomLoot()) {
                    addDistributionEntry(distribution, "on_random_loot", definition.id(), pendingRestart);
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
            writeIfChanged(datapackDir.resolve(".backstabbing-id"), settings.backstabbing().id());
            if (!pendingRestart.isEmpty()) {
                I18n.logWarning("plugin.datapack_registry_pending_restart",
                        "ids", String.join(", ", pendingRestart),
                        "hint", I18n.formatConsole("plugin.datapack_hint_restart"));
            }
            if (changed > 0) {
                I18n.logDetail("startup", "plugin.enchantment_datapack_written",
                        "count", changed, "dir", datapackDir);
            }
            return changed > 0;
        } catch (Exception exception) {
            I18n.logWarning("plugin.enchantment_datapack_install_failed",
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

    // These files load together on the next start; a newly written definition can safely enter its tags.
    private static void addDistributionEntry(Map<String, List<String>> distribution, String tag,
                                             String enchantmentId, List<String> pendingRestart) {
        distribution.get(tag).add(enchantmentId);
        if (!isEnchantmentRegistered(enchantmentId) && !pendingRestart.contains(enchantmentId)) {
            pendingRestart.add(enchantmentId);
        }
    }

    private static boolean isEnchantmentRegistered(String enchantmentId) {
        NamespacedKey key = NamespacedKey.fromString(enchantmentId);
        return key != null
                && RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key) != null;
    }

    private void announceInstalled(World primaryWorld) {
        I18n.logInfo("plugin.datapack_installed",
                "name", DATAPACK_DIRECTORY,
                "dir", DatapackSupport.managedPack(plugin.getDataFolder().toPath(), DATAPACK_DIRECTORY),
                "hint", I18n.formatConsole("plugin.datapack_hint_restart"));
    }

    static String renderPackMetadata() {
        return DatapackSupport.renderPackMetadata(PACK_DESCRIPTION);
    }

    static String renderDefinition(EnchantmentSettings.Backstabbing backstabbing) {
        return FarmersDelightDatapacks.renderDefinition(backstabbing);
    }

    static String renderDefinition(EnchantmentDefinition definition) {
        return FarmersDelightDatapacks.renderDefinition(definition);
    }

    static String renderSupportedItems() {
        return FarmersDelightDatapacks.renderSupportedItems();
    }

    static String renderDistributionTag(List<String> ids) {
        return FarmersDelightDatapacks.renderDistributionTag(ids);
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
        Set<Path> expected = new HashSet<>();
        for (GeneratedFile file : generated) {
            expected.add(file.path().toAbsolutePath().normalize());
        }
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> paths = Files.walk(data)) {
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
            if (NamespacedKey.fromString(value) == null || value.contains("/../")
                    || value.endsWith("/..") || value.contains(":../") || value.contains(":./")
                    || value.contains("/./") || value.endsWith("/.") || separator <= 0) {
                throw new IllegalArgumentException("Invalid namespaced id: " + value);
            }
            return new NamespacedId(value.substring(0, separator), value.substring(separator + 1));
        }
    }
}
