package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.api.util.DatapackSupport;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;

// Exports the common-item tag registry as a server-side data pack so plain vanilla items (members
// under the "minecraft:" namespace in CommonTagResolver) become real registry tags. On the next server
// start CraftEngine absorbs them via registerAllVanillaItems -> Holder tags, letting CE recipes written
// against #c:... / #farmersdelight:... match vanilla items without altering vanilla behavior. During a
// live Bukkit/Paper data reload, FD uses Bukkit's refreshed tag views for its own recipe matching.
//
// Registry tags are server-global and shared by every world, so the pack is written once into the
// primary world's datapacks folder (the data-pack root the server loads at boot). A per-world
// whitelist would be meaningless here and is deliberately absent. Only "minecraft:" members are
// written: addon (CraftEngine) items stay declared in the CE item configs, the very source CraftEngine
// already serves customItemIdsByTag from.
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

    // Writes the tag data pack into the primary world's datapacks folder. Idempotent: rewrites any
    // file whose content differs and leaves matching files untouched. Returns true when anything changed.
    public boolean installToPrimaryWorld(World primaryWorld) {
        if (primaryWorld == null) {
            return false;
        }
        Path datapackDir = DatapackSupport.worldRoot(primaryWorld)
                .resolve("datapacks")
                .resolve(DATAPACK_DIRECTORY);
        if (!installEnabled()) {
            return deletePack(datapackDir);
        }
        Map<String, Set<String>> tags = vanillaTagSnapshot();
        if (tags.isEmpty()) {
            return deletePack(datapackDir);
        }
        try {
            List<GeneratedFile> generated = new ArrayList<>();
            generated.add(new GeneratedFile(datapackDir.resolve("pack.mcmeta"), renderPackMetadata()));
            for (Map.Entry<String, Set<String>> entry : tags.entrySet()) {
                NamespacedId tag = NamespacedId.parse(entry.getKey());
                generated.add(new GeneratedFile(
                        datapackDir.resolve("data")
                                .resolve(tag.namespace())
                                .resolve("tags")
                                .resolve("item")
                                .resolve(tag.path() + ".json"),
                        renderTag(entry.getValue())));
            }
            int changed = 0;
            for (GeneratedFile file : generated) {
                if (DatapackSupport.writeIfChanged(file.path(), file.content())) {
                    changed++;
                }
            }
            changed += deleteStaleFiles(datapackDir, generated);
            if (changed > 0) {
                I18n.logInfo("plugin.datapack_installed",
                        "name", DATAPACK_DIRECTORY,
                        "dir", datapackDir,
                        "hint", I18n.formatConsole("plugin.datapack_hint_reload"));
            }
            return changed > 0;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to install common-item tag data pack for world '"
                    + primaryWorld.getName() + "': " + exception.getMessage());
            return false;
        }
    }

    // Collects every registered tag but keeps only plain vanilla members. Members resolve to no item
    // on the current server version (e.g. brown/blue egg on 1.21.4) are dropped so the exported tag
    // never references an unknown id that could fail the pack load. Tags whose surviving members are
    // all outside the "minecraft:" namespace produce no file and stay declared in the CE item configs.
    private static Map<String, Set<String>> vanillaTagSnapshot() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : CommonTagResolver.tagSnapshot().entrySet()) {
            Set<String> vanilla = new LinkedHashSet<>();
            for (String itemId : entry.getValue()) {
                int separator = itemId.indexOf(':');
                if (separator > 0 && VANILLA_NAMESPACE.equals(itemId.substring(0, separator))
                        && isExistingItem(itemId)) {
                    vanilla.add(itemId);
                }
            }
            if (!vanilla.isEmpty()) {
                result.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(vanilla)));
            }
        }
        return result;
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

    private static int deleteStaleFiles(Path root, List<GeneratedFile> generated) throws IOException {
        Set<Path> expected = new HashSet<>();
        for (GeneratedFile file : generated) {
            expected.add(file.path().toAbsolutePath().normalize());
        }
        int removed = 0;
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) {
            return 0;
        }
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

    private static boolean isExistingItem(String itemId) {
        return Material.matchMaterial(itemId) != null;
    }

    static String renderPackMetadata() {
        return DatapackSupport.renderPackMetadata(PACK_DESCRIPTION);
    }

    static String renderTag(Set<String> members) {
        StringBuilder values = new StringBuilder();
        for (String member : members.stream().sorted().toList()) {
            if (!values.isEmpty()) {
                values.append(",\n");
            }
            values.append("    \"").append(member).append('"');
        }
        return "{\n  \"replace\": false,\n  \"values\": [\n" + values + "\n  ]\n}\n";
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
