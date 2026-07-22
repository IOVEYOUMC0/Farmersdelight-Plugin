package com.huidu.farmersdelight.advancement;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-data definition of one addon advancement. Carries only Bukkit / java types and references no
 * UltimateAdvancementAPI classes, so it (and the api builder that assembles it) can be loaded even when UAA
 * is absent — the UAA-bound build happens later, only when the advancement system is ready.
 *
 * parentId is null for the tab root; non-empty criteria makes a multi-task advancement. title/description are
 * client translation keys (or literal text, rendered verbatim when the key is unknown). frame is one of
 * "task", "goal", "challenge".
 *
 * requiredIds and criteriaRequirements are optional CraftEngine content dependencies: the advancement (or the
 * single criterion) is dropped from the built tab while none of the listed item/block ids is loaded. Both
 * default to empty, which means "always shown".
 */
public record AdvancementDef(String id, String parentId, ItemStack icon, String title, String description,
                            String frame, float x, float y, List<String> criteria, String background,
                            List<String> requiredIds, Map<String, List<String>> criteriaRequirements) {

    public AdvancementDef {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        icon = icon == null ? null : icon.clone();
        requiredIds = requiredIds == null ? List.of() : List.copyOf(requiredIds);
        criteriaRequirements = copyRequirements(criteriaRequirements);
    }

    /** Definition with no CraftEngine content requirements. */
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

    /** The requirement guarding the whole advancement; ALWAYS when the addon declared none. */
    public ContentRequirement requirement() {
        return ContentRequirement.anyItemOrBlock(requiredIds);
    }

    /** The requirement guarding each named criterion; criteria absent from the map are always kept. */
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
