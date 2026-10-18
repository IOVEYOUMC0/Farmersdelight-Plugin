package com.huidu.farmersdelight.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.huidu.farmersdelight.api.enchant.EnchantmentDefinition;
import com.huidu.farmersdelight.api.util.DatapackSupport;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.huidu.farmersdelight.api.util.DatapackSupport.writeIfChanged;

/** Registry file generation shared by bootstrap and runtime, without plugin or CraftEngine classes. */
public final class FarmersDelightDatapacks {
    public static final String TAG_PACK = "farmersdelight_tags";
    public static final String ENCHANT_PACK = "farmersdelight_enchant";
    public static final String DAMAGE_PACK = "farmersdelight_damage";
    private static final String PACK_DESCRIPTION = "FarmersDelight configurable enchantments";
    private static final String VANILLA_NAMESPACE = "minecraft";
    private static final int MIN_COST_BASE = 15;
    private static final int MIN_COST_PER_LEVEL = 9;
    private static final int MAX_COST_BASE = 50;
    private static final int MAX_COST_PER_LEVEL = 8;
    private static final int ANVIL_COST = 2;
    private static final String FALLBACK_NAME = "Backstabbing";
    private static final List<String> SLOTS = List.of("mainhand");
    private static final String SUPPORTED_ITEMS_TAG = "farmersdelight:enchantable/knife";
    private static final List<String> SUPPORTED_ITEMS = List.of();
    private static final List<String> DISTRIBUTION_TAGS = List.of(
            "tradeable",
            "treasure",
            "on_random_loot"
    );


    private FarmersDelightDatapacks() {}

    public static void prepareEnchantments(Path root, EnchantmentSettings settings, String version) throws IOException {
        Path previousIdFile = root.resolve(".backstabbing-id");
        String previousId = Files.isRegularFile(previousIdFile)
                ? Files.readString(previousIdFile, StandardCharsets.UTF_8).trim() : "farmersdelight:backstabbing";
        String currentId = settings.backstabbing().id();
        boolean enabled = settings.enabled() && settings.backstabbing().enabled();
        if (!enabled || !previousId.equals(currentId)) {
            Files.deleteIfExists(enchantmentFile(root, NamespacedId.parse(previousId)));
        }
        if (enabled) {
            writeIfChanged(enchantmentFile(root, NamespacedId.parse(currentId)), renderDefinition(settings.backstabbing()));
        }
        NamespacedId tag = NamespacedId.parse(SUPPORTED_ITEMS_TAG);
        writeIfChanged(root.resolve("data").resolve(tag.namespace()).resolve("tags/item")
                .resolve(tag.path() + ".json"), renderSupportedItems());
        for (String distributionTag : DISTRIBUTION_TAGS) {
            Path file = root.resolve("data/minecraft/tags/enchantment/" + distributionTag + ".json");
            Set<String> ids = new LinkedHashSet<>();
            if (Files.isRegularFile(file)) {
                JsonArray values = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonArray("values");
                for (var value : values) {
                    String id = value.getAsString();
                    if (!id.equals(previousId) && Files.isRegularFile(enchantmentFile(root, NamespacedId.parse(id)))) {
                        ids.add(id);
                    }
                }
            }
            if (enabled) ids.add(currentId);
            writeIfChanged(file, renderDistributionTag(List.copyOf(ids)));
        }
        writeIfChanged(previousIdFile, currentId);
        writeIfChanged(root.resolve("pack.mcmeta"), DatapackSupport.renderPackMetadata(PACK_DESCRIPTION, version));
    }

    public static String renderDefinition(EnchantmentSettings.Backstabbing backstabbing) {
        NamespacedId id = NamespacedId.parse(backstabbing.id());
        return renderEnchantJson(
                "enchantment." + id.namespace() + "." + id.path(), FALLBACK_NAME,
                backstabbing.definition().weight(), backstabbing.definition().maxLevel(),
                MIN_COST_BASE, MIN_COST_PER_LEVEL, MAX_COST_BASE, MAX_COST_PER_LEVEL, ANVIL_COST, SLOTS);
    }

    public static String renderDefinition(EnchantmentDefinition definition) {
        return renderEnchantJson(
                definition.translationKey(), definition.fallbackName(),
                definition.weight(), definition.maxLevel(),
                definition.minCostBase(), definition.minCostPerLevel(),
                definition.maxCostBase(), definition.maxCostPerLevel(),
                definition.anvilCost(), definition.slots());
    }

    // All FarmersDelight-managed enchants (built-in backstab + API-registered) share the one empty
    // supported-items tag, since the enchant filter — not vanilla supported_items — distributes them.
    private static String renderEnchantJson(String translationKey, String fallbackName, int weight, int maxLevel,
                                            int minCostBase, int minCostPerLevel, int maxCostBase,
                                            int maxCostPerLevel, int anvilCost, List<String> slots) {
        StringBuilder slotsJson = new StringBuilder();
        for (String slot : slots) {
            if (!slotsJson.isEmpty()) {
                slotsJson.append(", ");
            }
            slotsJson.append('"').append(json(slot.toLowerCase(Locale.ROOT))).append('"');
        }
        return "{\n"
                + "  \"description\": {\n"
                + "    \"translate\": \"" + json(translationKey) + "\",\n"
                + "    \"fallback\": \"" + json(fallbackName) + "\"\n"
                + "  },\n"
                + "  \"supported_items\": \"#" + json(SUPPORTED_ITEMS_TAG) + "\",\n"
                + "  \"weight\": " + weight + ",\n"
                + "  \"max_level\": " + maxLevel + ",\n"
                + "  \"min_cost\": {\"base\": " + minCostBase
                + ", \"per_level_above_first\": " + minCostPerLevel + "},\n"
                + "  \"max_cost\": {\"base\": " + maxCostBase
                + ", \"per_level_above_first\": " + maxCostPerLevel + "},\n"
                + "  \"anvil_cost\": " + anvilCost + ",\n"
                + "  \"slots\": [" + slotsJson + "],\n"
                + "  \"effects\": {}\n"
                + "}\n";
    }

    public static String renderSupportedItems() {
        StringBuilder values = new StringBuilder();
        for (String item : SUPPORTED_ITEMS) {
            if (!values.isEmpty()) {
                values.append(",\n");
            }
            values.append("    \"").append(json(item)).append('"');
        }
        return "{\n  \"replace\": true,\n  \"values\": ["
                + (values.isEmpty() ? "" : "\n" + values + "\n  ")
                + "]\n}\n";
    }

    public static String renderDistributionTag(List<String> enchantmentIds) {
        StringBuilder values = new StringBuilder();
        for (String id : enchantmentIds) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append('"').append(json(id)).append('"');
        }
        return "{\n"
                + "  \"replace\": false,\n"
                + "  \"values\": [" + values + "]\n"
                + "}\n";
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value == null ? 0 : value.length() + 8);
        if (value == null) {
            return "";
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static Path enchantmentFile(Path datapackDir, NamespacedId id) {
        return datapackDir.resolve("data")
                .resolve(id.namespace())
                .resolve("enchantment")
                .resolve(id.path() + ".json");
    }

    public static boolean writeTags(Path datapackDir, Map<String, Set<String>> snapshot, String version)
            throws IOException {
        Map<String, Set<String>> tags = vanillaTagSnapshot(snapshot);
        List<GeneratedFile> generated = new ArrayList<>();
        generated.add(new GeneratedFile(datapackDir.resolve("pack.mcmeta"), DatapackSupport.renderPackMetadata("FarmersDelight common-item tags (vanilla members)", version)));
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
        return changed > 0;
    }

    // Collects every registered tag but keeps only plain vanilla members. A member that resolves to no
    // item on the current server version is dropped, so the exported tag never references an unknown id
    // that could fail the pack load. Tags whose surviving members are all outside the "minecraft:"
    // namespace produce no file and stay declared in the CE item configs.
    private static Map<String, Set<String>> vanillaTagSnapshot(Map<String, Set<String>> snapshot) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : snapshot.entrySet()) {
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

    public static String renderTag(Set<String> members) {
        StringBuilder values = new StringBuilder();
        for (String member : members.stream().sorted().toList()) {
            if (!values.isEmpty()) {
                values.append(",\n");
            }
            values.append("    \"").append(member).append('"');
        }
        return "{\n  \"replace\": false,\n  \"values\": [\n" + values + "\n  ]\n}\n";
    }

    private record GeneratedFile(Path path, String content) {}

    private record NamespacedId(String namespace, String path) {
        static NamespacedId parse(String value) {
            int separator = value.indexOf(':');
            if (NamespacedKey.fromString(value) == null || value.contains("/../")
                    || value.endsWith("/..") || value.contains(":../") || value.contains(":./")
                    || value.contains("/./") || value.endsWith("/.") || separator <= 0) {
                throw new IllegalArgumentException("Invalid namespaced id: " + value);
            }
            return new NamespacedId(value.substring(0, separator), value.substring(separator + 1));
        }
    }
}
