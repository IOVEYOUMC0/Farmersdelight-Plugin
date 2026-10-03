package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeatSourceConfig {

    private static final Pattern BLOCK_STATE_PATTERN = Pattern.compile(
            "^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\\[([^\\]]+)\\])?$"
    );

    private static Logger LOGGER;
    // The live lookup tables double as the target of runtime api registrations (an addon may declare heat
    // sources at any time), while region threads iterate them on the block-interaction hot paths, so the
    // collections have to tolerate concurrent reads and writes. Entries are added, never removed in place, and
    // a /fd reload builds a fresh instance, which is why copy-on-write is cheap enough here.
    private final Set<Material> vanillaBlocks = ConcurrentHashMap.newKeySet();
    private final Set<Material> vanillaLitBlocks = ConcurrentHashMap.newKeySet();
    private final Set<String> vanillaTags = ConcurrentHashMap.newKeySet();
    private final Set<Tag<Material>> resolvedVanillaBlockTags = ConcurrentHashMap.newKeySet();
    private final Set<Key> customBlockTags = ConcurrentHashMap.newKeySet();
    private final Set<CustomBlockStateMatcher> customBlockStates = ConcurrentHashMap.newKeySet();
    private final Set<Material> conductors = ConcurrentHashMap.newKeySet();
    private final Set<Key> conductorTags = ConcurrentHashMap.newKeySet();
    // Unified entries, evaluated IN ORDER before the legacy lists below; the first entry that matches
    // decides. One entry carries both what to match (vanilla block / vanilla tag / CE block / CE block
    // tag, each optionally narrowed by block state) and what it means (heat source? conductor?), so a
    // negative entry placed earlier can carve a state out of a broader entry that follows it.
    private final List<HeatEntry> entries = new CopyOnWriteArrayList<>();

    public static void setLogger(Logger logger) {
        LOGGER = logger;
    }

    public void loadDefaults() {
        // Tag = "always a heat source in every state". Blocks with an on/off state are registered
        // below as explicit state matchers instead, and must NOT carry the tag in their CE config.
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

        // Read first so an entry can override anything the legacy lists below would have matched.
        loadEntries(section);

        List<String> vanillaBlockList = ConfigSectionReader.optionalStringList(section, "vanilla-blocks");
        for (String blockId : vanillaBlockList) {
            addVanillaBlock(blockId);
        }

        List<String> vanillaTagList = ConfigSectionReader.optionalStringList(section, "vanilla-tags");
        for (String tagId : vanillaTagList) {
            addVanillaTag(tagId);
        }

        List<String> tagList = ConfigSectionReader.optionalStringList(section, "tags");
        for (String tagId : tagList) {
            try {
                addCustomBlockTag(Key.of(tagId));
            } catch (IllegalArgumentException e) {
                I18n.logWarning("plugin.config_value_invalid", "file", "config.yml",
                        "path", section.getCurrentPath() + ".tags", "error", "invalid tag " + tagId);
            }
        }

        List<String> customBlockList = ConfigSectionReader.optionalStringList(section, "custom-blocks");
        for (String blockId : customBlockList) {
            try {
                CustomBlockStateMatcher matcher = parseBlockState(blockId);
                if (matcher != null) {
                    customBlockStates.add(matcher);
                    continue;
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to the same per-entry diagnostic as a malformed state expression.
            }
            {
                I18n.logWarning("plugin.config_value_invalid", "file", "config.yml",
                        "path", section.getCurrentPath() + ".custom-blocks", "error", "invalid block state " + blockId);
            }
        }

        List<String> conductorList = ConfigSectionReader.optionalStringList(section, "conductors");
        for (String conductorId : conductorList) {
            addVanillaConductor(conductorId);
        }

        List<String> conductorTagList = ConfigSectionReader.optionalStringList(section, "conductor-tags");
        for (String tagId : conductorTagList) {
            try {
                addConductorTag(Key.of(tagId));
            } catch (IllegalArgumentException e) {
                I18n.logWarning("plugin.config_value_invalid", "file", "config.yml",
                        "path", section.getCurrentPath() + ".conductor-tags", "error", "invalid tag " + tagId);
            }
        }
    }

    public void addVanillaBlock(String blockId) {
        try {
            NamespacedKey key = NamespacedKey.minecraft(blockId.replace("minecraft:", "").toLowerCase(Locale.ROOT));
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
        String normalizedTag = (tagId.startsWith("#") ? tagId.substring(1) : tagId).toLowerCase(Locale.ROOT);
        if (normalizedTag.equals("minecraft:campfires") || normalizedTag.equals("campfires")) {
            // Campfires count as heat sources only when lit, so vanillaLitBlocks handles the exact campfire tag.
            // Generic tag matching would ignore lit state and substring matching would also catch unrelated tags.
            vanillaLitBlocks.add(Material.CAMPFIRE);
            vanillaLitBlocks.add(Material.SOUL_CAMPFIRE);
            return;
        }
        Tag<Material> blockTag = resolveVanillaBlockTag(tagId);
        if (blockTag != null) {
            resolvedVanillaBlockTags.add(blockTag);
        } else if (LOGGER != null) {
            LOGGER.warning(I18n.formatConsole("heat_source.invalid_vanilla_block", "id", tagId));
        }
    }

    private static Tag<Material> resolveVanillaBlockTag(String tagId) {
        String normalized = (tagId.startsWith("#") ? tagId.substring(1) : tagId).toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key == null) {
            return null;
        }
        return Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
    }

    public void addCustomBlockTag(Key tag) {
        customBlockTags.add(tag);
    }

    /**
     * Registers a CraftEngine block as a heat source, optionally only in certain states
     * ("namespace:block" or "namespace:block[fire:true]").
     *
     *
     * Blocks that can be switched off MUST be registered this way rather than by tag: CraftEngine
     * copies the block-level settings (and therefore the tag list) onto every state, so a tag matches
     * the unlit state just as well as the lit one.
     *
     * @return false when the id could not be parsed
     */
    public boolean addCustomBlockState(String blockIdWithOptionalState) {
        CustomBlockStateMatcher matcher = parseBlockState(blockIdWithOptionalState);
        if (matcher == null) {
            if (LOGGER != null) {
                LOGGER.warning(I18n.formatConsole("heat_source.invalid_custom_block",
                        "id", String.valueOf(blockIdWithOptionalState)));
            }
            return false;
        }
        customBlockStates.add(matcher);
        return true;
    }

    public void addConductorTag(Key tag) {
        conductorTags.add(tag);
    }

    public void addVanillaConductor(String conductorId) {
        try {
            NamespacedKey key = NamespacedKey.minecraft(conductorId.replace("minecraft:", "").toLowerCase(Locale.ROOT));
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

    /** One configured heat-source rule: how to match a block, and what matching it means. */
    private record HeatEntry(EntryMatcher matcher, boolean heatSource, boolean conductor) {
    }

    private interface EntryMatcher {
        boolean matches(Block block, ImmutableBlockState customState);
    }

    private record VanillaBlockMatcher(Material material, Map<String, String> states) implements EntryMatcher {
        @Override
        public boolean matches(Block block, ImmutableBlockState customState) {
            return block.getType() == material && vanillaStatesMatch(block, states);
        }
    }

    private record VanillaTagMatcher(Tag<Material> tag, Map<String, String> states) implements EntryMatcher {
        @Override
        public boolean matches(Block block, ImmutableBlockState customState) {
            return tag.isTagged(block.getType()) && vanillaStatesMatch(block, states);
        }
    }

    private record CustomBlockMatcher(CustomBlockStateMatcher delegate) implements EntryMatcher {
        @Override
        public boolean matches(Block block, ImmutableBlockState customState) {
            return customState != null && !customState.isEmpty() && delegate.matches(customState);
        }
    }

    private record CustomTagMatcher(Key tag) implements EntryMatcher {
        @Override
        public boolean matches(Block block, ImmutableBlockState customState) {
            return customState != null && !customState.isEmpty() && customState.settings().tags().contains(tag);
        }
    }

    // Compares the requested properties against the block's real state. Reading them out of
    // BlockData#getAsString avoids a typed cast per property (Lightable, Ageable, ...) and works for
    // any vanilla block, which is the whole point of letting the config name states at all.
    private static boolean vanillaStatesMatch(Block block, Map<String, String> required) {
        if (required.isEmpty()) {
            return true;
        }
        String data = block.getBlockData().getAsString();
        int open = data.indexOf('[');
        if (open < 0 || !data.endsWith("]")) {
            return false;
        }
        Map<String, String> actual = new HashMap<>();
        for (String pair : data.substring(open + 1, data.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                actual.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (!entry.getValue().equalsIgnoreCase(actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private void loadEntries(ConfigurationSection section) {
        for (Map<?, ?> raw : ConfigSectionReader.optionalMapList(section, "entries")) {
            HeatEntry entry = parseEntry(raw, section);
            if (entry != null) {
                entries.add(entry);
            }
        }
    }

    private HeatEntry parseEntry(Map<?, ?> raw, ConfigurationSection section) {
        String vanillaBlock = entryString(raw, "vanilla-block");
        String vanillaTag = entryString(raw, "vanilla-block-tag");
        String customBlock = entryString(raw, "custom-block");
        String customTag = entryString(raw, "custom-block-tag");
        Map<String, String> states = entryStates(raw);
        boolean heatSource = entryBoolean(raw, "heat-source", true);
        boolean conductor = entryBoolean(raw, "conductor", false);

        EntryMatcher matcher = null;
        if (vanillaBlock != null) {
            Material material = Registry.MATERIAL.get(NamespacedKey.minecraft(
                    vanillaBlock.replace("minecraft:", "").toLowerCase(Locale.ROOT)));
            matcher = material == null ? null : new VanillaBlockMatcher(material, states);
        } else if (vanillaTag != null) {
            Tag<Material> tag = resolveVanillaBlockTag(vanillaTag);
            matcher = tag == null ? null : new VanillaTagMatcher(tag, states);
        } else if (customBlock != null) {
            CustomBlockStateMatcher delegate = parseBlockState(appendStates(customBlock, states));
            matcher = delegate == null ? null : new CustomBlockMatcher(delegate);
        } else if (customTag != null) {
            try {
                matcher = new CustomTagMatcher(Key.of(customTag));
            } catch (IllegalArgumentException ignored) {
                matcher = null;
            }
        }

        if (matcher == null) {
            I18n.logWarning("plugin.config_value_invalid", "file", "config.yml",
                    "path", (section == null ? "heat-sources" : section.getCurrentPath()) + ".entries",
                    "error", "entry matches nothing: " + raw);
            return null;
        }
        return new HeatEntry(matcher, heatSource, conductor);
    }

    // The CE matcher is already driven by the "id[key:value]" syntax, so a states map is folded into it
    // instead of duplicating the property-comparison logic.
    private static String appendStates(String blockId, Map<String, String> states) {
        if (states.isEmpty()) {
            return blockId;
        }
        StringBuilder builder = new StringBuilder(blockId).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : states.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append(':').append(entry.getValue());
            first = false;
        }
        return builder.append(']').toString();
    }

    private static String entryString(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean entryBoolean(Map<?, ?> raw, String key, boolean defaultValue) {
        Object value = raw.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static Map<String, String> entryStates(Map<?, ?> raw) {
        Object value = raw.get("states");
        if (!(value instanceof Map<?, ?> stateMap)) {
            return Map.of();
        }
        Map<String, String> states = new HashMap<>();
        for (Map.Entry<?, ?> entry : stateMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                states.put(String.valueOf(entry.getKey()).trim(), String.valueOf(entry.getValue()).trim());
            }
        }
        return states;
    }

    // Returns the first entry matching this block, or null when no entry does. Entries are state-aware, so an
    // "unlit campfire is not a heat source" rule really means cold: every caller asks the same question.
    private HeatEntry firstMatchingEntry(Block block, ImmutableBlockState preFetchedState) {
        if (entries.isEmpty()) {
            return null;
        }
        ImmutableBlockState customState = preFetchedState;
        boolean customResolved = preFetchedState != null;
        for (HeatEntry entry : entries) {
            if (!customResolved && needsCustomState(entry.matcher())) {
                customState = CraftEngineBlocks.getCustomBlockState(block);
                customResolved = true;
            }
            if (entry.matcher().matches(block, customState)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean needsCustomState(EntryMatcher matcher) {
        return matcher instanceof CustomBlockMatcher || matcher instanceof CustomTagMatcher;
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
        HeatEntry entry = firstMatchingEntry(block, preFetchedState);
        if (entry != null) {
            return entry.heatSource();
        }

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

        for (Tag<Material> tag : resolvedVanillaBlockTags) {
            if (tag.isTagged(blockType)) {
                return true;
            }
        }

        ImmutableBlockState customState = customStateFor(block, preFetchedState);
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

    // Resolves the CraftEngine state only when a custom rule exists: with none configured the answer cannot
    // change, and the lookup goes through the CE proxy for every queried block.
    private ImmutableBlockState customStateFor(Block block, ImmutableBlockState preFetchedState) {
        if (preFetchedState != null) {
            return preFetchedState;
        }
        if (customBlockStates.isEmpty() && customBlockTags.isEmpty()) {
            return null;
        }
        return CraftEngineBlocks.getCustomBlockState(block);
    }

    public boolean isConductor(Block block) {
        return isConductor(block, null);
    }

    public boolean isConductor(Block block, ImmutableBlockState preFetchedState) {
        HeatEntry entry = firstMatchingEntry(block, preFetchedState);
        if (entry != null) {
            return entry.conductor();
        }

        if (conductors.contains(block.getType())) {
            return true;
        }

        // No conductor tag configured means the answer cannot change, and the CE state read below goes through
        // the proxy (which reads the chunk) for every queried block. customStateFor applies the same short
        // circuit; keep both in step.
        if (conductorTags.isEmpty()) {
            return false;
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

                Comparable<?> expectedValue = property.valueByName(requiredValue);
                if (expectedValue == null || !actualValue.equals(expectedValue)) {
                    return false;
                }
            }

            return true;
        }
    }
}
