package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

public record CuttingBoardRecipe(String id, RecipeIngredient input, ItemStack inputDisplay, List<ToolRequirement> tools,
                                 List<ResultEntry> results, String sound, int priority) {
    public String getId() {
        return id;
    }

    public RecipeIngredient getInput() {
        return input;
    }

    public ItemStack getInputDisplay() {
        return inputDisplay;
    }

    public List<ToolRequirement> getTools() {
        return tools;
    }

    public List<ResultEntry> getResults() {
        return results;
    }

    public String getSound() {
        return sound;
    }

    public int getPriority() {
        return priority;
    }

    public record ToolRequirement(Key key, Set<Key> excludedItems, Set<Key> excludedTags) {
        public ToolRequirement(Key key) {
            this(key, Set.of(), Set.of());
        }

        public Key getKey() {
            return key;
        }

        public Set<Key> getExcludedItems() {
            return excludedItems;
        }

        public Set<Key> getExcludedTags() {
            return excludedTags;
        }
    }

    public record ResultEntry(ItemStack item, double chance) {
        public ResultEntry(ItemStack item) {
            this(item, 1.0d);
        }

        public ItemStack getItem() {
            return item;
        }

        public double getChance() {
            return chance;
        }
    }
}
