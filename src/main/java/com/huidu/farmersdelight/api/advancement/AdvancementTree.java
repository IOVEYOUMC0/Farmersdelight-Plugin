package com.huidu.farmersdelight.api.advancement;

import com.huidu.farmersdelight.advancement.AdvancementDef;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for an addon advancement tab. Describe the tree as plain data — exactly one root plus child
 * advancements — then call #register(). FarmersDelight builds the UltimateAdvancementAPI tab (and
 * rebuilds it across /fd reload); no UAA types are involved.
 *
 * Titles/descriptions are client translation keys (resolved from the client's resource pack), or literal text
 * which clients render verbatim when the key is unknown. frame is "task", "goal", or
 * "challenge". x/y place the node on the tab grid (root is conventionally 0,0).
 *
 * Obtain an instance via FarmersDelightAdvancements#tree(String).
 */
public final class AdvancementTree {

    private final String tabId;
    private final List<AdvancementDef> defs = new ArrayList<>();
    private final Map<String, List<String>> requiredIds = new HashMap<>();
    private final Map<String, Map<String, List<String>>> criteriaRequiredIds = new HashMap<>();
    private boolean hasRoot = false;

    AdvancementTree(String tabId) {
        this.tabId = tabId;
    }

    /** Defines the single tab root. background is a texture path (null = a default). */
    public AdvancementTree root(String id, ItemStack icon, String title, String description, String background) {
        defs.add(new AdvancementDef(id, null, icon, title, description, "task", 0, 0, null, background));
        hasRoot = true;
        return this;
    }

    /** Adds a child advancement under parentId. */
    public AdvancementTree advancement(String id, String parentId, ItemStack icon, String title,
                                       String description, String frame, float x, float y) {
        defs.add(new AdvancementDef(id, parentId, icon, title, description, frame, x, y, null, null));
        return this;
    }

    /**
     * Adds a multi-task (criteria-counting) child advancement: it completes when every named criterion is
     * granted via org.bukkit.entity.Player, String, String).
     */
    public AdvancementTree multiTask(String id, String parentId, ItemStack icon, String title,
                                     String description, String frame, float x, float y, List<String> criteria) {
        defs.add(new AdvancementDef(id, parentId, icon, title, description, frame, x, y, criteria, null));
        return this;
    }

    /**
     * Declares which CraftEngine content an advancement depends on. When a server owner deletes every listed
     * id from the CraftEngine configuration, the advancement is left out of the built tab and its children are
     * re-attached to the nearest surviving ancestor, instead of sitting there forever unobtainable. Any id that
     * is still loaded as either an item or a block satisfies the requirement.
     *
     * Optional and additive: an advancement with no declared requirement is always shown, which is how every
     * advancement behaves without this call. Declare only ids whose absence really makes the advancement
     * impossible to earn — over-declaring hides content that still works. Calling it again for the same
     * advancement replaces the previous list. The tab root is always kept and ignores any requirement.
     *
     * The auto-disable-missing key in FarmersDelight's config.yml turns the whole mechanism off, and its
     * force-enable / force-disable lists (entries written either as the advancement id or as tabId:id) let a
     * server owner override individual decisions.
     */
    public AdvancementTree requires(String advancementId, String... craftEngineIds) {
        List<String> ids = idList(craftEngineIds);
        if (advancementId != null && !ids.isEmpty()) {
            requiredIds.put(advancementId, ids);
        }
        return this;
    }

    /**
     * Declares which CraftEngine content one criterion of a multi-task advancement depends on. When every listed
     * id is gone, that criterion is dropped from the advancement so the remaining ones can still complete it —
     * without this, a single deleted item leaves the advancement permanently one subtask short.
     *
     * Optional and additive: criteria with no declared requirement are always kept. If every criterion of an
     * advancement would be dropped, the full list is kept instead (an advancement with no criteria at all cannot
     * be registered) and a warning is logged.
     */
    public AdvancementTree requiresCriterion(String advancementId, String criterion, String... craftEngineIds) {
        List<String> ids = idList(craftEngineIds);
        if (advancementId == null || criterion == null || ids.isEmpty()) {
            return this;
        }
        criteriaRequiredIds.computeIfAbsent(advancementId, id -> new LinkedHashMap<>()).put(criterion, ids);
        return this;
    }

    /** Keeps only the usable ids, so a stray null in the varargs cannot break registration. */
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

    /**
     * Registers the tab with FarmersDelight. Returns true if it was accepted (and built, when the advancement
     * system is already up; otherwise it is built once the system becomes ready). Returns false when there is
     * no root, FarmersDelight is unavailable, or the build fails.
     */
    public boolean register() {
        if (!hasRoot) {
            return false;
        }
        return FarmersDelightAdvancements.registerTree(tabId, withRequirements());
    }

    /** Folds the declared requirements into the definitions, which are otherwise built before they are known. */
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
