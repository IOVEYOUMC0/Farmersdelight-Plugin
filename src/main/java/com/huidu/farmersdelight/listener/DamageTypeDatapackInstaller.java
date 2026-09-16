package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.DatapackSupport;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

// Installs the standalone FarmersDelight damage-type datapack (farmersdelight:stove_burn + the
// no_knockback tag), independent of the loot datapack so each can be toggled and reinstalled on its
// own. Also migrates the damage files out of the legacy loot datapack folder (pre-split installs
// wrote them to datapacks/farmersdelight/), otherwise both datapacks would define stove_burn and the
// duplicate definition would clash at load time.
public final class DamageTypeDatapackInstaller {

    private static final String DATAPACK_NAME = "farmersdelight_damage";
    private static final String RESOURCE_PREFIX = "datapack/damage/";
    private static final String PACK_METADATA_FILE = "pack.mcmeta";
    private static final String PACK_DESCRIPTION =
            "FarmersDelight damage types (stove_burn, no_knockback tag)";
    private static final String LEGACY_LOOT_DATAPACK = "farmersdelight";
    private static final String LEGACY_DAMAGE_DIR = "data/farmersdelight/damage_type";
    private static final String LEGACY_NO_KNOCKBACK = "data/minecraft/tags/damage_type/no_knockback.json";

    // Deletes the obsolete FarmersDelight loot datapack (datapacks/farmersdelight). Loot injections
    // use CraftEngine vanilla/container loot sources; damage files are migrated by removeLegacyFiles
    // before the folder is deleted. Runs across every world where the folder exists.
    public void cleanupLegacyLootDatapack() {
        if (!installEnabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            Path legacy = DatapackSupport.worldRoot(world).resolve("datapacks").resolve(LEGACY_LOOT_DATAPACK);
            try {
                if (Files.exists(legacy)) {
                    DatapackSupport.deleteRecursively(legacy);
                    I18n.logInfo("loot_datapack_legacy_removed", "world", world.getName());
                }
            } catch (IOException e) {
                plugin.getLogger().warning("FarmersDelight loot datapack: failed to remove legacy folder under "
                        + legacy + ": " + e.getMessage());
            }
        }
    }

    private final FarmersDelightPlugin plugin;
    private final boolean installEnabled;

    public DamageTypeDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.installEnabled = plugin.getConfig().getBoolean("damage-type.install-datapack", true);
    }

    // Registry-scoped datapack content (damage types, damage_type tags) is loaded by the server from
    // the primary world's datapacks folder and shared by every world, so the pack is written once into
    // the primary world. Copies in non-primary worlds are removed.
    public void installToPrimaryWorld(World primaryWorld) {
        if (!installEnabled) {
            I18n.logInfo("damage_datapack_disabled");
            return;
        }
        if (primaryWorld == null) {
            I18n.logWarning("damage_datapack_no_worlds");
            return;
        }
        boolean wrote = installToWorld(primaryWorld);
        int cleaned = cleanupRedundantDatapacks(primaryWorld);
        if (wrote || cleaned > 0) {
            printRestartBanner();
        }
    }

    // Deletes the farmersdelight_damage datapack from every non-primary world. The server scans only
    // the primary world's datapacks/ folder. Loot-folder cleanup is handled separately by
    // cleanupLegacyLootDatapack across every world.
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
                    I18n.logInfo("damage_datapack_redundant_removed", "world", world.getName());
                }
            } catch (IOException e) {
                plugin.getLogger().warning("FarmersDelight damage datapack: failed to remove redundant folder under "
                        + redundant + ": " + e.getMessage());
            }
        }
        return removed;
    }

    private void printRestartBanner() {
        plugin.getLogger().warning("==================================================================");
        plugin.getLogger().warning(" Installed FarmersDelight damage-type datapack (primary world only).");
        plugin.getLogger().warning(" RESTART the server (or run /reload) for the stove_burn damage type");
        plugin.getLogger().warning(" to take effect.");
        plugin.getLogger().warning("==================================================================");
    }

    private boolean installToWorld(World world) {
        Path worldRoot = DatapackSupport.worldRoot(world);
        Path datapackDir = worldRoot.resolve("datapacks").resolve(DATAPACK_NAME);
        boolean debug = plugin.isDebugEnabled("startup");
        try {
            Files.createDirectories(datapackDir);
            int count = 0;
            int skipped = 0;
            try (JarFile ownJar = DatapackSupport.openPluginJar(plugin.getClass())) {
                Enumeration<JarEntry> entries = ownJar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.startsWith(RESOURCE_PREFIX)) continue;
                    String relative = name.substring(RESOURCE_PREFIX.length());
                    // pack.mcmeta is generated from the running server version instead of copied: the
                    // bundled copy carries a fixed pack_format, which the server rejects on any other
                    // release. Rewriting it also refreshes a stale format left by an earlier server.
                    if (PACK_METADATA_FILE.equals(relative)) {
                        continue;
                    }
                    Path dest = datapackDir.resolve(relative);
                    if (Files.exists(dest)) {
                        skipped++;
                        if (debug) I18n.logInfo("loot_datapack_debug_skipped", "path", relative);
                        continue;
                    }
                    if (debug) I18n.logInfo("loot_datapack_debug_added", "path", relative);
                    DatapackSupport.copyFromJar(ownJar, entry, dest);
                    count++;
                }
            }
            if (DatapackSupport.writeIfChanged(datapackDir.resolve(PACK_METADATA_FILE),
                    DatapackSupport.renderPackMetadata(PACK_DESCRIPTION))) {
                count++;
            }
            // Migrate the damage files out of the legacy loot datapack folder so the two datapacks
            // never define stove_burn twice. No-op on fresh installs and after a previous migration.
            Path legacy = worldRoot.resolve("datapacks").resolve(LEGACY_LOOT_DATAPACK);
            int migrated = removeLegacyFiles(legacy);
            if (migrated > 0) {
                I18n.logInfo("damage_datapack_legacy_removed", "world", world.getName(), "count", migrated);
            }
            if (debug) {
                I18n.logInfo("loot_datapack_debug_summary", "count", count, "skipped", skipped, "dir", datapackDir.toString());
            }
            if (count > 0) {
                I18n.logDetail("startup", "damage_datapack_written", "count", count, "dir", datapackDir);
            }
            return count > 0;
        } catch (IOException e) {
            I18n.logWarning("damage_datapack_install_failed", "world", world.getName(), "error", e.getMessage());
            return false;
        }
    }

    // Deletes the pre-split damage files from datapacks/farmersdelight/ (both the stove_burn damage
    // type directory and the no_knockback tag it appended to). Returns how many paths were removed.
    private int removeLegacyFiles(Path legacyDatapackDir) {
        int removed = 0;
        try {
            Path damageDir = legacyDatapackDir.resolve(LEGACY_DAMAGE_DIR);
            if (Files.isDirectory(damageDir)) {
                DatapackSupport.deleteRecursively(damageDir);
                removed++;
            }
            Path noKnockback = legacyDatapackDir.resolve(LEGACY_NO_KNOCKBACK);
            if (Files.isRegularFile(noKnockback)) {
                Files.deleteIfExists(noKnockback);
                removed++;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("FarmersDelight damage datapack: failed to remove legacy files under "
                    + legacyDatapackDir + ": " + e.getMessage());
        }
        return removed;
    }
}
