package com.huidu.farmersdelight.api.advancement;

import com.huidu.farmersdelight.advancement.AdvancementDef;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AdvancementTree {

    private final String tabId;
    private final List<AdvancementDef> defs = new ArrayList<>();
    private final Map<String, List<String>> requiredIds = new HashMap<>();
    private final Map<String, Map<String, List<String>>> criteriaRequiredIds = new HashMap<>();
    private boolean hasRoot = false;

    AdvancementTree(String tabId) {
        this.tabId = tabId;
    }

    public AdvancementTree root(String id, ItemStack icon, String title, String description, String background) {
        defs.add(new AdvancementDef(id, null, icon, title, description, "task", 0, 0, null, background));
        hasRoot = true;
        return this;
    }

    public AdvancementTree advancement(String id, String parentId, ItemStack icon, String title,
                                       String description, String frame, float x, float y) {
        defs.add(new AdvancementDef(id, parentId, icon, title, description, frame, x, y, null, null));
        return this;
    }

    public AdvancementTree multiTask(String id, String parentId, ItemStack icon, String title,
                                     String description, String frame, float x, float y, List<String> criteria) {
        defs.add(new AdvancementDef(id, parentId, icon, title, description, frame, x, y, criteria, null));
        return this;
    }

    public AdvancementTree requires(String advancementId, String... craftEngineIds) {
        List<String> ids = idList(craftEngineIds);
        if (advancementId != null && !ids.isEmpty()) {
            requiredIds.put(advancementId, ids);
        }
        return this;
    }

    public AdvancementTree requiresCriterion(String advancementId, String criterion, String... craftEngineIds) {
        List<String> ids = idList(craftEngineIds);
        if (advancementId == null || criterion == null || ids.isEmpty()) {
            return this;
        }
        criteriaRequiredIds.computeIfAbsent(advancementId, id -> new LinkedHashMap<>()).put(criterion, ids);
        return this;
    }

    private static List<String> idList(String... craftEngineIds) {
        if (craftEngineIds == null || craftEngineIds.length == 0) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(craftEngineIds.length);
        for (String id : craftEngineIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    public boolean register() {
        if (!hasRoot) {
            return false;
        }
        return FarmersDelightAdvancements.registerTree(tabId, withRequirements());
    }

    private List<AdvancementDef> withRequirements() {
        if (requiredIds.isEmpty() && criteriaRequiredIds.isEmpty()) {
            return defs;
        }
        List<AdvancementDef> merged = new ArrayList<>(defs.size());
        for (AdvancementDef def : defs) {
            merged.add(new AdvancementDef(def.id(), def.parentId(), def.icon(), def.title(), def.description(),
                    def.frame(), def.x(), def.y(), def.criteria(), def.background(),
                    requiredIds.getOrDefault(def.id(), List.of()),
                    criteriaRequiredIds.getOrDefault(def.id(), Map.of())));
        }
        return merged;
    }
}
