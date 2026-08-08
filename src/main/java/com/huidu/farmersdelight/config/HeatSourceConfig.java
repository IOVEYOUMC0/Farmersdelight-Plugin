package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeatSourceConfig {

    private static final Pattern BLOCK_STATE_PATTERN = Pattern.compile(
            "^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\\[([^\\]]+)\\])?$"
    );

    private static Logger LOGGER;
    private final Set<Material> vanillaBlocks = new HashSet<>();
    private final Set<Material> vanillaLitBlocks = new HashSet<>();
    private final Set<String> vanillaTags = new HashSet<>();
    private final Set<org.bukkit.Tag<Material>> resolvedVanillaBlockTags = new HashSet<>();
    private final Set<Key> customBlockTags = new HashSet<>();
    private final Set<CustomBlockStateMatcher> customBlockStates = new HashSet<>();
    private final Set<Material> conductors = new HashSet<>();
    private final Set<Key> conductorTags = new HashSet<>();

    public static void setLogger(Logger logger) {
        LOGGER = logger;
    }

    public void loadDefaults() {
        addCustomBlockTag(Key.of("farmersdelight:heat_sources"));
        addVanillaBlock("minecraft:magma_block");
        addVanillaBlock("minecraft:lava_cauldron");
        addVanillaBlock("minecraft:lava");
        addVanillaBlock("minecraft:fire");
        addVanillaBlock("minecraft:soul_fire");
        addVanillaTag("minecraft:campfires");
        CustomBlockStateMatcher stove = parseBlockState("farmersdelight:stove[fire:true]");
        if (stove != null) {
            customBlockStates.add(stove);
        }
        addVanillaConductor("minecraft:hopper");
    }

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;

        List<String> vanillaBlockList = section.getStringList("vanilla-blocks");
        for (String blockId : vanillaBlockList) {
            addVanillaBlock(blockId);
        }

        List<String> vanillaTagList = section.getStringList("vanilla-tags");
        for (String tagId : vanillaTagList) {
            addVanillaTag(tagId);
        }

        List<String> tagList = section.getStringList("tags");
        for (String tagId : tagList) {
            addCustomBlockTag(Key.of(tagId));
        }

        List<String> customBlockList = section.getStringList("custom-blocks");
        for (String blockId : customBlockList) {
            CustomBlockStateMatcher matcher = parseBlockState(blockId);
            if (matcher != null) {
                customBlockStates.add(matcher);
            }
        }

        List<String> conductorList = section.getStringList("conductors");
        for (String conductorId : conductorList) {
            addVanillaConductor(conductorId);
        }

        List<String> conductorTagList = section.getStringList("conductor-tags");
        for (String tagId : conductorTagList) {
            addConductorTag(Key.of(tagId));
        }
    }

    public void addVanillaBlock(String blockId) {
        try {
            NamespacedKey key = NamespacedKey.minecraft(blockId.replace("minecraft:", "").toLowerCase(java.util.Locale.ROOT));
            Material material = Registry.MATERIAL.get(key);
            // For well-formed but unknown ids, Registry.get returns null (rather than throwing), so a misspelled
            // block id would otherwise add a null to the set with no diagnostics.
            if (material == null) {
                if (LOGGER != null) {
                    LOGGER.warning(I18n.formatConsole("heat_source.invalid_vanilla_block", "id", blockId));
                }
                return;
            }
            vanillaBlocks.add(material);
        } catch (IllegalArgumentException e) {
            if (LOGGER != null) {
                LOGGER.warning(I18n.formatConsole("heat_source.invalid_vanilla_block", "id", blockId));
            }
        }
    }

    public void addVanillaTag(String tagId) {
        vanillaTags.add(tagId);
        String normalizedTag = (tagId.startsWith("#") ? tagId.substring(1) : tagId).toLowerCase(java.util.Locale.ROOT);
        if (normalizedTag.equals("minecraft:campfires") || normalizedTag.equals("campfires")) {
            // Campfires only count as heat sources when lit, so they are handled specially via vanillaLitBlocks below,
            // rather than generic tag matching (which would ignore the lit state). Match the exact tag id — the old
            // substring `contains("fire")` misrouted any tag whose id merely contained "fire"
            // (e.g. minecraft:soul_fire_base_blocks) into campfire-only handling.
            vanillaLitBlocks.add(Material.CAMPFIRE);
            vanillaLitBlocks.add(Material.SOUL_CAMPFIRE);
            return;
        }
        org.bukkit.Tag<Material> blockTag = resolveVanillaBlockTag(tagId);
        if (blockTag != null) {
            resolvedVanillaBlockTags.add(blockTag);
        } else if (LOGGER != null) {
            LOGGER.warning(I18n.formatConsole("heat_source.invalid_vanilla_block", "id", tagId));
        }
    }

    private static org.bukkit.Tag<Material> resolveVanillaBlockTag(String tagId) {
        String normalized = (tagId.startsWith("#") ? tagId.substring(1) : tagId).toLowerCase(java.util.Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key == null) {
            return null;
        }
        return org.bukkit.Bukkit.getTag(org.bukkit.Tag.REGISTRY_BLOCKS, key, Material.class);
    }

    public void addCustomBlockTag(Key tag) {
        customBlockTags.add(tag);
    }

    public void addConductorTag(Key tag) {
        conductorTags.add(tag);
    }

    public void addVanillaConductor(String conductorId) {
        try {
            NamespacedKey key = NamespacedKey.minecraft(conductorId.replace("minecraft:", "").toLowerCase(java.util.Locale.ROOT));
            Material material = Registry.MATERIAL.get(key);
            if (material == null) {
                if (LOGGER != null) {
                    LOGGER.warning(I18n.formatConsole("heat_source.invalid_vanilla_conductor", "id", conductorId));
                }
                return;
            }
            conductors.add(material);
        } catch (IllegalArgumentException e) {
            if (LOGGER != null) {
                LOGGER.warning(I18n.formatConsole("heat_source.invalid_vanilla_conductor", "id", conductorId));
            }
        }
    }

    private CustomBlockStateMatcher parseBlockState(String input) {
        if (input == null) {
            return null;
        }

        Matcher matcher = BLOCK_STATE_PATTERN.matcher(input.trim());
        if (!matcher.matches()) return null;

        String blockId = matcher.group(1);
        String propertiesStr = matcher.group(2);

        Map<String, String> requiredProperties = new HashMap<>();
        if (propertiesStr != null && !propertiesStr.isEmpty()) {
            for (String prop : propertiesStr.split(",")) {
                String[] parts = prop.contains("=") ? prop.split("=", 2) : prop.split(":", 2);
                if (parts.length == 2) {
                    requiredProperties.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        return new CustomBlockStateMatcher(Key.of(blockId), requiredProperties);
    }

    public boolean isHeatSource(Block block) {
        return isHeatSource(block, null);
    }

    public boolean isHeatSource(Block block, ImmutableBlockState preFetchedState) {
        Material blockType = block.getType();

        if (vanillaBlocks.contains(blockType)) {
            return true;
        }

        if (vanillaLitBlocks.contains(blockType)) {
            if (block.getBlockData() instanceof Lightable lightable) {
                return lightable.isLit();
            }
            return false;
        }

        for (org.bukkit.Tag<Material> tag : resolvedVanillaBlockTags) {
            if (tag.isTagged(blockType)) {
                return true;
            }
        }

        ImmutableBlockState customState = preFetchedState != null
                ? preFetchedState
                : CraftEngineBlocks.getCustomBlockState(block);
        if (customState != null && !customState.isEmpty()) {
            for (CustomBlockStateMatcher stateMatcher : customBlockStates) {
                if (stateMatcher.matches(customState)) {
                    return true;
                }
            }

            Set<Key> blockTags = customState.settings().tags();
            for (Key tag : customBlockTags) {
                if (blockTags.contains(tag)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isConductor(Block block) {
        return isConductor(block, null);
    }

    public boolean isConductor(Block block, ImmutableBlockState preFetchedState) {
        if (conductors.contains(block.getType())) {
            return true;
        }

        ImmutableBlockState customState = preFetchedState != null
                ? preFetchedState
                : CraftEngineBlocks.getCustomBlockState(block);
        if (customState != null && !customState.isEmpty()) {
            Set<Key> blockTags = customState.settings().tags();
            for (Key tag : conductorTags) {
                if (blockTags.contains(tag)) {
                    return true;
                }
            }
        }

        return false;
    }

    private record CustomBlockStateMatcher(Key blockId, Map<String, String> requiredProperties) {

        @SuppressWarnings("unchecked")
        public boolean matches(ImmutableBlockState state) {
            if (!state.owner().matchesKey(blockId)) {
                return false;
            }

            if (requiredProperties.isEmpty()) {
                return true;
            }

            for (Map.Entry<String, String> entry : requiredProperties.entrySet()) {
                String propertyName = entry.getKey();
                String requiredValue = entry.getValue();

                Property<?> property = state.owner().value().getProperty(propertyName);
                if (property == null) {
                    return false;
                }

                Comparable<?> actualValue = state.getNullable(property);
                if (actualValue == null) {
                    return false;
                }

                @SuppressWarnings("rawtypes")
                String actualValueStr = ((Property) property).valueName(actualValue);
                if (!actualValueStr.equalsIgnoreCase(requiredValue)) {
                    return false;
                }
            }

            return true;
        }
    }
}
