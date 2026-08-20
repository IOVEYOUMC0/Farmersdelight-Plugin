package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SpecialRecipeRegistry {

    private final Map<String, SpecialRecipeInfo> recipes = new LinkedHashMap<>();

    public void register(SpecialRecipeInfo info) {
        if (info != null && info.id() != null) {
            recipes.put(info.id(), info);
        }
    }

    public void unregister(String id) {
        if (id != null) {
            recipes.remove(id);
        }
    }

    public void clear() {
        recipes.clear();
    }

    public SpecialRecipeInfo get(String id) {
        return id == null ? null : recipes.get(id);
    }

    public List<SpecialRecipeInfo> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(recipes.values()));
    }

    public boolean isEmpty() {
        return recipes.isEmpty();
    }
}