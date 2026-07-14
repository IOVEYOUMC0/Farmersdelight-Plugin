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

/**
 * Installs FarmersDelight's loot-injection datapack into each world's datapacks/ folder.
 * The bundled datapack overrides vanilla chest / mob / grass loot tables to inject CraftEngine
 * custom items (via the craftengine:item loot pool entry type registered by CraftEngine).
 *
 * <p>Installs idempotently: skips worlds where the datapack folder already exists, so admin
 * customisations are preserved across plugin updates. Newly placed datapacks require a server
 * restart or /reload to be picked up by vanilla — that's intentional and logged loudly.
 */
public final class LootDatapackInstaller implements Listener {

    private static final String DATAPACK_NAME = "farmersdelight_loot";
    private static final String RESOURCE_PREFIX = "datapack/";

    private final FarmersDelightPlugin plugin;
    private final boolean installEnabled;

    public LootDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.installEnabled = plugin.getConfig().getBoolean("loot-injection.install-datapack", true);
    }

    /** Install into every currently loaded world. Call from onEnable. */
    public void installToAllWorlds() {
        if (!installEnabled) {
            I18n.logInfo("loot_datapack_disabled");
            return;
        }
        int installed = 0;
        for (World world : Bukkit.getWorlds()) {
            if (installToWorld(world)) installed++;
        }
        if (installed > 0) {
            plugin.getLogger().warning("==================================================================");
            plugin.getLogger().warning(" Installed FarmersDelight loot datapack into " + installed + " world(s).");
            plugin.getLogger().warning(" RESTART the server (or run /reload) for the loot injections to");
            plugin.getLogger().warning(" take effect. Custom items in vanilla chests / mob drops / grass");
            plugin.getLogger().warning(" will not appear until the datapack is loaded by vanilla.");
            plugin.getLogger().warning("==================================================================");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        // Cover newly-created / runtime-loaded worlds (e.g. Multiverse) — same idempotent install path.
        if (installEnabled) installToWorld(event.getWorld());
    }

    /** @return true if files were written this call (false: already present or skipped). */
    private boolean installToWorld(World world) {
        Path datapackDir = world.getWorldFolder().toPath().resolve("datapacks").resolve(DATAPACK_NAME);
        // Fresh install → copy everything. Existing install → only ADD files that are missing (e.g. a
        // newly-bundled stove_burn damage type after a plugin update), never overwriting files the admin
        // may have edited. Mirrors FarmersDelight's CraftEngine-resource auto-completion.
        boolean freshInstall = !Files.exists(datapackDir);
        try {
            Files.createDirectories(datapackDir);
            try (JarFile jar = openOwnJar()) {
                Enumeration<JarEntry> entries = jar.entries();
                int count = 0;
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.startsWith(RESOURCE_PREFIX)) continue;
                    String relative = name.substring(RESOURCE_PREFIX.length());
                    Path dest = datapackDir.resolve(relative);
                    if (!freshInstall && Files.exists(dest)) continue;
                    Path parent = dest.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
                if (count > 0) {
                    I18n.logInfo("loot_datapack_written", "count", count, "dir", datapackDir);
                }
            }
            return true;
        } catch (IOException e) {
            I18n.logSevere("loot_datapack_install_failed", "world", world.getName(), "error", e.getMessage());
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
}
