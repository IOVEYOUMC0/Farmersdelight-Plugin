package com.huidu.farmersdelight.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides which advancements of a tab stay in the tree when the CraftEngine content they depend on has been
 * deleted from the configuration, and re-parents the survivors so the tab is still a connected tree.
 *
 * Configuration lives under the advancements section of config.yml:
 * auto-disable-missing (master switch, default true), force-enable and force-disable (id lists, default empty).
 * Precedence is force-disable, then force-enable, then automatic detection. Ids in the two lists may be written
 * bare (master_chef) or tab-qualified (farmersdelight:master_chef).
 *
 * The gate never removes the root, and resolveParents refuses to return anything but a fully connected tree, so
 * a wrong requirement can at worst hide a leaf — it can never empty the tab.
 */
final class AdvancementGate {

    private static final String PATH_AUTO_DISABLE = "advancements.auto-disable-missing";
    private static final String PATH_FORCE_ENABLE = "advancements.force-enable";
    private static final String PATH_FORCE_DISABLE = "advancements.force-disable";

    private final String tab;
    private final boolean autoDisable;
    private final Set<String> forceEnable;
    private final Set<String> forceDisable;

    private AdvancementGate(String tab, boolean autoDisable, Set<String> forceEnable, Set<String> forceDisable) {
        this.tab = tab;
        this.autoDisable = autoDisable;
        this.forceEnable = forceEnable;
        this.forceDisable = forceDisable;
    }

    /** Reads the gate settings for one tab. Falls back to "keep everything" when the plugin is unavailable. */
    static AdvancementGate fromConfig(FarmersDelightPlugin plugin, String tab) {
        if (plugin == null) {
            return new AdvancementGate(tab, false, Set.of(), Set.of());
        }
        // Automatic detection reads "content is absent" from a null registry lookup, which is also what an
        // unpopulated registry returns. Rebuild paths that do not wait for CraftEngine to finish loading would
        // therefore hide most of the tab, so absence is only trusted once both registries hold something.
        // force-disable is an explicit admin instruction and stays in effect either way.
        boolean autoDisable = plugin.getConfigBoolean(true, PATH_AUTO_DISABLE) && registriesReady();
        return new AdvancementGate(tab, autoDisable,
                readIds(plugin, PATH_FORCE_ENABLE), readIds(plugin, PATH_FORCE_DISABLE));
    }

    private static boolean registriesReady() {
        try {
            return !CraftEngineItems.loadedItems().isEmpty() && !CraftEngineBlocks.loadedBlocks().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static Set<String> readIds(FarmersDelightPlugin plugin, String path) {
        List<String> configured = plugin.getConfig().getStringList(path);
        if (configured.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (String entry : configured) {
            if (entry != null && !entry.isBlank()) {
                ids.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    /** True when the advancement (or criterion) keeps its place in the tree. */
    boolean keeps(String id, ContentRequirement requirement) {
        if (matches(forceDisable, id)) {
            return false;
        }
        if (matches(forceEnable, id)) {
            return true;
        }
        if (!autoDisable) {
            return true;
        }
        return requirement == null || requirement.isSatisfied();
    }

    private boolean matches(Set<String> ids, String id) {
        if (ids.isEmpty() || id == null) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return ids.contains(lower) || ids.contains(tab.toLowerCase(Locale.ROOT) + ":" + lower);
    }

    /**
     * Maps every kept advancement to its nearest kept ancestor, so hiding a middle node re-attaches its children
     * further up instead of orphaning them. Returns null when the outcome is not a single tree rooted at rootId;
     * callers treat that as "build the full ungated tab instead".
     */
    static Map<String, String> resolveParents(String rootId, Map<String, String> declaredParents, Set<String> kept) {
        if (rootId == null || !kept.contains(rootId)) {
            return null;
        }
        Map<String, String> resolved = new HashMap<>();
        for (String id : kept) {
            if (rootId.equals(id)) {
                continue;
            }
            String parent = declaredParents.get(id);
            int steps = 0;
            while (parent != null && !kept.contains(parent) && steps++ <= declaredParents.size()) {
                parent = declaredParents.get(parent);
            }
            if (parent == null || !kept.contains(parent)) {
                return null;
            }
            resolved.put(id, parent);
        }
        // Explicit connectivity check rather than trusting the walk above: every node must reach the root.
        for (String id : resolved.keySet()) {
            String current = id;
            int steps = 0;
            while (!rootId.equals(current)) {
                current = resolved.get(current);
                if (current == null || steps++ > resolved.size()) {
                    return null;
                }
            }
        }
        return resolved;
    }

    /**
     * Orders the kept ids so every advancement comes after the parent it will be built against, which is what
     * the UltimateAdvancementAPI constructors require. Returns null when some node cannot be reached from the
     * root (an unknown or cyclic parent), leaving the caller to decide what to build instead.
     */
    static List<String> buildOrder(String rootId, Map<String, String> parents, Set<String> kept) {
        if (rootId == null || !kept.contains(rootId)) {
            return null;
        }
        List<String> order = new ArrayList<>(kept.size());
        Set<String> placed = new HashSet<>();
        order.add(rootId);
        placed.add(rootId);
        boolean progress = true;
        while (progress && placed.size() < kept.size()) {
            progress = false;
            for (String id : kept) {
                if (placed.contains(id)) {
                    continue;
                }
                if (placed.contains(parents.get(id))) {
                    order.add(id);
                    placed.add(id);
                    progress = true;
                }
            }
        }
        return placed.size() == kept.size() ? order : null;
    }

    /**
     * Logs the ids that changed side since the previous build, so an advancement never disappears from (or
     * reappears in) the tab silently. Returns the set to remember for the next comparison.
     */
    static Set<String> logChanges(String tab, Set<String> previousGatedOff, Set<String> gatedOff) {
        // Keep the declaration order so repeated builds produce identical log lines.
        Set<String> current = Collections.unmodifiableSet(new LinkedHashSet<>(gatedOff));
        if (previousGatedOff == null) {
            if (!current.isEmpty()) {
                I18n.logInfo("advancement.gate_disabled", "tab", tab, "ids", join(current));
            }
            return current;
        }
        List<String> newlyOff = new ArrayList<>();
        for (String id : current) {
            if (!previousGatedOff.contains(id)) {
                newlyOff.add(id);
            }
        }
        List<String> newlyOn = new ArrayList<>();
        for (String id : previousGatedOff) {
            if (!current.contains(id)) {
                newlyOn.add(id);
            }
        }
        if (!newlyOff.isEmpty()) {
            I18n.logInfo("advancement.gate_disabled", "tab", tab, "ids", join(newlyOff));
        }
        if (!newlyOn.isEmpty()) {
            I18n.logInfo("advancement.gate_restored", "tab", tab, "ids", join(newlyOn));
        }
        return current;
    }

    /**
     * Drops the criteria whose CraftEngine content is gone, so one deleted item cannot leave a multi-task
     * advancement permanently one subtask short. Returns the full list unchanged (with a warning) when every
     * criterion would be dropped: a multi-task advancement with no criteria at all cannot even be registered.
     *
     * Gated on the same autoDisable flag as node-level gating: the admin switch that turns automatic removal
     * off has to hold for subtasks too, and a requirement evaluated before the CraftEngine registries are
     * populated reads every criterion as missing content.
     */
    List<String> filterCriteria(String advancementId, List<String> criteria,
                                Map<String, ContentRequirement> requirements) {
        if (!autoDisable || criteria == null || criteria.isEmpty()) {
            return criteria;
        }
        List<String> keptCriteria = new ArrayList<>(criteria.size());
        List<String> dropped = new ArrayList<>();
        for (String criterion : criteria) {
            ContentRequirement requirement = requirements == null ? null : requirements.get(criterion);
            if (requirement == null || requirement.isSatisfied()) {
                keptCriteria.add(criterion);
            } else {
                dropped.add(criterion);
            }
        }
        if (dropped.isEmpty()) {
            return criteria;
        }
        if (keptCriteria.isEmpty()) {
            I18n.logWarning("advancement.criteria_all_missing", "tab", tab, "id", advancementId);
            return criteria;
        }
        I18n.logInfo("advancement.criteria_dropped", "tab", tab, "id", advancementId,
                "count", dropped.size(), "criteria", join(dropped));
        return keptCriteria;
    }

    /**
     * Warns about force-enable / force-disable entries that match no advancement in this tab, so a typo does not
     * fail silently. A bare id applies to every registered tab, so an entry meant for another tab is reported
     * here too; writing entries tab-qualified avoids that.
     */
    void warnUnknownConfiguredIds(Iterable<String> declaredIds) {
        if (forceEnable.isEmpty() && forceDisable.isEmpty()) {
            return;
        }
        Set<String> known = new HashSet<>();
        String tabPrefix = tab.toLowerCase(Locale.ROOT) + ":";
        for (String id : declaredIds) {
            if (id != null) {
                String lower = id.toLowerCase(Locale.ROOT);
                known.add(lower);
                known.add(tabPrefix + lower);
            }
        }
        warnUnknown(known, forceEnable, "force-enable");
        warnUnknown(known, forceDisable, "force-disable");
    }

    private void warnUnknown(Set<String> known, Set<String> configured, String list) {
        for (String entry : configured) {
            if (!known.contains(entry)) {
                I18n.logWarning("advancement.gate_unknown_id", "tab", tab, "id", entry, "list", list);
            }
        }
    }

    /** Lists the gated-off ids in declaration order so repeated builds log identical text. */
    static Set<String> orderedGatedOff(List<String> declarationOrder, Set<String> kept) {
        Set<String> gatedOff = new LinkedHashSet<>();
        for (String id : declarationOrder) {
            if (!kept.contains(id)) {
                gatedOff.add(id);
            }
        }
        return gatedOff;
    }

    private static String join(Iterable<String> ids) {
        return String.join(", ", ids);
    }
}
