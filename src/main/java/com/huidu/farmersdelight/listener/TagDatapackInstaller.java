package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.DatapackSupport;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Exports the common-item tag registry as a server-side data pack so plain vanilla items (members
// under the "minecraft:" namespace in CommonTagResolver) become real registry tags. CraftEngine then
// absorbs them via registerAllVanillaItems -> Holder tags, letting CE recipes written against
// #c:... / #farmersdelight:... match vanilla items without altering vanilla behavior.
//
// Registry tags are server-global and shared by every world, so the pack is written once into the
// primary world's datapacks folder (the data-pack root the server loads at boot). A per-world
// whitelist would be meaningless here and is deliberately absent. Only "minecraft:" members are
// written: addon (CraftEngine) items stay declared in the CE item configs, the very source CraftEngine
// already serves customItemIdsByTag from.
public final class TagDatapackInstaller {

    private static final String DATAPACK_DIRECTORY = "farmersdelight_tags";
    private static final int PACK_FORMAT = 48;
    private static final String PACK_DESCRIPTION = "FarmersDelight common-item tags (vanilla members)";
    private static final String VANILLA_NAMESPACE = "minecraft";

    private final FarmersDelightPlugin plugin;
    private final boolean installEnabled;

    public TagDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.installEnabled = plugin.getConfig().getBoolean("datapacks.tags-enabled", true);
    }

    // Writes the tag data pack into the primary world's datapacks folder. Idempotent: rewrites any
    // file whose content differs and leaves matching files untouched. Returns true when anything changed.
    public boolean installToPrimaryWorld(World primaryWorld) {
        if (!installEnabled || primaryWorld == null) {
            return false;
        }
        Map<String, Set<String>> tags = vanillaTagSnapshot();
        if (tags.isEmpty()) {
            return false;
        }
        Path datapackDir = DatapackSupport.worldRoot(primaryWorld)
                .resolve("datapacks")
                .resolve(DATAPACK_DIRECTORY);
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
            if (changed > 0) {
                plugin.getLogger().warning("==========================================================");
                plugin.getLogger().warning(" Updated FarmersDelight common-item tag data pack (" + changed + " file(s)).");
                plugin.getLogger().warning(" RESTART the server so the registry tags take effect.");
                plugin.getLogger().warning("==========================================================");
            }
            return changed > 0;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to install common-item tag data pack for world '"
                    + primaryWorld.getName() + "': " + exception.getMessage());
            return false;
        }
    }

    // Collects every registered tag but keeps only plain vanilla members. Tags whose members are all
    // outside the "minecraft:" namespace produce no file and stay declared in the CE item configs.
    private static Map<String, Set<String>> vanillaTagSnapshot() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : CommonTagResolver.tagSnapshot().entrySet()) {
            Set<String> vanilla = new LinkedHashSet<>();
            for (String itemId : entry.getValue()) {
                int separator = itemId.indexOf(':');
                if (separator > 0 && VANILLA_NAMESPACE.equals(itemId.substring(0, separator))) {
                    vanilla.add(itemId);
                }
            }
            if (!vanilla.isEmpty()) {
                result.put(entry.getKey(), Set.copyOf(vanilla));
            }
        }
        return result;
    }

    static String renderPackMetadata() {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + PACK_FORMAT + ",\n"
                + "    \"description\": \"" + PACK_DESCRIPTION + "\"\n"
                + "  }\n"
                + "}\n";
    }

    static String renderTag(Set<String> members) {
        StringBuilder values = new StringBuilder();
        for (String member : members) {
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