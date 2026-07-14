package com.huidu.farmersdelight.api.recipe;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A mutable draft of a recipe being edited in the generic editor. The editor GUI fills the item slots
 * and numeric values; the addon's RecipeEditor#save turns this back into its own recipe format.
 *
 * Lives in the name-stable api package; uses only Bukkit / java types.
 */
public final class EditableRecipe {

    private String id;
    private final List<ItemStack> items;
    private ItemStack result;
    private int resultCount = 1;
    private final Map<String, Double> numbers = new HashMap<>();

    public EditableRecipe(String id, int itemSlotCount) {
        this.id = id;
        this.items = new ArrayList<>(itemSlotCount);
        for (int i = 0; i < itemSlotCount; i++) {
            this.items.add(null);
        }
    }

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int itemSlotCount() {
        return items.size();
    }

    public ItemStack item(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public void setItem(int index, ItemStack item) {
        if (index >= 0 && index < items.size()) {
            items.set(index, item);
        }
    }

    public ItemStack result() {
        return result;
    }

    public void setResult(ItemStack result) {
        this.result = result;
    }

    public int resultCount() {
        return resultCount;
    }

    public void setResultCount(int resultCount) {
        this.resultCount = Math.max(1, resultCount);
    }

    public double number(String key, double fallback) {
        return numbers.getOrDefault(key, fallback);
    }

    public void setNumber(String key, double value) {
        numbers.put(key, value);
    }
}
