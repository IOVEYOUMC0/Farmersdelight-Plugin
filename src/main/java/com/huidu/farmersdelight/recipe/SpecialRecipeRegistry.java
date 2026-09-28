package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SpecialRecipeRegistry {

    public static final String TYPE_ID = "farmersdelight:special";

    private final Map<String, SpecialRecipeInfo> recipes = new LinkedHashMap<>();
    private volatile LinkIndex linkIndex;

    public synchronized void register(SpecialRecipeInfo info) {
        if (info != null && info.id() != null) {
            recipes.put(info.id(), info);
            linkIndex = null;
        }
    }

    public synchronized void unregister(String id) {
        if (id != null) {
            recipes.remove(id);
            linkIndex = null;
        }
    }

    public synchronized void clear() {
        recipes.clear();
        linkIndex = null;
    }

    public synchronized SpecialRecipeInfo get(String id) {
        return id == null ? null : recipes.get(id);
    }

    public synchronized List<SpecialRecipeInfo> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(recipes.values()));
    }

    public synchronized boolean isEmpty() {
        return recipes.isEmpty();
    }

    public String findProducingRecipe(ItemStack item) {
        return find(linkIndex().producing(), item);
    }

    public String findLinkedRecipe(ItemStack item) {
        return find(linkIndex().linked(), item);
    }

    public synchronized void invalidateIndex() {
        linkIndex = null;
    }

    private String find(Map<String, String> index, ItemStack item) {
        String itemId = ItemUtils.resolveItemId(item);
        return itemId == null ? null : index.get(itemId);
    }

    private LinkIndex linkIndex() {
        LinkIndex current = linkIndex;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (linkIndex == null) {
                Map<String, String> producing = new LinkedHashMap<>();
                for (SpecialRecipeInfo info : recipes.values()) {
                    indexItem(producing, info.iconItemId(), info.id());
                    indexEntries(producing, info.outputSlots(), info.id());
                }
                Map<String, String> linked = new LinkedHashMap<>(producing);
                for (SpecialRecipeInfo info : recipes.values()) {
                    indexEntries(linked, info.inputSlots(), info.id());
                    indexEntries(linked, info.catalystSlots(), info.id());
                }
                linkIndex = new LinkIndex(Map.copyOf(producing), Map.copyOf(linked));
            }
            return linkIndex;
        }
    }

    private static void indexEntries(Map<String, String> index, List<SpecialRecipeInfo.SlotEntry> entries,
                                     String recipeId) {
        for (SpecialRecipeInfo.SlotEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            for (ItemStack item : ItemUtils.createSlotItems(
                    entry.itemId(), entry.behaviorBlockId(), entry.behaviorListKey())) {
                String itemId = ItemUtils.resolveItemId(item);
                if (itemId != null) {
                    index.putIfAbsent(itemId, recipeId);
                }
            }
        }
    }

    private static void indexItem(Map<String, String> index, String itemId, String recipeId) {
        for (ItemStack item : ItemUtils.createSlotItems(itemId)) {
            String resolvedId = ItemUtils.resolveItemId(item);
            if (resolvedId != null) {
                index.putIfAbsent(resolvedId, recipeId);
            }
        }
    }

    private record LinkIndex(Map<String, String> producing, Map<String, String> linked) {
    }
}
