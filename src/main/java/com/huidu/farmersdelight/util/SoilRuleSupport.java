package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SoilRuleSupport {

    // Resolved Bukkit block tags stay constant for the server's lifetime; cache them so the crop
    // grow/place hot path skips rerunning NamespacedKey.fromString + Bukkit.getTag per call.
    private static final Map<Key, Optional<Tag<Material>>> blockTagResolveCache = new ConcurrentHashMap<>();

    private SoilRuleSupport() {
    }

    public static SoilRules parseSoilRules(Map<String, Object> arguments) {
        Set<Key> tags = parseKeys(arguments, "bottom-block-tags");
        Set<Material> materials = new HashSet<>();
        Set<String> customBlockIds = new HashSet<>();
        List<BlockData> vanillaStates = new ArrayList<>();
        Set<String> customStateStrings = new HashSet<>();

        Object raw = arguments != null ? arguments.get("bottom-blocks") : null;
        if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value == null) {
                    continue;
                }
                String text = String.valueOf(value).trim();
                if (text.isEmpty()) {
                    continue;
                }

                try {
                    BlockData blockData = Bukkit.createBlockData(text);
                    if (text.contains("[")) {
                        vanillaStates.add(blockData);
                    } else {
                        materials.add(blockData.getMaterial());
                    }
                    continue;
                } catch (IllegalArgumentException ignored) {
                }

                String materialName = text.contains(":")
                        ? text.substring(text.indexOf(':') + 1)
                        : text;
                NamespacedKey nk = NamespacedKey.minecraft(materialName.toLowerCase(java.util.Locale.ROOT));
                Material material = Registry.MATERIAL.get(nk);
                if (material != null) {
                    materials.add(material);
                    continue;
                }

                if (text.contains("[")) {
                    customStateStrings.add(text);
                } else {
                    customBlockIds.add(text);
                }
            }
        }

        return new SoilRules(materials, tags, customBlockIds, vanillaStates, customStateStrings);
    }

    public static boolean matches(Block block, SoilRules configuredRules) {
        if (block == null || configuredRules == null || !configuredRules.isConfigured()) {
            return false;
        }

        if (configuredRules.materials().contains(block.getType())) {
            return true;
        }

        for (Key configuredTag : configuredRules.tags()) {
            Tag<Material> blockTag = blockTagResolveCache
                    .computeIfAbsent(configuredTag, SoilRuleSupport::resolveBlockTag)
                    .orElse(null);
            if (blockTag != null && blockTag.isTagged(block.getType())) {
                return true;
            }
        }

        BlockData currentData = block.getBlockData();
        for (BlockData allowedState : configuredRules.vanillaStates()) {
            if (allowedState.matches(currentData)) {
                return true;
            }
        }

        ImmutableBlockState configuredState = CraftEngineBlocks.getCustomBlockState(block);
        if (configuredState == null || configuredState.isEmpty()) {
            return false;
        }

        String customId = configuredState.owner().value().id().toString();
        if (configuredRules.customBlockIds().contains(customId)) {
            return true;
        }
        if (configuredRules.customStateStrings().contains(configuredState.toString())) {
            return true;
        }

        Set<Key> tags = configuredState.settings().tags();
        for (Key tag : tags) {
            if (configuredRules.tags().contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Tag<Material>> resolveBlockTag(Key configuredTag) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(configuredTag.toString());
        if (namespacedKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Bukkit.getTag("blocks", namespacedKey, Material.class));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static Set<Key> parseKeys(Map<String, Object> arguments, String key) {
        Object raw = arguments != null ? arguments.get(key) : null;
        if (!(raw instanceof Iterable<?> iterable)) {
            return Collections.emptySet();
        }

        Set<Key> result = new HashSet<>();
        for (Object value : iterable) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("#")) {
                text = text.substring(1);
            }
            result.add(Key.of(text));
        }
        return result;
    }

    public record SoilRules(
            Set<Material> materials,
            Set<Key> tags,
            Set<String> customBlockIds,
            List<BlockData> vanillaStates,
            Set<String> customStateStrings
    ) {
        public boolean isConfigured() {
            return !materials.isEmpty()
                    || !tags.isEmpty()
                    || !customBlockIds.isEmpty()
                    || !vanillaStates.isEmpty()
                    || !customStateStrings.isEmpty();
        }
    }
}

