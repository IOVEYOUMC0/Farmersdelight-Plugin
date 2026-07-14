package com.huidu.farmersdelight.advancement;

import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.MultiTasksAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.TaskAdvancement;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single addon-defined UltimateAdvancementAPI tab, built from plain AdvancementDef data (no UAA types
 * leak to the addon). Mirrors AdvancementManager's tree-building and grant/check logic, but the tree is
 * data-driven and the tab name is supplied by the addon. Titles/descriptions are client translation keys (or
 * plain literal text, which clients render verbatim when the key is unknown), matching FarmersDelight's own tab.
 */
public final class AddonAdvancementTab {

    private final Plugin plugin;
    private final String tabName;
    private final List<AdvancementDef> definitions;
    private final Map<String, Advancement> byId = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TaskAdvancement>> multiTasks = new ConcurrentHashMap<>();
    private final Set<UUID> rootAwarded = ConcurrentHashMap.newKeySet();
    private AdvancementTab tab;
    private String rootId;

    public AddonAdvancementTab(Plugin plugin, String tabName, List<AdvancementDef> definitions) {
        this.plugin = plugin;
        this.tabName = tabName;
        this.definitions = List.copyOf(definitions);
    }

    public String tabName() {
        return tabName;
    }

    public boolean isLoaded() {
        return tab != null && tab.isInitialised();
    }

    /** Builds (or rebuilds) the UAA tab from the definitions. Returns false on any failure. */
    public boolean load() {
        try {
            UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
            if (api.isAdvancementTabRegistered(tabName)) {
                api.unregisterAdvancementTab(tabName);
            }
            tab = api.createAdvancementTab(tabName);
            buildTree();
            I18n.logInfo("advancement.addon_tab_built", "tab", tabName, "count", definitions.size());
            return true;
        } catch (Throwable e) {
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

        AdvancementDef rootDef = null;
        for (AdvancementDef def : definitions) {
            if (def.isRoot()) {
                rootDef = def;
                break;
            }
        }
        if (rootDef == null) {
            throw new IllegalStateException("advancement tab '" + tabName + "' has no root definition");
        }

        String background = rootDef.background() == null
                ? "minecraft:textures/block/stone.png"
                : rootDef.background();
        RootAdvancement root = new RootAdvancement(tab, rootDef.id(),
                display(rootDef), background);
        byId.put(rootDef.id(), root);
        rootId = rootDef.id();
        if (rootDef.isMulti()) {
            // A root is never a multi-task in practice; ignore criteria on the root.
            multiTasks.remove(rootDef.id());
        }

        Set<BaseAdvancement> all = new HashSet<>();
        // Multi-pass so children may appear before their parents in the definition order.
        List<AdvancementDef> pending = new ArrayList<>(definitions);
        pending.remove(rootDef);
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                AdvancementDef def = it.next();
                Advancement parent = byId.get(def.parentId());
                if (parent == null) {
                    continue; // parent not built yet
                }
                BaseAdvancement built = def.isMulti()
                        ? buildMulti(def, parent)
                        : new BaseAdvancement(def.id(), display(def), parent);
                byId.put(def.id(), built);
                all.add(built);
                it.remove();
                progress = true;
            }
        }
        if (!pending.isEmpty()) {
            StringBuilder unresolved = new StringBuilder();
            for (AdvancementDef def : pending) {
                unresolved.append(def.id()).append("(parent=").append(def.parentId()).append(") ");
            }
            throw new IllegalStateException("advancement tab '" + tabName
                    + "' has advancements with unknown/cyclic parents: " + unresolved);
        }

        tab.registerAdvancements(root, all);
    }

    private MultiTasksAdvancement buildMulti(AdvancementDef def, Advancement parent) {
        MultiTasksAdvancement multi = new MultiTasksAdvancement(def.id(), display(def), parent, def.criteria().size());
        Map<String, TaskAdvancement> taskMap = new HashMap<>();
        List<TaskAdvancement> tasks = new ArrayList<>();
        for (String criterion : def.criteria()) {
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
        // The tab root shows no toast and makes no chat broadcast, matching the original mod's root
        // advancement (announce_to_chat / show_toast both false); other advancements announce as usual.
        boolean announce = !def.isRoot();
        return new LocalizedAdvancementDisplay(icon, def.title(), def.description(),
                frameOf(def.frame()), announce, announce, def.x(), def.y());
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

    /** Re-grant the root and re-show the tab to a player after a rebuild. Recreating the UAA tab (e.g. on
     *  /ce reload) drops it from online clients; mirroring the on-join path here keeps the tab visible for
     *  players already online instead of vanishing until they rejoin or re-trigger an award. */
    public void resyncPlayer(Player player) {
        if (tab == null || !tab.isInitialised() || player == null) {
            return;
        }
        if (rootId != null) {
            award(player, rootId);
        }
        showTo(player);
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
            if (advancement instanceof MultiTasksAdvancement) {
                Map<String, TaskAdvancement> taskMap = multiTasks.get(advancementId);
                if (taskMap != null) {
                    for (TaskAdvancement task : taskMap.values()) {
                        if (!task.isGranted(player)) {
                            task.grant(player);
                        }
                    }
                }
            } else if (!advancement.isGranted(player)) {
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
        if (rootId == null || rootId.equals(advancementId)) {
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
