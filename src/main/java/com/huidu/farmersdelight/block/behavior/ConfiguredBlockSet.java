package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConfiguredBlockSet {

    public static final ConfiguredBlockSet EMPTY =
            new ConfiguredBlockSet(Set.of(), Set.of(), Set.of(), Set.of());

    private final Set<Material> materials;
    private final Set<String> customIds;
    private final Set<Key> tags;
    private final Set<Tag<Material>> vanillaTags;

    private ConfiguredBlockSet(Set<Material> materials,
                               Set<String> customIds,
                               Set<Key> tags,
                               Set<Tag<Material>> vanillaTags) {
        this.materials = materials;
        this.customIds = customIds;
        this.tags = tags;
        this.vanillaTags = vanillaTags;
    }

    public static ConfiguredBlockSet parse(Object rawValue) {
        if (rawValue != null && !(rawValue instanceof Iterable<?>)) {
            throw new KnownResourceException(ConfigConstants.PARSE_LIST_FAILED, "blocks", String.valueOf(rawValue));
        }
        if (!(rawValue instanceof Iterable<?> entries)) {
            return EMPTY;
        }
        // LinkedHashSet keeps the config's declaration order for callers that enumerate members
        // (e.g. the recipe GUI's catalyst slots render the list as written in blocks.yml).
        Set<Material> materials = new LinkedHashSet<>();
        Set<String> customIds = new LinkedHashSet<>();
        Set<Key> tags = new LinkedHashSet<>();
        Set<Tag<Material>> vanillaTags = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (entry == null) {
                continue;
            }
            String id = String.valueOf(entry).trim();
            if (id.isEmpty()) {
                continue;
            }
            if (id.charAt(0) == '#') {
                addTag(id, tags, vanillaTags);
            } else if (id.startsWith("minecraft:") || id.indexOf(':') < 0) {
                Material material = Material.matchMaterial(id);
                if (material != null) {
                    materials.add(material);
                }
            } else {
                customIds.add(id.toLowerCase(Locale.ROOT));
            }
        }
        if (materials.isEmpty() && customIds.isEmpty() && tags.isEmpty()) {
            return EMPTY;
        }
        return new ConfiguredBlockSet(materials, customIds, tags, vanillaTags);
    }

    private static void addTag(String entry, Set<Key> tags, Set<Tag<Material>> vanillaTags) {
        String id = entry.substring(1).trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return;
        }
        Key key = Key.of(id);
        tags.add(key);

        Tag<Material> vanillaTag = resolveVanillaTag(key);
        if (vanillaTag != null) {
            vanillaTags.add(vanillaTag);
        } else if (Key.MINECRAFT_NAMESPACE.equals(key.namespace())) {
            I18n.logWarning("block_set.unknown_tag", "tag", "#" + key);
        }
    }

    private static Tag<Material> resolveVanillaTag(Key key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key.toString());
        if (namespacedKey == null) {
            return null;
        }
        try {
            return Bukkit.getTag(Tag.REGISTRY_BLOCKS, namespacedKey, Material.class);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return null;
        }
    }

    public boolean isEmpty() {
        return this.materials.isEmpty() && this.customIds.isEmpty() && this.tags.isEmpty();
    }

    // Concrete members (vanilla ids + CraftEngine custom block ids) in config order; tags stay
    // separate so callers expand them through their own tag lookup.
    public List<String> memberIds() {
        List<String> ids = new ArrayList<>(this.materials.size() + this.customIds.size());
        for (Material material : this.materials) {
            ids.add("minecraft:" + material.name().toLowerCase(Locale.ROOT));
        }
        ids.addAll(this.customIds);
        return ids;
    }

    public Set<Key> tags() {
        return this.tags;
    }

    public Set<Key> vanillaTagKeys() {
        if (this.vanillaTags.isEmpty()) {
            return Set.of();
        }
        Set<Key> keys = new LinkedHashSet<>();
        for (Tag<Material> tag : this.vanillaTags) {
            keys.add(Key.of(tag.getKey().toString()));
        }
        return keys;
    }

    public boolean contains(Block block) {
        if (block == null || isEmpty()) {
            return false;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(block);
        String customId = CustomBlockUtils.getId(state);
        if (customId != null) {
            if (this.customIds.contains(customId.toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (this.tags.isEmpty()) {
                return false;
            }
            for (Key tag : state.settings().tags()) {
                if (this.tags.contains(tag)) {
                    return true;
                }
            }
            return false;
        }
        Material material = block.getType();
        if (this.materials.contains(material)) {
            return true;
        }
        for (Tag<Material> tag : this.vanillaTags) {
            if (tag.isTagged(material)) {
                return true;
            }
        }
        return false;
    }
}
