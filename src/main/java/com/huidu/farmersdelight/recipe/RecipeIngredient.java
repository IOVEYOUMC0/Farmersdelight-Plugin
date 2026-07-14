package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;

import java.util.List;
import java.util.Set;

public sealed interface RecipeIngredient permits RecipeIngredient.Item, RecipeIngredient.Tag, RecipeIngredient.Choice {

    record Item(Key key) implements RecipeIngredient {
    }

    record Tag(Key key, Set<Key> excludedItems, Set<Key> excludedTags) implements RecipeIngredient {
        public Tag(Key key) {
            this(key, Set.of(), Set.of());
        }

        public boolean hasExclusions() {
            return !excludedItems.isEmpty() || !excludedTags.isEmpty();
        }
    }

    record Choice(List<RecipeIngredient> options) implements RecipeIngredient {
        public Choice {
            options = List.copyOf(options);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("Choice ingredient must contain at least one option");
            }
        }
    }
}

