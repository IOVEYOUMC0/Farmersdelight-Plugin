package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts the in-memory recipe model back into the YAML string form the recipe parser understands
 * (i.e. the inverse of RecipeParsingSupport and the managers' parseRecipe methods).
 *
 * The string conversions here are pure functions (no Bukkit/CraftEngine state), so round-trip
 * consistency can be verified by unit tests. Item resolution (#itemIdString(ItemStack)) is the only
 * method that touches CraftEngine, which is why it is split out separately.
 */
public final class RecipeSerializer {

    private RecipeSerializer() {
    }

    /**
     * Serializes an ingredient (a cooking-pot ingredient slot, or a cutting-board input) into its YAML
     * string: an item key, a #tag with optional ,!exclusions, or a|b choices.
     */
    public static String serializeIngredient(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            return item.key().toString();
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return serializeTag(tag.key(), tag.excludedItems(), tag.excludedTags());
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            StringBuilder builder = new StringBuilder();
            List<RecipeIngredient> options = choice.options();
            for (int i = 0; i < options.size(); i++) {
                if (i > 0) {
                    builder.append('|');
                }
                builder.append(serializeIngredient(options.get(i)));
            }
            return builder.toString();
        }
        return "";
    }

    /**
     * Serializes a cutting-board tool requirement. The tool key matches as either an item id or a tag,
     * treated identically, so it is output as the raw key plus any ,!exclusions.
     */
    public static String serializeTool(CuttingBoardRecipe.ToolRequirement tool) {
        return serializeKeyWithExclusions(tool.getKey().toString(), tool.getExcludedItems(), tool.getExcludedTags());
    }

    private static String serializeTag(Key key, Set<Key> excludedItems, Set<Key> excludedTags) {
        return serializeKeyWithExclusions("#" + key, excludedItems, excludedTags);
    }

    private static String serializeKeyWithExclusions(String base, Set<Key> excludedItems, Set<Key> excludedTags) {
        StringBuilder builder = new StringBuilder(base);
        // Sort exclusions so serialization is deterministic (the sets are unordered).
        List<String> exclusions = new ArrayList<>();
        for (Key excludedItem : excludedItems) {
            exclusions.add(",!" + excludedItem);
        }
        for (Key excludedTag : excludedTags) {
            exclusions.add(",!#" + excludedTag);
        }
        Collections.sort(exclusions);
        for (String exclusion : exclusions) {
            builder.append(exclusion);
        }
        return builder.toString();
    }

    /**
     * Resolves an item stack to a recipe item id string: its CraftEngine custom id if present,
     * otherwise minecraft:<material>.
     */
    public static String itemIdString(ItemStack item) {
        if (item == null) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        return "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
    }
}
