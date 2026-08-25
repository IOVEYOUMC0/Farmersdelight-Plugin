package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for tag → concrete-item mappings across the whole plugin family. It satisfies two
 * needs that Paper/CraftEngine cannot natively provide:
 *
 * 1. NeoForge/Fabric "common tag" conventions (c:drinks/milk, c:foods/cooked_fish, ...). Recipes
 *    written against those tags resolve their members here so recipe matching, index lookup and the
 *    recipe-book icons agree with the original mod.
 * 2. Per-addon tag registration: every addon ships its own tags config inside ITS data folder and, at
 *    enable time, registers the mapping into this central registry so the whole family resolves the
 *    same tags "under one roof". FD's own default lives in <dataFolder>/common-tags.yml.
 *
 * All sources are merged (union of members per tag). Effective maps are immutable snapshots published
 * whole on any change so region-thread readers never observe a half-built map.
 */
public final class CommonTagResolver {

    public static final String FILE_NAME = "common-tags.yml";
    private static final String ROOT_KEY = "tags";
    private static final String BUILTIN_SOURCE = "farmersdelight";

    // Externally registered sources (addons). Guarded by this class's monitor during merge; reads.
    // never touch it directly. Values and member sets are immutable after registration.
    private static final Map<String, Map<String, Set<String>>> externals = new ConcurrentHashMap<>();

    // FD's own default, loaded from <dataFolder>/common-tags.yml. Immutable snapshot.
    private static volatile Map<String, Set<String>> builtin = Map.of();

    // Effective merged snapshots, published whole on every change.
    private static volatile Map<String, Set<String>> tagToItems = Map.of();
    private static volatile Map<String, Set<String>> itemToTags = Map.of();
    private static volatile boolean loaded = false;

    private CommonTagResolver() {
    }

    /** Reloads FD's own default config and re-merges all externally registered sources. */
    public static synchronized void reload(JavaPlugin plugin) {
        builtin = loadFile(plugin);
        rebuild();
    }

    /**
     * Registers (or replaces) an addon's tag mapping. Members are merged across sources, so multiple
     * addons may contribute to the same tag (e.g. every addon adds its knives to farmersdelight:tools/knives).
     */
    public static synchronized void registerSource(String source, Map<String, List<String>> tagToMemberItems) {
        if (source == null || tagToMemberItems == null) {
            return;
        }
        Map<String, Set<String>> frozen = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : tagToMemberItems.entrySet()) {
            String tag = normalize(entry.getKey());
            Set<String> members = new HashSet<>();
            for (String itemId : entry.getValue()) {
                String id = itemId == null ? "" : itemId.trim();
                if (!id.isEmpty()) {
                    members.add(id);
                }
            }
            if (!members.isEmpty()) {
                frozen.put(tag, Set.copyOf(members));
            }
        }
        externals.put(source, Map.copyOf(frozen));
        rebuild();
    }

    /** Removes a previously registered addon source (idempotent). */
    public static synchronized void unregisterSource(String source) {
        if (source == null) {
            return;
        }
        externals.remove(source);
        rebuild();
    }

    /** True if the given tag key is registered in this central registry. */
    public static boolean isCommonTag(String tagKey) {
        return loaded && tagToItems.containsKey(normalize(tagKey));
    }

    /** Concrete item ids the given tag expands to (empty when unregistered). */
    public static Set<String> getMembers(String tagKey) {
        return tagToItems.getOrDefault(normalize(tagKey), Set.of());
    }

    public static Set<String> getMembers(Key tagKey) {
        return tagKey == null ? Set.of() : getMembers(tagKey.toString());
    }

    /** Tags owned by the given concrete item id (matching any id form an item reports). */
    public static Set<String> getTagsForItemId(String itemId) {
        return itemId == null ? Set.of() : itemToTags.getOrDefault(itemId, Set.of());
    }

    /** Immutable snapshot of every registered tag and its concrete members, for datapack export. */
    public static Map<String, Set<String>> tagSnapshot() {
        return tagToItems;
    }

    private static void rebuild() {
        Map<String, Set<String>> mergedTagToItems = new HashMap<>(builtin);
        for (Map<String, Set<String>> source : externals.values()) {
            for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
                mergedTagToItems.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
            }
        }
        Map<String, Set<String>> mergedItemToTags = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : mergedTagToItems.entrySet()) {
            for (String member : entry.getValue()) {
                mergedItemToTags.computeIfAbsent(member, k -> new HashSet<>()).add(entry.getKey());
            }
        }
        Map<String, Set<String>> frozenTagToItems = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : mergedTagToItems.entrySet()) {
            frozenTagToItems.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        Map<String, Set<String>> frozenItemToTags = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : mergedItemToTags.entrySet()) {
            frozenItemToTags.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        tagToItems = Map.copyOf(frozenTagToItems);
        itemToTags = Map.copyOf(frozenItemToTags);
        loaded = true;
    }

    private static Map<String, Set<String>> loadFile(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists() && plugin.getResource(FILE_NAME) != null) {
            plugin.saveResource(FILE_NAME, false);
        }
        Map<String, Set<String>> result = new HashMap<>();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection(ROOT_KEY);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String tag = normalize(key);
                Set<String> members = new HashSet<>();
                for (String itemId : section.getStringList(key)) {
                    String id = itemId.trim();
                    if (!id.isEmpty()) {
                        members.add(id);
                    }
                }
                if (!members.isEmpty()) {
                    result.put(tag, Set.copyOf(members));
                }
            }
        }
        return Map.copyOf(result);
    }

    private static String normalize(String tagKey) {
        if (tagKey == null) {
            return "";
        }
        return tagKey.startsWith("#") ? tagKey.substring(1) : tagKey;
    }
}