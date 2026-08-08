package com.huidu.farmersdelight.advancement;

import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.MultiTasksAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.TaskAdvancement;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AdvancementManager {

    private static final String TAB = "farmersdelight";
    private static final String ROOT_ID = "root";
    private static final String ROOT_BACKGROUND = "minecraft:textures/block/bricks.png";
    private static final String KEY_PREFIX = "farmersdelight.advancement.";
    private static final String ITEM_PREFIX = "farmersdelight:";

    private static final List<String> CROPS = List.of(
            "wheat", "beetroot", "carrot", "potato", "cabbage", "tomato", "onion", "rice", "melon",
            "pumpkin", "sweet_berries", "sugar_cane", "kelp", "cocoa", "nether_wart", "chorus_flower",
            "brown_mushroom", "red_mushroom", "glow_berries");
    private static final List<String> DISHES = List.of(
            "mixed_salad", "cooked_rice", "bone_broth", "beef_stew", "vegetable_soup", "fish_stew",
            "chicken_soup", "fried_rice", "pumpkin_soup", "baked_cod_stew", "noodle_soup", "onion_soup",
            "bacon_and_eggs", "ratatouille", "steak_and_potatoes", "pasta_with_meatballs",
            "pasta_with_mutton_chop", "mushroom_rice", "roasted_mutton_chops", "vegetable_noodles",
            "squid_ink_pasta", "grilled_salmon", "roast_chicken", "stuffed_pumpkin", "honey_glazed_ham",
            "shepherds_pie", "gleaming_salad");

    private static final Map<String, ContentRequirement> CROP_REQUIREMENTS = Map.of(
            "cabbage", ContentRequirement.anyBlock(Constants.BLOCK_CABBAGES),
            "tomato", ContentRequirement.anyBlock(Constants.BLOCK_BUDDING_TOMATOES, Constants.BLOCK_TOMATOES),
            "onion", ContentRequirement.anyBlock(Constants.BLOCK_ONIONS),
            "rice", ContentRequirement.anyBlock(Constants.BLOCK_RICE));
    private static final Map<String, ContentRequirement> DISH_REQUIREMENTS = dishRequirements();

    private static final List<NodeSpec> NODES = List.of(
            // Awarded on join, unconditionally.
            node(ROOT_ID, null, "farmersdelight:cooking_pot", Material.BRICKS, AdvancementFrameType.TASK, 0, 0),
            // Crafting any id in the configurable knife item set (FarmersDelightPlugin.isKnifeItemId).
            node("craft_knife", ROOT_ID, "farmersdelight:flint_knife", Material.WOODEN_SWORD, AdvancementFrameType.TASK, 1, 0),
            // Placing a vanilla campfire / soul campfire.
            node("place_campfire", ROOT_ID, null, Material.CAMPFIRE, AdvancementFrameType.TASK, 1, 3),
            // Crafting or picking up any of AchievementListener.FD_SEED_IDS.
            node("get_fd_seed", ROOT_ID, "farmersdelight:cabbage_seeds", Material.WHEAT_SEEDS, AdvancementFrameType.TASK, 1, 6,
                    ContentRequirement.anyItem(Constants.ITEM_CABBAGE_SEEDS, Constants.ITEM_TOMATO_SEEDS,
                            Constants.ITEM_ONION, Constants.ITEM_RICE)),
            // Crafting ham / smoked ham, or a knife drop of any ham item (KnifeDropHandler.isHamItem).
            node("get_ham", "craft_knife", "farmersdelight:ham", Material.COOKED_BEEF, AdvancementFrameType.TASK, 2, 0,
                    ContentRequirement.anyItem(Constants.ITEM_HAM, Constants.ITEM_SMOKED_HAM,
                            Constants.ITEM_HONEY_GLAZED_HAM)),
            // Breaking any block matched by the straw-drop config with a knife.
            node("harvest_straw", "craft_knife", "farmersdelight:straw", Material.WHEAT, AdvancementFrameType.TASK, 2, 1),
            // Placing any block carrying OrganicCompostBlockBehavior.
            node("place_organic_compost", "harvest_straw", "farmersdelight:organic_compost", Material.DIRT, AdvancementFrameType.TASK, 3, 1),
            // Cutting on any block carrying CuttingBoardBlockBehavior.
            node("use_cutting_board", "craft_knife", "farmersdelight:cutting_board", Material.OAK_SLAB, AdvancementFrameType.TASK, 2, 2),
            // Crafting/smithing exactly farmersdelight:netherite_knife.
            node("obtain_netherite_knife", "use_cutting_board", "farmersdelight:netherite_knife", Material.NETHERITE_SWORD, AdvancementFrameType.CHALLENGE, 3, 2,
                    ContentRequirement.anyItem(Constants.ITEM_NETHERITE_KNIFE)),
            // Cooking on any block carrying SkilletBlockBehavior.
            node("use_skillet", "place_campfire", "farmersdelight:skillet", Material.IRON_SWORD, AdvancementFrameType.TASK, 2, 3),
            node("place_skillet", "use_skillet", "farmersdelight:skillet", Material.IRON_SWORD, AdvancementFrameType.TASK, 3, 3),
            // Placing any block carrying CookingPotBlockBehavior.
            node("place_cooking_pot", "place_campfire", "farmersdelight:cooking_pot", Material.BRICKS, AdvancementFrameType.GOAL, 2, 4),
            // Placing any block carrying the farmersdelight:feast_blocks block tag.
            node("place_feast", "place_cooking_pot", "farmersdelight:roast_chicken", Material.COOKED_CHICKEN, AdvancementFrameType.TASK, 3, 4),
            // Eating every dish; unobtainable only once every dish item is gone.
            multiNode("master_chef", "place_feast", "farmersdelight:beef_stew", Material.COOKED_PORKCHOP, 4, 4,
                    DISHES, ContentRequirement.anyItem(prefixed())),
            // Gaining the nourishment effect, whose food list is plugin config.
            node("eat_nourishing_food", "place_cooking_pot", "farmersdelight:steak_and_potatoes", Material.COOKED_BEEF, AdvancementFrameType.TASK, 3, 5),
            // Hitting a raider with a thrown farmersdelight:rotten_tomato.
            node("hit_raider_with_rotten_tomato", "get_fd_seed", "farmersdelight:rotten_tomato", Material.RED_DYE, AdvancementFrameType.TASK, 2, 5,
                    ContentRequirement.anyItem(Constants.ITEM_ROTTEN_TOMATO)),
            // Obtaining either colony item (the mod's requirement is an OR of the two).
            node("get_mushroom_colony", "get_fd_seed", "farmersdelight:red_mushroom_colony", Material.RED_MUSHROOM, AdvancementFrameType.TASK, 2, 6,
                    ContentRequirement.anyItemOrBlock(List.of(Constants.BLOCK_BROWN_MUSHROOM_COLONY,
                            Constants.BLOCK_RED_MUSHROOM_COLONY))),
            // RicePlantListener places the rice block and bails out when it is not registered.
            node("plant_rice", "get_fd_seed", "farmersdelight:rice", Material.WHEAT_SEEDS, AdvancementFrameType.TASK, 2, 7,
                    ContentRequirement.anyBlock(Constants.BLOCK_RICE)),
            // Its vanilla subtasks keep it obtainable whatever happens to the FarmersDelight crops.
            multiNode("plant_all_crops", "plant_rice", "farmersdelight:cabbage_seeds", Material.WHEAT_SEEDS, 3, 7,
                    CROPS, ContentRequirement.ALWAYS),
            // Crafting or picking up the compost item.
            node("get_organic_compost", "get_fd_seed", "farmersdelight:organic_compost", Material.DIRT, AdvancementFrameType.TASK, 2, 8,
                    ContentRequirement.anyItem(Constants.ITEM_ORGANIC_COMPOST)),
            // Picking up the rich soil item.
            node("get_rich_soil", "get_organic_compost", "farmersdelight:rich_soil", Material.DIRT, AdvancementFrameType.GOAL, 3, 8,
                    ContentRequirement.anyItem(Constants.ITEM_RICH_SOIL)),
            // RichSoilHoeListener places the farmland block and bails out when it is not registered.
            node("hoe_rich_soil", "get_rich_soil", "farmersdelight:rich_soil_farmland", Material.FARMLAND, AdvancementFrameType.CHALLENGE, 4, 8,
                    ContentRequirement.anyBlock(Constants.BLOCK_RICH_SOIL_FARMLAND)),
            // Right-clicking the rope-grown tomato block.
            node("harvest_ropelogged_tomato", "get_fd_seed", "farmersdelight:tomato", Material.RED_DYE, AdvancementFrameType.TASK, 2, 9,
                    ContentRequirement.anyBlock(Constants.BLOCK_TOMATO_CROP_ON_ROPE)));

    private final FarmersDelightPlugin plugin;
    private final Map<String, Advancement> byId = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TaskAdvancement>> multiTasks = new ConcurrentHashMap<>();
    private final Set<UUID> rootAwarded = ConcurrentHashMap.newKeySet();
    private AdvancementTab tab;
    // Which ids the previous build gated off, so only the difference is logged. Written on the load/reload path
    // and read by the next build, which may run on another thread.
    private volatile Set<String> gatedOff;

    public AdvancementManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public int getLoadedCount() {
        return byId.size();
    }

    public void load() {
        try {
            UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
            if (api.isAdvancementTabRegistered(TAB)) {
                api.unregisterAdvancementTab(TAB);
            }
            tab = api.createAdvancementTab(TAB);
            buildTree();
            I18n.logDetail("startup", "advancement.loaded_keys", "count", byId.size());
        } catch (Exception | LinkageError e) {
            // Discard the half-built (uninitialized) tab so it doesn't remain registered.
            try {
                UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
                if (api.isAdvancementTabRegistered(TAB)) {
                    api.unregisterAdvancementTab(TAB);
                }
            } catch (Exception ignored) {
                // best-effort
            }
            tab = null;
            byId.clear();
            multiTasks.clear();
            // A build failure discards the whole tab, so all 23 advancements vanish in-game at once. Log the
            // throwable with its stack trace (I18n.logWarning can't carry one) or the cause is undiagnosable.
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    I18n.formatConsole("advancement.tab_build_failed", "tab", TAB), e);
        }
    }

    private void buildTree() {
        AdvancementGate gate = AdvancementGate.fromConfig(plugin, TAB);

        List<String> order = new ArrayList<>(NODES.size());
        Map<String, String> declaredParents = new HashMap<>();
        for (NodeSpec spec : NODES) {
            order.add(spec.id());
            if (spec.parentId() != null) {
                declaredParents.put(spec.id(), spec.parentId());
            }
        }

        Set<String> kept = new LinkedHashSet<>();
        for (NodeSpec spec : NODES) {
            if (spec.parentId() == null || gate.keeps(spec.id(), spec.requirement())) {
                kept.add(spec.id());
            }
        }
        Map<String, String> parents = AdvancementGate.resolveParents(ROOT_ID, declaredParents, kept);
        if (parents != null && !isBuildableInOrder(order, kept, parents)) {
            parents = null;
        }
        boolean gated = parents != null;
        if (!gated) {
            I18n.logWarning("advancement.gate_structure_invalid", "tab", TAB);
            kept = new LinkedHashSet<>(order);
            parents = declaredParents;
        }
        gate.warnUnknownConfiguredIds(order);
        Set<String> currentGatedOff = AdvancementGate.orderedGatedOff(order, kept);

        byId.clear();
        multiTasks.clear();
        RootAdvancement root = null;
        Set<BaseAdvancement> children = new HashSet<>();
        for (NodeSpec spec : NODES) {
            if (!kept.contains(spec.id())) {
                continue;
            }
            ItemStack icon = icon(spec.iconId(), spec.iconFallback());
            if (spec.parentId() == null) {
                // Root shows no toast and makes no chat broadcast, matching the original mod's root advancement.
                root = new RootAdvancement(tab, spec.id(),
                        display(spec.id(), icon, spec.frame(), spec.x(), spec.y(), false, false), ROOT_BACKGROUND);
                byId.put(spec.id(), root);
                continue;
            }
            Advancement parent = byId.get(parents.get(spec.id()));
            BaseAdvancement built = spec.criteria().isEmpty()
                    ? new BaseAdvancement(spec.id(), display(spec.id(), icon, spec.frame(), spec.x(), spec.y()), parent)
                    : multi(spec, icon, parent, gated ? gate : null);
            byId.put(spec.id(), built);
            children.add(built);
        }
        tab.registerAdvancements(root, children);
        // Recorded only once the tab is actually registered: a build that threw part-way must not become the
        // baseline, or the next successful build would compare against a tree that never existed and skip the
        // line announcing that an advancement came back.
        gatedOff = AdvancementGate.logChanges(TAB, gatedOff, currentGatedOff);
    }

    private static boolean isBuildableInOrder(List<String> order, Set<String> kept, Map<String, String> parents) {
        Set<String> seen = new HashSet<>();
        for (String id : order) {
            if (!kept.contains(id)) {
                continue;
            }
            String parent = parents.get(id);
            if (parent != null && !seen.contains(parent)) {
                return false;
            }
            seen.add(id);
        }
        return true;
    }

    private static Map<String, ContentRequirement> dishRequirements() {
        Map<String, ContentRequirement> requirements = new HashMap<>();
        for (String dish : DISHES) {
            requirements.put(dish, ContentRequirement.anyItem(ITEM_PREFIX + dish));
        }
        return Map.copyOf(requirements);
    }

    private static List<String> prefixed() {
        List<String> prefixed = new ArrayList<>(AdvancementManager.DISHES.size());
        for (String id : AdvancementManager.DISHES) {
            prefixed.add(ITEM_PREFIX + id);
        }
        return prefixed;
    }

    private static ItemStack icon(String ceId, Material fallback) {
        ItemStack item = ceId == null ? null : ItemUtils.createItem(ceId);
        return item != null && !item.getType().isAir() ? item : new ItemStack(fallback);
    }

    private LocalizedAdvancementDisplay display(String key, ItemStack icon, AdvancementFrameType frame, float x, float y) {
        return display(key, icon, frame, x, y, true, true);
    }

    private LocalizedAdvancementDisplay display(String key, ItemStack icon, AdvancementFrameType frame, float x, float y,
                                                boolean showToast, boolean announceChat) {
        // The patched UltimateAdvancementAPI uses this display to render the toast + chat message per client.
        return new LocalizedAdvancementDisplay(icon, KEY_PREFIX + key, KEY_PREFIX + key + ".desc",
                frame, showToast, announceChat, x, y);
    }

    private MultiTasksAdvancement multi(NodeSpec spec, ItemStack icon, Advancement parent, AdvancementGate gate) {
        String key = spec.id();
        List<String> criteria = gate == null
                ? spec.criteria()
                : gate.filterCriteria(key, spec.criteria(), criteriaRequirements(key));
        MultiTasksAdvancement multi = new MultiTasksAdvancement(key,
                display(key, icon, spec.frame(), spec.x(), spec.y()), parent, criteria.size());
        Map<String, TaskAdvancement> taskMap = new HashMap<>();
        List<TaskAdvancement> tasks = new ArrayList<>();
        for (String criterion : criteria) {
            TaskAdvancement task = new TaskAdvancement(key + "_" + criterion, multi);
            taskMap.put(criterion, task);
            tasks.add(task);
        }
        multi.registerTasks(tasks.toArray(new TaskAdvancement[0]));
        multiTasks.put(key, taskMap);
        return multi;
    }

    private static Map<String, ContentRequirement> criteriaRequirements(String advancementId) {
        return switch (advancementId) {
            case "master_chef" -> DISH_REQUIREMENTS;
            case "plant_all_crops" -> CROP_REQUIREMENTS;
            default -> Map.of();
        };
    }

    private record NodeSpec(String id, String parentId, String iconId, Material iconFallback,
                            AdvancementFrameType frame, float x, float y,
                            List<String> criteria, ContentRequirement requirement) {
    }

    private static NodeSpec node(String id, String parentId, String iconId, Material iconFallback,
                                 AdvancementFrameType frame, float x, float y) {
        return new NodeSpec(id, parentId, iconId, iconFallback, frame, x, y, List.of(), ContentRequirement.ALWAYS);
    }

    private static NodeSpec node(String id, String parentId, String iconId, Material iconFallback,
                                 AdvancementFrameType frame, float x, float y, ContentRequirement requirement) {
        return new NodeSpec(id, parentId, iconId, iconFallback, frame, x, y, List.of(), requirement);
    }

    private static NodeSpec multiNode(String id, String parentId, String iconId, Material iconFallback,
                                      float x, float y,
                                      List<String> criteria, ContentRequirement requirement) {
        return new NodeSpec(id, parentId, iconId, iconFallback, AdvancementFrameType.CHALLENGE, x, y, criteria, requirement);
    }

    public void showTo(Player player) {
        if (tab != null && tab.isInitialised() && player != null) {
            tab.showTab(player);
        }
    }

    public void resyncOnlinePlayers() {
        if (tab == null || !tab.isInitialised()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            award(player, "root");
            showTo(player);
        }
    }

    public void award(Player player, String advancementId) {
        if (!plugin.isAdvancementsEnabled() || tab == null) return;
        if (player == null || advancementId == null) return;
        if (!"root".equals(advancementId)) {
            ensureRoot(player);
        }
        Advancement advancement = byId.get(advancementId);
        if (advancement == null) {
            if (plugin.isDebugEnabled()) {
                I18n.logInfo("advancement.unknown", "id", advancementId);
            }
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
        } catch (Exception e) {
            if (plugin.isDebugEnabled()) {
                I18n.logWarning("advancement.award_failed", "id", advancementId, "error", e.getMessage());
            }
        }
    }

    private void ensureRoot(Player player) {
        if (rootAwarded.contains(player.getUniqueId())) {
            return;
        }
        award(player, "root");
        rootAwarded.add(player.getUniqueId());
    }

    public void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            rootAwarded.remove(playerId);
        }
    }

    public void awardCriteria(Player player, String advancementId, String criterion) {
        if (!plugin.isAdvancementsEnabled() || tab == null) return;
        if (player == null || advancementId == null || criterion == null) return;
        if (!"root".equals(advancementId)) {
            ensureRoot(player);
        }
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
        } catch (Exception e) {
            if (plugin.isDebugEnabled()) {
                I18n.logWarning("advancement.award_criterion_failed",
                        "id", advancementId, "criterion", criterion, "error", e.getMessage());
            }
        }
    }

    public void revoke(Player player, String advancementId) {
        if (!plugin.isAdvancementsEnabled() || tab == null) return;
        if (player == null || advancementId == null) return;
        Advancement advancement = byId.get(advancementId);
        if (advancement == null) return;
        try {
            Map<String, TaskAdvancement> taskMap = multiTasks.get(advancementId);
            if (taskMap != null) {
                for (TaskAdvancement task : taskMap.values()) {
                    task.revoke(player);
                }
            } else {
                advancement.revoke(player);
            }
        } catch (Exception e) {
            if (plugin.isDebugEnabled()) {
                I18n.logWarning("advancement.revoke_failed", "id", advancementId, "error", e.getMessage());
            }
        }
    }

    public boolean hasAdvancement(Player player, String advancementId) {
        if (!plugin.isAdvancementsEnabled() || tab == null) return false;
        if (player == null || advancementId == null) return false;
        Advancement advancement = byId.get(advancementId);
        if (advancement == null) return false;
        try {
            return advancement.isGranted(player);
        } catch (Exception e) {
            return false;
        }
    }

    public void dispose() {
        try {
            UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
            if (api.isAdvancementTabRegistered(TAB)) {
                api.unregisterAdvancementTab(TAB);
            }
        } catch (Exception ignored) {
            // UAA already unloaded / not enabled -- nothing to dispose.
        }
        tab = null;
        byId.clear();
        multiTasks.clear();
    }

    public void reload() {
        dispose();
        load();
    }
}
