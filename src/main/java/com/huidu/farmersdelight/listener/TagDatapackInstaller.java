package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.datapack.FarmersDelightDatapacks;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.api.util.DatapackSupport;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Files;

// Exports vanilla common-tag members to the plugin-managed pack for the next server start.
public final class TagDatapackInstaller implements Listener {

    private static final String DATAPACK_DIRECTORY = "farmersdelight_tags";
    private static final String PACK_DESCRIPTION = "FarmersDelight common-item tags (vanilla members)";
    private static final String VANILLA_NAMESPACE = "minecraft";

    private final FarmersDelightPlugin plugin;

    public TagDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Read per install rather than at construction: the switch belongs to the config the operator may have
     * reloaded since, and keeping it out of the constructor lets the installer be built without a live plugin.
     */
    private boolean installEnabled() {
        return plugin == null || plugin.getConfig().getBoolean("datapacks.tags-enabled", true);
    }

    // Addons register their tag sources on their own enable, which happens after FD's. Exporting only
    // during FD's enable would therefore drop every addon-contributed vanilla member. Re-export once the
    // whole server has loaded; CraftEngine absorbs the written members on the next start.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (installToPrimaryWorld(plugin.getPrimaryWorld())) {
            plugin.queueDatapackReload(
                    I18n.formatConsole("plugin.datapack_reason_apply_tag_changes"));
        }
    }

    // Exports current tag sources to the managed pack; equal content is left untouched.
    public boolean installToPrimaryWorld(World primaryWorld) {
        if (primaryWorld == null || plugin.getServer().isStopping()) {
            return false;
        }
        Path datapackDir = DatapackSupport.managedPack(plugin.getDataFolder().toPath(), DATAPACK_DIRECTORY);
        try {
            DatapackSupport.migrateLegacyPack(primaryWorld,
                    plugin.getDataFolder().toPath(), DATAPACK_DIRECTORY);
            YamlConfiguration sources = new YamlConfiguration();
            Map<String, List<String>> saved = new LinkedHashMap<>();
            CommonTagResolver.externalTagSnapshot().forEach((tag, members) ->
                    saved.put(tag, members.stream().sorted().toList()));
            sources.set("tags", saved);
            DatapackSupport.writeIfChanged(plugin.getDataFolder().toPath().resolve("datapacks/tag-sources.yml"),
                    sources.saveToString());
        } catch (IOException | IllegalStateException failure) {
            plugin.getLogger().warning("Could not preserve legacy tag data pack: " + failure.getMessage());
            return false;
        }
        if (!installEnabled()) {
            return deletePack(datapackDir);
        }
        Map<String, Set<String>> tags = CommonTagResolver.tagSnapshot();
        if (tags.isEmpty()) {
            return deletePack(datapackDir);
        }
        try {
            boolean changed = writePack(datapackDir, tags, Bukkit.getBukkitVersion());
            if (changed) {
                I18n.logInfo("plugin.datapack_installed", "name", DATAPACK_DIRECTORY,
                        "dir", datapackDir, "hint", I18n.formatConsole("plugin.datapack_hint_restart"));
            }
            return changed;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to install common-item tag data pack: " + exception.getMessage());
            return false;
        }
    }

    public static boolean writePack(Path root, Map<String, Set<String>> snapshot, String version) throws IOException {
        return FarmersDelightDatapacks.writeTags(root, snapshot, version);
    }

    private boolean deletePack(Path datapackDir) {
        try {
            if (!Files.exists(datapackDir)) {
                return false;
            }
            DatapackSupport.deleteRecursively(datapackDir);
            I18n.logWarning("plugin.datapack_removed",
                    "name", DATAPACK_DIRECTORY,
                    "hint", I18n.formatConsole("plugin.datapack_hint_restart"));
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to remove tag data pack: " + exception.getMessage());
            return false;
        }
    }

    static String renderPackMetadata() {
        return DatapackSupport.renderPackMetadata(PACK_DESCRIPTION);
    }

    static String renderTag(Set<String> members) {
        return FarmersDelightDatapacks.renderTag(members);
    }


}
