package com.huidu.farmersdelight.advancement;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AdvancementDef(String id, String parentId, ItemStack icon, String title, String description,
                            String frame, float x, float y, List<String> criteria, String background,
                            List<String> requiredIds, Map<String, List<String>> criteriaRequirements) {

    public AdvancementDef {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        icon = icon == null ? null : icon.clone();
        requiredIds = requiredIds == null ? List.of() : List.copyOf(requiredIds);
        criteriaRequirements = copyRequirements(criteriaRequirements);
    }

    public AdvancementDef(String id, String parentId, ItemStack icon, String title, String description,
                          String frame, float x, float y, List<String> criteria, String background) {
        this(id, parentId, icon, title, description, frame, x, y, criteria, background, List.of(), Map.of());
    }

    public boolean isRoot() {
        return parentId == null;
    }

    public boolean isMulti() {
        return !criteria.isEmpty();
    }

    public ContentRequirement requirement() {
        return ContentRequirement.anyItemOrBlock(requiredIds);
    }

    public Map<String, ContentRequirement> criterionRequirements() {
        if (criteriaRequirements.isEmpty()) {
            return Map.of();
        }
        Map<String, ContentRequirement> requirements = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : criteriaRequirements.entrySet()) {
            requirements.put(entry.getKey(), ContentRequirement.anyItemOrBlock(entry.getValue()));
        }
        return requirements;
    }

    private static Map<String, List<String>> copyRequirements(Map<String, List<String>> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : requirements.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        return Map.copyOf(copy);
    }
}
