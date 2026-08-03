package com.huidu.farmersdelight.listener;

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
 * 将背刺附魔数据包安装到每个世界的 datapacks/ 文件夹中。
 * 独立于战利品注入数据包（farmersdelight），可单独开关。
 */
public final class EnchantmentDatapackInstaller implements Listener {

    private static final String DATAPACK_NAME = "farmersdelight_enchant";
    private static final String RESOURCE_PREFIX = "datapack/enchantment/";

    private final FarmersDelightPlugin plugin;
    private final boolean installEnabled;

    public EnchantmentDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.installEnabled = plugin.getConfig().getBoolean("enchantments.backstabbing.enabled", true);
    }

    /** 安装到所有已加载的世界。在 onEnable 中调用。 */
    public void installToAllWorlds() {
        if (!installEnabled) {
            plugin.getLogger().info("[FarmersDelight] Enchantment datapack install disabled (config: enchantments.backstabbing.enabled=false)");
            return;
        }
        var worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            plugin.getLogger().warning("[FarmersDelight] No loaded worlds — enchantment datapack will install when a world loads");
            return;
        }
        int installed = 0;
        for (World world : worlds) {
            if (installToWorld(world)) installed++;
        }
        if (installed > 0) {
            printRestartBanner(installed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        if (installEnabled && installToWorld(event.getWorld())) {
            printRestartBanner(1);
        }
    }

    private void printRestartBanner(int worlds) {
        plugin.getLogger().warning("==================================================================");
        plugin.getLogger().warning(" Installed FarmersDelight enchantment datapack into " + worlds + " world(s).");
        plugin.getLogger().warning(" RESTART the server (or run /reload) for the backstabbing enchantment");
        plugin.getLogger().warning(" to take effect in the enchanting table.");
        plugin.getLogger().warning("==================================================================");
    }

    private boolean installToWorld(World world) {
        Path datapackDir = getWorldRoot(world).resolve("datapacks").resolve(DATAPACK_NAME);
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
                        if (debug) plugin.getLogger().info("[FarmersDelight] = " + relative);
                        continue;
                    }
                    if (debug) plugin.getLogger().info("[FarmersDelight] + " + relative);
                    Path parent = dest.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
                if (debug) {
                    plugin.getLogger().info("[FarmersDelight] 附魔数据包: +" + count + " new, =" + skipped + " skipped -> " + datapackDir);
                }
                if (count > 0) {
                    I18n.logDetail("startup", "plugin.enchantment_datapack_written", "count", count, "dir", datapackDir);
                }
                return count > 0;
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "[FarmersDelight] Enchantment datapack install FAILED (world=" + world.getName() + "): " + e.getMessage(), e);
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

    /**
     * 获取真正的世界根目录（含 level.dat）。在维度分离存储结构下，
     * World#getWorldFolder() 返回的是维度子文件夹，需要向上查找。
     */
    private static Path getWorldRoot(World world) {
        Path folder = world.getWorldFolder().toPath();
        while (folder != null && !Files.exists(folder.resolve("level.dat"))) {
            folder = folder.getParent();
        }
        return folder != null ? folder : world.getWorldFolder().toPath();
    }
}
