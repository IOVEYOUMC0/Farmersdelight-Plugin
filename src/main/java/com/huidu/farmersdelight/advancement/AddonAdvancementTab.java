package com.huidu.farmersdelight.advancement;

import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.MultiTasksAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.TaskAdvancement;
import com.fren_gor.ultimateAdvancementAPI.database.TeamProgression;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AddonAdvancementTab {

    private final Plugin plugin;
    private final String tabName;
    private final List<AdvancementDef> definitions;
    private final boolean autoLayout;
    private final Map<String, Advancement> byId = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TaskAdvancement>> multiTasks = new ConcurrentHashMap<>();
    private final Set<UUID> rootAwarded = ConcurrentHashMap.newKeySet();
    // IDs gated by each tab's last completed construction. The tab name owns the state because rebuilding
    // replaces the AddonAdvancementTab instance.
    private static final Map<String, Set<String>> GATED_OFF_BY_TAB = new ConcurrentHashMap<>();

    private AdvancementTab tab;
    private String rootId;
    private boolean autoAwardRoot;

    public AddonAdvancementTab(Plugin plugin, String tabName, List<AdvancementDef> definitions) {
        this(plugin, tabName, definitions, false);
    }

    AddonAdvancementTab(Plugin plugin, String tabName, List<AdvancementDef> definitions, boolean autoLayout) {
        this.plugin = plugin;
        this.tabName = tabName;
        this.definitions = List.copyOf(definitions);
        this.autoLayout = autoLayout;
    }

    public String tabName() {
        return tabName;
    }

    public boolean isLoaded() {
        return tab != null && tab.isInitialised();
    }

    public int getLoadedCount() {
        return byId.size();
    }

    public boolean load() {
        try {
            UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
            if (api.isAdvancementTabRegistered(tabName)) {
                // The client may have discarded the virtual tree, so do not send remove packets for it.
                api.unregisterAdvancementTab(tabName, false);
            }
            tab = api.createAdvancementTab(tabName);
            buildTree();
            I18n.logDetail("startup", "advancement.addon_tab_built", "tab", tabName, "count", definitions.size());
            return true;
        } catch (Exception | LinkageError e) {
            disposeQuietly();
            // A build failure otherwise leaves the addon's advancements missing in-game with no error; log the
            // localized message + stack trace so the cause is diagnosable (I18n.logWarning can't carry the throwable).
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    I18n.formatConsole("advancement.addon_tab_build_failed", "tab", tabName), e);
            return false;
        }
    }

    private void buildTree() {
        byId.clear();
        multiTasks.clear();

        Map<String, AdvancementDef> defsById = new LinkedHashMap<>();
        AdvancementDef rootDef = null;
        for (AdvancementDef def : definitions) {
            defsById.put(def.id(), def);
            if (def.isRoot() && rootDef == null) {
                rootDef = def;
            }
        }
        if (rootDef == null) {
            throw new IllegalStateException("advancement tab '" + tabName + "' has no root definition");
        }

        // Drop the advancements whose declared CraftEngine content is gone, then re-hang their children on the
        // nearest surviving ancestor. Addons that declared no requirement keep every advancement, as before.
        AdvancementGate gate = AdvancementGate.fromConfig(FarmersDelightPlugin.getInstance(), tabName);
        Map<String, String> declaredParents = new HashMap<>();
        Set<String> kept = new LinkedHashSet<>();
        for (AdvancementDef def : definitions) {
            if (!def.isRoot()) {
                declaredParents.put(def.id(), def.parentId());
            }
            if (def.isRoot() || gate.keeps(def.id(), def.requirement())) {
                kept.add(def.id());
            }
        }
        Map<String, String> parents = AdvancementGate.resolveParents(rootDef.id(), declaredParents, kept);
        List<String> order = parents == null ? null : AdvancementGate.buildOrder(rootDef.id(), parents, kept);
        boolean gated = order != null;
        if (!gated) {
            // Either gating disconnected the tree or the addon's own tree is malformed. Try the full tree; only
            // if that is unbuildable too is the definition itself at fault.
            kept = new LinkedHashSet<>(defsById.keySet());
            parents = declaredParents;
            order = AdvancementGate.buildOrder(rootDef.id(), parents, kept);
            if (order == null) {
                throw new IllegalStateException("advancement tab '" + tabName
                        + "' has advancements with unknown/cyclic parents: " + unresolvedIds(defsById));
            }
            I18n.logWarning("advancement.gate_structure_invalid", "tab", tabName);
        }
        gate.warnUnknownConfiguredIds(defsById.keySet());
        Set<String> currentGatedOff = AdvancementGate.orderedGatedOff(List.copyOf(defsById.keySet()), kept);

        String background = rootDef.background() == null
                ? "minecraft:textures/block/stone.png"
                : rootDef.background();
        RootAdvancement root = new RootAdvancement(tab, rootDef.id(), display(rootDef), background);
        byId.put(rootDef.id(), root);
        rootId = rootDef.id();
        autoAwardRoot = rootDef.requiredIds().isEmpty();
        if (rootDef.isMulti()) {
            // A root is never a multi-task in practice; ignore criteria on the root.
            multiTasks.remove(rootDef.id());
        }

        Set<BaseAdvancement> all = new HashSet<>();
        for (String id : order) {
            if (id.equals(rootDef.id())) {
                continue;
            }
            AdvancementDef def = defsById.get(id);
            Advancement parent = byId.get(parents.get(id));
            BaseAdvancement built = def.isMulti()
                    ? buildMulti(def, parent, gated ? gate : null)
                    : new BaseAdvancement(def.id(), display(def), parent) {
                        @Override
                        public boolean isVisible(TeamProgression progression) {
                            return !def.hidden() || isGranted(progression);
                        }
                    };
            byId.put(def.id(), built);
            all.add(built);
        }

        // Use UAA's vanilla tidy-tree layout so CE-pack advancement positions follow their parent graph.
        tab.registerAdvancements(root, all, autoLayout);
        tab.automaticallyShowToPlayers();
        // Recorded only once the tab is actually registered, so a build that threw part-way does not become the
        // baseline the next build compares against.
        GATED_OFF_BY_TAB.put(tabName, AdvancementGate.logChanges(tabName, GATED_OFF_BY_TAB.get(tabName),
                currentGatedOff));
    }

    private static String unresolvedIds(Map<String, AdvancementDef> defsById) {
        StringBuilder unresolved = new StringBuilder();
        for (AdvancementDef def : defsById.values()) {
            if (!def.isRoot() && !defsById.containsKey(def.parentId())) {
                unresolved.append(def.id()).append("(parent=").append(def.parentId()).append(") ");
            }
        }
        return unresolved.length() == 0 ? "cyclic parent chain" : unresolved.toString().trim();
    }

    private MultiTasksAdvancement buildMulti(AdvancementDef def, Advancement parent, AdvancementGate gate) {
        List<String> criteria = gate == null
                ? def.criteria()
                : gate.filterCriteria(def.id(), def.criteria(), def.criterionRequirements());
        MultiTasksAdvancement multi = new MultiTasksAdvancement(def.id(), display(def), parent, criteria.size()) {
            @Override
            public boolean isVisible(TeamProgression progression) {
                return !def.hidden() || isGranted(progression);
            }
        };
        Map<String, TaskAdvancement> taskMap = new HashMap<>();
        List<TaskAdvancement> tasks = new ArrayList<>();
        for (String criterion : criteria) {
            TaskAdvancement task = new TaskAdvancement(def.id() + "_" + criterion, multi);
            taskMap.put(criterion, task);
            tasks.add(task);
        }
        multi.registerTasks(tasks.toArray(new TaskAdvancement[0]));
        multiTasks.put(def.id(), taskMap);
        return multi;
    }

    private LocalizedAdvancementDisplay display(AdvancementDef def) {
        ItemStack icon = def.icon() != null && !def.icon().getType().isAir()
                ? def.icon()
                : new ItemStack(org.bukkit.Material.BOOK);
        return new LocalizedAdvancementDisplay(icon, def.title(), def.description(),
                frameOf(def.frame()), def.showToast(), def.announceChat(), def.x(), def.y());
    }

    private static AdvancementFrameType frameOf(String frame) {
        if (frame == null) {
            return AdvancementFrameType.TASK;
        }
        return switch (frame.toLowerCase(Locale.ROOT)) {
            case "goal" -> AdvancementFrameType.GOAL;
            case "challenge" -> AdvancementFrameType.CHALLENGE;
            default -> AdvancementFrameType.TASK;
        };
    }

    public void showTo(Player player) {
        if (tab != null && tab.isInitialised() && player != null) {
            tab.showTab(player);
        }
    }

    public void resyncPlayer(Player player) {
        if (tab == null || !tab.isInitialised() || player == null) {
            return;
        }
        if (autoAwardRoot && rootId != null) {
            award(player, rootId);
        }
        showTo(player);
    }

    // Forces a full tree re-send to an online player even if the tab was already shown (UAA's showTab no-ops
    // once a player is marked shown). Used to re-push the tree after a reload/rebuild, when the first send may
    // have been dropped while the client re-applied CraftEngine's resource pack.
    public void forceResend(Player player) {
        if (tab == null || !tab.isInitialised() || player == null) {
            return;
        }
        try {
            tab.updateAdvancementsToTeam(player);
        } catch (Exception ignored) {
            // team data not loaded yet; UAA re-shows the tab once the player's data finishes loading
        }
    }

    public void award(Player player, String advancementId) {
        if (tab == null || player == null || advancementId == null) {
            return;
        }
        ensureRoot(player, advancementId);
        Advancement advancement = byId.get(advancementId);
        if (advancement == null) {
            return;
        }
        try {
            if (advancement.isGranted(player)) {
                return;
            }
            if (advancement instanceof MultiTasksAdvancement) {
                Map<String, TaskAdvancement> taskMap = multiTasks.get(advancementId);
                if (taskMap != null) {
                    for (TaskAdvancement task : taskMap.values()) {
                        if (!task.isGranted(player)) {
                            task.grant(player);
                        }
                    }
                }
            } else {
                advancement.grant(player);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    public void awardCriteria(Player player, String advancementId, String criterion) {
        if (tab == null || player == null || advancementId == null || criterion == null) {
            return;
        }
        ensureRoot(player, advancementId);
        Map<String, TaskAdvancement> taskMap = multiTasks.get(advancementId);
        if (taskMap == null) {
            return;
        }
        Advancement advancement = byId.get(advancementId);
        if (advancement != null) {
            try {
                if (advancement.isGranted(player)) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        TaskAdvancement task = taskMap.get(criterion);
        if (task == null) {
            return;
        }
        try {
            if (!task.isGranted(player)) {
                task.grant(player);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    public void revoke(Player player, String advancementId) {
        if (tab == null || player == null || advancementId == null) {
            return;
        }
        Advancement advancement = byId.get(advancementId);
        if (advancement == null) {
            return;
        }
        try {
            Map<String, TaskAdvancement> taskMap = multiTasks.get(advancementId);
            if (taskMap != null) {
                for (TaskAdvancement task : taskMap.values()) {
                    task.revoke(player);
                }
            } else {
                advancement.revoke(player);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    public boolean hasAdvancement(Player player, String advancementId) {
        if (tab == null || player == null || advancementId == null) {
            return false;
        }
        Advancement advancement = byId.get(advancementId);
        if (advancement == null) {
            return false;
        }
        try {
            return advancement.isGranted(player);
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureRoot(Player player, String advancementId) {
        if (!autoAwardRoot || rootId == null || rootId.equals(advancementId)) {
            return;
        }
        if (rootAwarded.contains(player.getUniqueId())) {
            return;
        }
        Advancement root = byId.get(rootId);
        if (root == null) {
            return;
        }
        try {
            if (!root.isGranted(player)) {
                root.grant(player);
            }
        } catch (Exception ignored) {
            // best-effort
        }
        rootAwarded.add(player.getUniqueId());
    }

    public void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            rootAwarded.remove(playerId);
        }
    }

    public void dispose() {
        disposeQuietly();
    }

    private void disposeQuietly() {
        try {
            UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
            if (api.isAdvancementTabRegistered(tabName)) {
                api.unregisterAdvancementTab(tabName);
            }
        } catch (Exception ignored) {
            // UAA already unloaded / not enabled
        }
        tab = null;
        byId.clear();
        multiTasks.clear();
    }
}
