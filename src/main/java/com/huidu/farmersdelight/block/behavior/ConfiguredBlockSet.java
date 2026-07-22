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

/**
 * A set of block ids and block tags read from a behavior argument list, matched against a live block.
 *
 * Entries come in three shapes. A leading '#' marks a tag. A minecraft: entry, or one with no namespace at
 * all, becomes a Bukkit Material. Anything else stays a CraftEngine block id.
 *
 * Matching asks CraftEngine first and only falls back to the vanilla side for blocks CraftEngine does not
 * own. A custom block reports a configurable disguise material through Bukkit (bricks by default), so a
 * Material comparison can neither identify a custom block nor be trusted to have found a genuine vanilla
 * one, and a vanilla tag must never be allowed to match a custom block through that disguise.
 *
 * Tags therefore live on both sides of that split. A custom block is tested against the tag list it
 * declares in its own settings, since CraftEngine blocks inherit no vanilla tag. A vanilla block is tested
 * against the server's block tag registry, which covers datapack tags as well.
 *
 * CraftEngine keeps block tags only as a per-block set in BlockSettings and publishes no tag-to-blocks
 * index, so there is no way to ask whether a given tag is known to CraftEngine at all. Existence can only
 * be checked on the vanilla side: a minecraft: tag missing from the block tag registry is reported at
 * load, while a tag in any other namespace is assumed to be a CraftEngine tag and stays silent.
 */
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

    /** Parses a behavior argument value; anything that is not a list of ids yields the empty set. */
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
