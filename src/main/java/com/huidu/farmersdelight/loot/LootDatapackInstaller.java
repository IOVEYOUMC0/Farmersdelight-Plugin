package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class LootDatapackInstaller implements Listener {

    private static final String DATAPACK_NAME = "farmersdelight";
    private static final String RESOURCE_PREFIX = "datapack/loot/";

    private final FarmersDelightPlugin plugin;
    private final boolean installEnabled;

    public LootDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.installEnabled = plugin.getConfig().getBoolean("loot-injection.install-datapack", true);
    }

    public void installToAllWorlds() {
        if (!installEnabled) {
            I18n.logInfo("loot_datapack_disabled");
            return;
        }
        var worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            I18n.logWarning("loot_datapack_no_worlds");
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
        // Cover newly-created / runtime-loaded worlds (e.g. Multiverse) — same idempotent install path.
        // A world that actually receives files here needs the same restart notice the startup path prints:
        // the per-world write itself only reaches the startup detail channel, so without this banner an
        // install into a runtime-created world would leave no console trace and the operator would never
        // learn the loot injections are not live yet.
        if (installEnabled && installToWorld(event.getWorld())) {
            printRestartBanner(1);
        }
    }

    private void printRestartBanner(int worlds) {
        plugin.getLogger().warning("==================================================================");
        plugin.getLogger().warning(" Installed FarmersDelight loot datapack into " + worlds + " world(s).");
        plugin.getLogger().warning(" RESTART the server (or run /reload) for the loot injections to");
        plugin.getLogger().warning(" take effect. Custom items in vanilla chests / mob drops / grass");
        plugin.getLogger().warning(" will not appear until the datapack is loaded by vanilla.");
        plugin.getLogger().warning("==================================================================");
    }

    private boolean installToWorld(World world) {
        Path datapackDir = getWorldRoot(world).resolve("datapacks").resolve(DATAPACK_NAME);
        // Fresh install → copy everything. Existing install → only ADD files that are missing (e.g. a
        // newly-bundled stove_burn damage type after a plugin update), never overwriting files the admin
        // may have edited. Mirrors FarmersDelight's CraftEngine-resource auto-completion.
        boolean freshInstall = !Files.exists(datapackDir);
        boolean debug = plugin.isDebugEnabled("startup");
        try {
            Files.createDirectories(datapackDir);
            try (JarFile jar = openOwnJar()) {
                Enumeration<JarEntry> entries = jar.entries();
                int count = 0;
                int skipped = 0;
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.startsWith(RESOURCE_PREFIX)) continue;
                    String relative = name.substring(RESOURCE_PREFIX.length());
                    Path dest = datapackDir.resolve(relative);
                    if (!freshInstall && Files.exists(dest)) {
                        skipped++;
                        if (debug) I18n.logInfo("loot_datapack_debug_skipped", "path", relative);
                        continue;
                    }
                    if (debug) I18n.logInfo("loot_datapack_debug_added", "path", relative);
                    Path parent = dest.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
                if (debug) {
                    I18n.logInfo("loot_datapack_debug_summary", "count", count, "skipped", skipped, "dir", datapackDir.toString());
                }
                if (count > 0) {
                    I18n.logDetail("startup", "plugin.loot_datapack_written", "count", count, "dir", datapackDir);
                }
                // Only a call that wrote at least one file counts as an install: the restart banner is about
                // datapack content the running server has not loaded yet, which is exactly this case. A boot
                // where every bundled file was already on disk changed nothing and must stay silent.
                return count > 0;
            }
        } catch (Exception e) {
            I18n.logWarning("loot_datapack_install_failed", "world", world.getName(), "error", e.getMessage());
            return false;
        }
    }

    private JarFile openOwnJar() throws IOException {
        URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
        try {
            return new JarFile(Paths.get(location.toURI()).toFile());
        } catch (URISyntaxException e) {
            throw new IOException("Could not resolve plugin jar location: " + location, e);
        }
    }

    private static Path getWorldRoot(World world) {
        Path folder = world.getWorldFolder().toPath();
        while (folder != null && !Files.exists(folder.resolve("level.dat"))) {
            folder = folder.getParent();
        }
        return folder != null ? folder : world.getWorldFolder().toPath();
    }
}
