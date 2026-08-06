package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.HashSet;
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
        if (!(rawValue instanceof Iterable<?> entries)) {
            return EMPTY;
        }
        Set<Material> materials = EnumSet.noneOf(Material.class);
        Set<String> customIds = new HashSet<>();
        Set<Key> tags = new HashSet<>();
        Set<Tag<Material>> vanillaTags = new HashSet<>();
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
