package com.huidu.farmersdelight.api.advancement;

import com.huidu.farmersdelight.advancement.AdvancementDef;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for an addon advancement tab. Describe the tree as plain data — exactly one root plus child
 * advancements — then call register(). FarmersDelight builds the UltimateAdvancementAPI tab (and
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
     * granted via FarmersDelightAdvancements#awardCriteria(String, org.bukkit.entity.Player, String, String).
     */
    public AdvancementTree multiTask(String id, String parentId, ItemStack icon, String title,
                                     String description, String frame, float x, float y, List<String> criteria) {
        defs.add(new AdvancementDef(id, parentId, icon, title, description, frame, x, y, criteria, null));
        return this;
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
        return FarmersDelightAdvancements.registerTree(tabId, defs);
    }
}
