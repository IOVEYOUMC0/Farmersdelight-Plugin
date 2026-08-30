package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecipeSerializer {

    private RecipeSerializer() {
    }

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

    /** Returns the YAML value used by the editor, retaining an optional item snapshot. */
    public static Object serializeIngredientValue(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item && item.nbt() != null) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("item", item.key().toString());
            value.put("nbt", item.nbt());
            return value;
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            List<Object> values = new ArrayList<>(choice.options().size());
            boolean hasSnapshot = false;
            for (RecipeIngredient option : choice.options()) {
                Object value = serializeIngredientValue(option);
                values.add(value);
                hasSnapshot |= value instanceof Map<?, ?>;
            }
            if (hasSnapshot) {
                return Map.of("choice", values);
            }
        }
        return serializeIngredient(ingredient);
    }

    public static String serializeTool(CuttingBoardRecipe.ToolRequirement tool) {
        String base = tool.isTag() ? "#" + tool.getKey() : tool.getKey().toString();
        return serializeKeyWithExclusions(base, tool.getExcludedItems(), tool.getExcludedTags());
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

    public static String itemIdString(ItemStack item) {
        if (item == null) {
            return null;
        }
        // Resolve the full identity (CE custom id -> mmoitems:<TYPE>:<ID> -> vanilla id) so an
        // ingredient or tool carrying an MMOItems identity survives the editor's save round-trip.
        return ItemUtils.resolveItemId(item);
    }
}
