package com.huidu.farmersdelight;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.World;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Resolves the datapack world and caches the configured level-name lookup. */
final class PrimaryWorldResolver {

    private final FarmersDelightPlugin plugin;
    private final Object levelNameLock = new Object();
    private volatile boolean levelNameResolved;
    private volatile String cachedLevelName;

    PrimaryWorldResolver(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    World resolve() {
        List<World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }
        String configuredLevelName = getConfiguredLevelName();
        if (configuredLevelName != null && !configuredLevelName.isBlank()) {
            World configuredWorld = plugin.getServer().getWorld(configuredLevelName);
            if (configuredWorld != null) {
                return configuredWorld;
            }
            for (World world : worlds) {
                if (configuredLevelName.equals(world.getName())) {
                    return world;
                }
            }
        }
        for (World world : worlds) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        return worlds.getFirst();
    }

    private String getConfiguredLevelName() {
        if (levelNameResolved) {
            return cachedLevelName;
        }
        synchronized (levelNameLock) {
            if (!levelNameResolved) {
                cachedLevelName = readConfiguredLevelName();
                levelNameResolved = true;
            }
            return cachedLevelName;
        }
    }

    private String readConfiguredLevelName() {
        Path serverProperties = plugin.getServer().getWorldContainer().toPath().resolve("server.properties");
        if (!Files.isRegularFile(serverProperties)) {
            return null;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(serverProperties);
             InputStreamReader reader = new InputStreamReader(input)) {
            properties.load(reader);
            return properties.getProperty("level-name");
        } catch (IOException e) {
            plugin.getLogger().fine(I18n.formatConsole("plugin.server_properties_read_failed", "error", e.getMessage()));
            return null;
        }
    }
}
