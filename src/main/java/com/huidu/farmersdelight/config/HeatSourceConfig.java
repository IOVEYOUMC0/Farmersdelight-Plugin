package com.huidu.farmersdelight.config;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.properties.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeatSourceConfig {

    private static final Pattern BLOCK_STATE_PATTERN = Pattern.compile(
            "^([a-z0-9_]+:[a-z0-9_]+)(?:\\[([^\\]]+)\\])?$"
    );

    private static Logger LOGGER;
    private final Set<Material> vanillaBlocks = new HashSet<>();
    private final Set<Material> vanillaLitBlocks = new HashSet<>();
    private final Set<String> vanillaTags = new HashSet<>();
    private final Set<Key> customBlockTags = new HashSet<>();
    private final Set<CustomBlockStateMatcher> customBlockStates = new HashSet<>();
    private final Set<Material> conductors = new HashSet<>();
    private final Set<Key> conductorTags = new HashSet<>();

    public static void setLogger(Logger logger) {
        LOGGER = logger;
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
    }

    public void addVanillaBlock(String blockId) {
        try {
            Material material = Material.valueOf(blockId.replace("minecraft:", "").toUpperCase());
            vanillaBlocks.add(material);
        } catch (IllegalArgumentException e) {
            if (LOGGER != null) {
                LOGGER.warning("Invalid vanilla block ID in heat source config: " + blockId);
            }
        }
    }

    public void addVanillaTag(String tagId) {
        vanillaTags.add(tagId);
        if (tagId.contains("campfires") || tagId.contains("fire")) {
            vanillaLitBlocks.add(Material.CAMPFIRE);
            vanillaLitBlocks.add(Material.SOUL_CAMPFIRE);
        }
    }

    public void addCustomBlockTag(Key tag) {
        customBlockTags.add(tag);
    }

    public void addConductorTag(Key tag) {
        conductorTags.add(tag);
    }

    public void addVanillaConductor(String conductorId) {
        try {
            Material material = Material.valueOf(conductorId.replace("minecraft:", "").toUpperCase());
            conductors.add(material);
        } catch (IllegalArgumentException e) {
            if (LOGGER != null) {
                LOGGER.warning("Invalid vanilla conductor material: " + conductorId);
            }
        }
    }

    private CustomBlockStateMatcher parseBlockState(String input) {
        Matcher matcher = BLOCK_STATE_PATTERN.matcher(input);
        if (!matcher.matches()) return null;

        String blockId = matcher.group(1);
        String propertiesStr = matcher.group(2);

        Map<String, String> requiredProperties = new HashMap<>();
        if (propertiesStr != null && !propertiesStr.isEmpty()) {
            for (String prop : propertiesStr.split(",")) {
                String[] parts = prop.split("=", 2);
                if (parts.length == 2) {
                    requiredProperties.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        return new CustomBlockStateMatcher(Key.of(blockId), requiredProperties);
    }

    public boolean isHeatSource(Block block) {
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

        ImmutableBlockState customState = CraftEngineBlocks.getCustomBlockState(block);
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
        if (conductors.contains(block.getType())) {
            return true;
        }

        ImmutableBlockState customState = CraftEngineBlocks.getCustomBlockState(block);
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
