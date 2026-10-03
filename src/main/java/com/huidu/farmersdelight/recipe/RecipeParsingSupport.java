package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class RecipeParsingSupport {

    /** Written in front of a group name, e.g. advtag:meats. */
    private static final String GROUP_PREFIX = "advtag:";

    /**
     * The groups the loaded packs declare, replaced on every reload. Starts empty so that a recipe naming
     * a group refuses to load rather than matching nothing before the packs have been read.
     */
    private static volatile AdvancedTagGroups advancedTagGroups = AdvancedTagGroups.EMPTY;

    private RecipeParsingSupport() {
    }

    static void setAdvancedTagGroups(AdvancedTagGroups groups) {
        advancedTagGroups = groups == null ? AdvancedTagGroups.EMPTY : groups;
    }

    static RecipeIngredient parseSimpleItemOrTag(String str) {
        if (isGroupReference(str)) {
            return groupIngredient(str.substring(GROUP_PREFIX.length()).trim());
        }
        if (!str.startsWith("#")) {
            return new RecipeIngredient.Item(Key.of(str));
        }
        return new RecipeIngredient.Tag(Key.of(str.substring(1)));
    }

    private static boolean isGroupReference(String str) {
        return str.regionMatches(true, 0, GROUP_PREFIX, 0, GROUP_PREFIX.length());
    }

    /**
     * Expands a group into the choice of the items it lists.
     *
     * <p>A group that is unknown, was dropped while resolving, or lists nothing is refused rather than turned
     * into an ingredient that can never match: a recipe that quietly stops being craftable is worse than one
     * that fails to load, where the operator can see which definition was wrong.
     */
    private static RecipeIngredient groupIngredient(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Advanced tag reference needs a group name");
        }
        Key group = Key.of(name);
        AdvancedTagGroups groups = advancedTagGroups;
        if (groups.dropped().contains(group)) {
            throw new IllegalArgumentException("Advanced tag group " + group + " could not be resolved");
        }
        List<Key> members = groups.members(group);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("Advanced tag group " + group + " is not declared or lists nothing");
        }
        List<RecipeIngredient> options = new ArrayList<>(members.size());
        for (Key member : members) {
            options.add(new RecipeIngredient.Item(member));
        }
        return options.size() == 1 ? options.getFirst() : new RecipeIngredient.Choice(options);
    }

    // Splits a choice ingredient on '|', trims each option, drops empties, and collapses a single-option
    // choice to that option; a string without '|' is passed through to leafParser as-is (each caller
    // applies its own single-token trimming). The leaf parser turns one option token into a RecipeIngredient.
    static RecipeIngredient parseChoice(String str, Function<String, RecipeIngredient> leafParser) {
        String[] choiceParts = str.split("\\|");
        if (choiceParts.length > 1) {
            List<RecipeIngredient> options = new ArrayList<>();
            for (String choicePart : choiceParts) {
                String trimmed = choicePart.trim();
                if (!trimmed.isEmpty()) {
                    options.add(leafParser.apply(trimmed));
                }
            }
            if (options.isEmpty()) {
                throw new IllegalArgumentException("Choice ingredient must contain at least one option");
            }
            if (options.size() == 1) {
                return options.getFirst();
            }
            return new RecipeIngredient.Choice(options);
        }
        return leafParser.apply(str);
    }

    static RecipeIngredient parseIngredientChoice(String str) {
        return parseChoice(str, option -> {
            String trimmed = option.trim();
            return trimmed.startsWith("#")
                    ? parseTagIngredientWithExclusions(trimmed)
                    : parseSimpleItemOrTag(trimmed);
        });
    }

    /** Parses both the legacy string form and editor-generated item/choice maps. */
    static RecipeIngredient parseIngredientValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Object choice = raw.get("choice");
            if (choice instanceof List<?> options) {
                List<RecipeIngredient> parsed = new ArrayList<>(options.size());
                for (Object option : options) {
                    RecipeIngredient ingredient = parseIngredientValue(option);
                    if (ingredient != null) {
                        parsed.add(ingredient);
                    }
                }
                if (parsed.isEmpty()) {
                    throw new IllegalArgumentException("Choice ingredient must contain at least one option");
                }
                return parsed.size() == 1 ? parsed.getFirst() : new RecipeIngredient.Choice(parsed);
            }
            Object item = raw.get("item");
            if (item != null) {
                String itemId = item.toString();
                if (isGroupReference(itemId.trim())) {
                    return groupIngredient(itemId.trim().substring(GROUP_PREFIX.length()).trim());
                }
                Object nbt = raw.get("nbt");
                return new RecipeIngredient.Item(Key.of(itemId),
                        nbt == null || nbt.toString().isBlank() ? null : nbt.toString());
            }
            throw new IllegalArgumentException("Ingredient map must contain item or choice");
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Ingredient cannot be empty");
        }
        return parseIngredientChoice(value.toString());
    }

    static RecipeIngredient.Tag parseTagIngredientWithExclusions(String str) {
        ParsedKey parsed = parseKeyWithExclusions(str, "ingredient");
        return new RecipeIngredient.Tag(parsed.key(), parsed.excludedItems(), parsed.excludedTags());
    }

    static ParsedKey parseKeyWithExclusions(String str, String contextName) {
        String[] parts = str.split(",");
        String baseToken = parts[0].trim();
        if (baseToken.isEmpty()) {
            throw new IllegalArgumentException(contextName + " cannot be empty");
        }

        Key baseKey = baseToken.startsWith("#")
                ? Key.of(baseToken.substring(1))
                : Key.of(baseToken);
        Set<Key> excludedItems = new HashSet<>();
        Set<Key> excludedTags = new HashSet<>();

        for (int i = 1; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.isEmpty()) {
                continue;
            }
            if (!token.startsWith("!")) {
                throw new IllegalArgumentException("Invalid exclusion token '" + token + "' in " + contextName + ": " + str);
            }

            String exclusion = token.substring(1).trim();
            if (exclusion.isEmpty()) {
                continue;
            }
            if (exclusion.startsWith("#")) {
                excludedTags.add(Key.of(exclusion.substring(1)));
            } else {
                excludedItems.add(Key.of(exclusion));
            }
        }

        return new ParsedKey(baseKey, baseToken.startsWith("#"),
                Set.copyOf(excludedItems), Set.copyOf(excludedTags));
    }

    record ParsedKey(Key key, boolean tag, Set<Key> excludedItems, Set<Key> excludedTags) {
    }
}
