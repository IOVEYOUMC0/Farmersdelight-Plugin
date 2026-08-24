package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.DatapackSupport;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

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
public final class DamageTypeDatapackInstaller implements Listener {

    private static final String DATAPACK_NAME = "farmersdelight_damage";
    private static final String RESOURCE_PREFIX = "datapack/damage/";
    private static final String LEGACY_LOOT_DATAPACK = "farmersdelight";
    private static final String LEGACY_DAMAGE_DIR = "data/farmersdelight/damage_type";
    private static final String LEGACY_NO_KNOCKBACK = "data/minecraft/tags/damage_type/no_knockback.json";

    // Removes the old FarmersDelight loot datapack (datapacks/farmersdelight) that pre-CE-native
    // builds installed to inject items into vanilla chest/grass/mob loot tables. The injections now
    // live as CraftEngine vanilla/container loot sources, so keeping the stale datapack would
    // double-add CE items. The damage files that old datapack also carried are migrated by
    // removeLegacyFiles first, so nothing is lost when the whole folder is deleted afterwards.
    public void cleanupLegacyLootDatapack() {
        if (!installEnabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            if (plugin.isDatapackWorldAllowed(world)) {
                continue;
            }
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

    public void installToAllWorlds() {
        if (!installEnabled) {
            I18n.logInfo("damage_datapack_disabled");
            return;
        }
        var worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            I18n.logWarning("damage_datapack_no_worlds");
            return;
        }
        int installed = 0;
        for (World world : worlds) {
            if (plugin.isDatapackWorldAllowed(world)) {
                continue;
            }
            if (installToWorld(world)) installed++;
        }
        if (installed > 0) {
            printRestartBanner(installed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        if (plugin.isDatapackWorldAllowed(event.getWorld())) {
            return;
        }
        if (installEnabled && installToWorld(event.getWorld())) {
            printRestartBanner(1);
        }
    }

    private void printRestartBanner(int worlds) {
        plugin.getLogger().warning("==================================================================");
        plugin.getLogger().warning(" Installed FarmersDelight damage-type datapack into " + worlds + " world(s).");
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
