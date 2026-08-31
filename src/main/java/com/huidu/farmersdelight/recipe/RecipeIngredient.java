package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

public sealed interface RecipeIngredient permits RecipeIngredient.Item, RecipeIngredient.Tag, RecipeIngredient.Choice {

    /** An exact item id, with an optional full-stack snapshot for unregistered custom items. */
    record Item(Key key, String nbt) implements RecipeIngredient {
        public Item(Key key) {
            this(key, null);
        }

        public static Item fromStack(ItemStack stack) {
            if (stack == null || stack.getType().isAir()) {
                throw new IllegalArgumentException("Ingredient item cannot be empty");
            }
            String id = RecipeSerializer.itemIdString(stack);
            if (id == null) {
                throw new IllegalArgumentException("Ingredient item has no resolvable id");
            }
            String snapshot = RecipeItemCodec.carriesExtraData(stack)
                    ? RecipeItemCodec.itemToBase64(stack) : null;
            return new Item(Key.of(id), snapshot);
        }

        public ItemStack createStack() {
            ItemStack snapshot = RecipeItemCodec.itemFromBase64(nbt);
            if (snapshot != null) {
                snapshot.setAmount(1);
                return snapshot;
            }
            return ItemUtils.createItem(key.toString());
        }
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

