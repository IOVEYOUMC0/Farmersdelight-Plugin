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

import java.util.*;
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
            // 对于格式正确但未知的 id，Registry.get 返回 null（而不是抛出异常），因此拼写错误的
            // block id 否则会在没有任何诊断信息的情况下向集合中添加一个 null。
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
        if (tagId.contains("campfires") || tagId.contains("fire")) {
            // 篝火只有在点燃时才算作热源，因此它们通过下面的 vanillaLitBlocks 进行特殊处理，
            // 而不是使用通用的标签匹配（后者会忽略点燃状态）。
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
