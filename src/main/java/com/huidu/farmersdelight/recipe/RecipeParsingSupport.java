package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;

import java.util.HashSet;
import java.util.Set;

final class RecipeParsingSupport {

    private RecipeParsingSupport() {
    }

    static RecipeIngredient parseSimpleItemOrTag(String str) {
        if (!str.startsWith("#")) {
            return new RecipeIngredient.Item(Key.of(str));
        }
        return new RecipeIngredient.Tag(Key.of(str.substring(1)));
    }

    static RecipeIngredient.Tag parseTagIngredientWithExclusions(String str, String contextName) {
        ParsedKey parsed = parseKeyWithExclusions(str, contextName);
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

        return new ParsedKey(baseKey, Set.copyOf(excludedItems), Set.copyOf(excludedTags));
    }

    record ParsedKey(Key key, Set<Key> excludedItems, Set<Key> excludedTags) {
    }
}
