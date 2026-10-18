package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.api.util.DatapackSupport;
import com.huidu.farmersdelight.api.util.DamageTypePack;
import com.huidu.farmersdelight.FarmersDelightBootstrap;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Exports damage registry YAML and removes obsolete world packs.
public final class DamageTypeDatapackInstaller {

    private static final String DATAPACK_NAME = "farmersdelight_damage";
    private static final String PACK_DESCRIPTION = "FarmersDelight damage types";
    private static final String DAMAGE_TYPE_ID = "farmersdelight:stove_burn";
    // CE owns loot injection, so the old world pack is no longer needed.
    public void cleanupLegacyLootDatapack() {
        if (!installEnabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Path folder : List.of(DatapackSupport.worldRoot(world), world.getWorldFolder().toPath())) {
                for (String name : List.of("farmersdelight", "farmersdelight_loot")) {
                    Path legacy = folder.resolve("datapacks").resolve(name);
                    try {
                        if (Files.exists(legacy)) {
                            DatapackSupport.deleteRecursively(legacy);
                            I18n.logInfo("plugin.loot_datapack_legacy_removed", "world", world.getName());
                        }
                    } catch (IOException failure) {
                        plugin.getLogger().warning("Could not remove obsolete loot pack " + legacy + ": " + failure.getMessage());
                    }
                }
            }
        }
    }

    private final FarmersDelightPlugin plugin;
    private final boolean installEnabled;

    public DamageTypeDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.installEnabled = plugin.getConfig().getBoolean("damage-type.install-datapack", true);
    }

    // Registry data is server-global; only legacy migration needs the primary world.
    public void installToPrimaryWorld(World primaryWorld) {
        if (!installEnabled) {
            I18n.logInfo("plugin.damage_datapack_disabled");
            return;
        }
        if (primaryWorld == null) {
            I18n.logWarning("plugin.damage_datapack_no_worlds");
            return;
        }
        try {
            DatapackSupport.migrateLegacyPack(primaryWorld,
                    plugin.getDataFolder().toPath(), DATAPACK_NAME);
        } catch (IOException | IllegalStateException failure) {
            plugin.getLogger().warning("Could not preserve legacy damage data pack: " + failure.getMessage());
            return;
        }
        boolean wrote = installToWorld(primaryWorld);
        int cleaned = cleanupRedundantDatapacks(primaryWorld);
        if (wrote || cleaned > 0) {
            announceInstalled(primaryWorld);
        }
    }

    // Remove ignored copies without touching the primary pack.
    private int cleanupRedundantDatapacks(World primaryWorld) {
        Path primaryDatapack = DatapackSupport.worldRoot(primaryWorld)
                .resolve("datapacks")
                .resolve(DATAPACK_NAME)
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
                    .resolve(DATAPACK_NAME);
            // Modern dimension folders resolve to the same level.dat root as the primary world.
            // Do not delete the primary pack while iterating those worlds.
            if (DatapackSupport.sameNormalizedPath(primaryDatapack, redundant)) {
                continue;
            }
            try {
                if (Files.exists(redundant)) {
                    DatapackSupport.deleteRecursively(redundant);
                    removed++;
                    I18n.logInfo("plugin.damage_datapack_redundant_removed", "world", world.getName());
                }
            } catch (IOException e) {
                plugin.getLogger().warning("FarmersDelight damage datapack: failed to remove redundant folder under "
                        + redundant + ": " + e.getMessage());
            }
        }
        return removed;
    }

    private void announceInstalled(World primaryWorld) {
        I18n.logInfo("plugin.datapack_installed",
                "name", DATAPACK_NAME,
                "dir", DatapackSupport.managedPack(plugin.getDataFolder().toPath(), DATAPACK_NAME),
                "hint", I18n.formatConsole("plugin.datapack_hint_restart"));
    }

    private boolean installToWorld(World world) {
        Path root = DatapackSupport.managedPack(plugin.getDataFolder().toPath(), DATAPACK_NAME);
        try {
            Path configuration = plugin.getDataFolder().toPath().resolve("damage-types.yml");
            DamageTypePack.saveConfiguration(configuration, root);
            boolean changed = DamageTypePack.prepare(configuration,
                    FarmersDelightBootstrap.class.getResourceAsStream("/damage-types.yml"), root,
                    PACK_DESCRIPTION, Bukkit.getBukkitVersion());
            if (!isDamageTypeRegistered()) {
                I18n.logWarning("plugin.datapack_registry_pending_restart", "ids", DAMAGE_TYPE_ID,
                        "hint", I18n.formatConsole("plugin.datapack_hint_restart"));
            }
            return changed;
        } catch (IOException | IllegalStateException failure) {
            I18n.logWarning("plugin.damage_datapack_install_failed", "world", world.getName(),
                    "error", failure.getMessage());
            return false;
        }
    }

    // True when the running server has already loaded farmersdelight:stove_burn. A datapack written during
    // this run is only picked up by the next start, so this is false on the first run after the pack appears.
    private static boolean isDamageTypeRegistered() {
        NamespacedKey key = NamespacedKey.fromString(DAMAGE_TYPE_ID);
        return key != null
                && RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).get(key) != null;
    }


}
