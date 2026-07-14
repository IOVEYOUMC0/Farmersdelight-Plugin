package com.huidu.farmersdelight.advancement;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Plain-data definition of one addon advancement. Carries only Bukkit / java types and references no
 * UltimateAdvancementAPI classes, so it (and the api builder that assembles it) can be loaded even when UAA
 * is absent — the UAA-bound build happens later, only when the advancement system is ready.
 *
 * parentId is null for the tab root; non-empty criteria makes a multi-task advancement.
 * title/description are client translation keys (or literal text, rendered verbatim when the
 * key is unknown). frame is one of "task", "goal", "challenge".
 */
public record AdvancementDef(String id, String parentId, ItemStack icon, String title, String description,
                            String frame, float x, float y, List<String> criteria, String background) {

    public AdvancementDef {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        icon = icon == null ? null : icon.clone();
    }

    public boolean isRoot() {
        return parentId == null;
    }

    public boolean isMulti() {
        return !criteria.isEmpty();
    }
}
