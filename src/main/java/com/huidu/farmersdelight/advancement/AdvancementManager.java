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
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UltimateAdvancementAPI-based advancement backend: a single farmersdelight advancement tab built in code.
 * award/awardCriteria/revoke/hasAdvancement are the public entry points called by
 * event listeners. Titles/descriptions are localized per-client via LocalizedAdvancementDisplay.
 */
public class AdvancementManager {

    private static final String TAB = "farmersdelight";
    private static final String ROOT_BACKGROUND = "minecraft:textures/block/bricks.png";
    private static final String KEY_PREFIX = "farmersdelight.advancement.";

    /** Subtasks of plant_all_crops (criterion names emitted by the planting listener). */
    private static final List<String> CROPS = List.of(
            "wheat", "beetroot", "carrot", "potato", "cabbage", "tomato", "onion", "rice", "melon",
            "pumpkin", "sweet_berries", "sugar_cane", "kelp", "cocoa", "nether_wart", "chorus_flower",
            "brown_mushroom", "red_mushroom", "glow_berries");
    /** Subtasks of master_chef (eaten FD dish ids, without the farmersdelight: prefix). */
    private static final List<String> DISHES = List.of(
            "mixed_salad", "cooked_rice", "bone_broth", "beef_stew", "vegetable_soup", "fish_stew",
            "chicken_soup", "fried_rice", "pumpkin_soup", "baked_cod_stew", "noodle_soup", "onion_soup",
            "bacon_and_eggs", "ratatouille", "steak_and_potatoes", "pasta_with_meatballs",
            "pasta_with_mutton_chop", "mushroom_rice", "roasted_mutton_chops", "vegetable_noodles",
            "squid_ink_pasta", "grilled_salmon", "roast_chicken", "stuffed_pumpkin", "honey_glazed_ham",
            "shepherds_pie", "gleaming_salad");

    private final FarmersDelightPlugin plugin;
    private final Map<String, Advancement> byId = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TaskAdvancement>> multiTasks = new ConcurrentHashMap<>();
    private final Set<UUID> rootAwarded = ConcurrentHashMap.newKeySet();
    private AdvancementTab tab;

    public AdvancementManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        try {
            UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
            if (api.isAdvancementTabRegistered(TAB)) {
                api.unregisterAdvancementTab(TAB);
            }
            tab = api.createAdvancementTab(TAB);
            buildTree();
            I18n.logInfo("advancement.loaded_keys", "count", byId.size());
        } catch (Exception e) {
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
            I18n.logWarning("advancement.award_failed", "id", TAB, "error", e.getMessage());
        }
    }

    private void buildTree() {
        RootAdvancement root = new RootAdvancement(tab, "root",
                // Root shows no toast and makes no chat broadcast, matching the original mod's root advancement.
                display("root", icon("farmersdelight:cooking_pot", Material.BRICKS), AdvancementFrameType.TASK, 0, 0, false, false), ROOT_BACKGROUND);

        BaseAdvancement craftKnife = base("craft_knife", icon("farmersdelight:flint_knife", Material.WOODEN_SWORD), AdvancementFrameType.TASK, root, 1, 0);
        BaseAdvancement placeCampfire = base("place_campfire", icon(null, Material.CAMPFIRE), AdvancementFrameType.TASK, root, 1, 3);
        BaseAdvancement getFdSeed = base("get_fd_seed", icon("farmersdelight:cabbage_seeds", Material.WHEAT_SEEDS), AdvancementFrameType.TASK, root, 1, 6);

        BaseAdvancement getHam = base("get_ham", icon("farmersdelight:ham", Material.COOKED_BEEF), AdvancementFrameType.TASK, craftKnife, 2, 0);
        BaseAdvancement harvestStraw = base("harvest_straw", icon("farmersdelight:straw", Material.WHEAT), AdvancementFrameType.TASK, craftKnife, 2, 1);
        BaseAdvancement placeOrganicCompost = base("place_organic_compost", icon("farmersdelight:organic_compost", Material.DIRT), AdvancementFrameType.TASK, harvestStraw, 3, 1);
        BaseAdvancement useCuttingBoard = base("use_cutting_board", icon("farmersdelight:cutting_board", Material.OAK_SLAB), AdvancementFrameType.TASK, craftKnife, 2, 2);
        BaseAdvancement netheriteKnife = base("obtain_netherite_knife", icon("farmersdelight:netherite_knife", Material.NETHERITE_SWORD), AdvancementFrameType.CHALLENGE, useCuttingBoard, 3, 2);

        BaseAdvancement useSkillet = base("use_skillet", icon("farmersdelight:skillet", Material.IRON_SWORD), AdvancementFrameType.TASK, placeCampfire, 2, 3);
        BaseAdvancement placeSkillet = base("place_skillet", icon("farmersdelight:skillet", Material.IRON_SWORD), AdvancementFrameType.TASK, useSkillet, 3, 3);
        BaseAdvancement placeCookingPot = base("place_cooking_pot", icon("farmersdelight:cooking_pot", Material.BRICKS), AdvancementFrameType.GOAL, placeCampfire, 2, 4);
        BaseAdvancement placeFeast = base("place_feast", icon("farmersdelight:roast_chicken", Material.COOKED_CHICKEN), AdvancementFrameType.TASK, placeCookingPot, 3, 4);
        MultiTasksAdvancement masterChef = multi("master_chef", icon("farmersdelight:beef_stew", Material.COOKED_PORKCHOP), AdvancementFrameType.CHALLENGE, placeFeast, 4, 4, DISHES);
        BaseAdvancement eatNourishingFood = base("eat_nourishing_food", icon("farmersdelight:steak_and_potatoes", Material.COOKED_BEEF), AdvancementFrameType.TASK, placeCookingPot, 3, 5);

        BaseAdvancement hitRaider = base("hit_raider_with_rotten_tomato", icon("farmersdelight:rotten_tomato", Material.RED_DYE), AdvancementFrameType.TASK, getFdSeed, 2, 5);
        BaseAdvancement getMushroom = base("get_mushroom_colony", icon("farmersdelight:red_mushroom_colony", Material.RED_MUSHROOM), AdvancementFrameType.TASK, getFdSeed, 2, 6);
        BaseAdvancement plantRice = base("plant_rice", icon("farmersdelight:rice", Material.WHEAT_SEEDS), AdvancementFrameType.TASK, getFdSeed, 2, 7);
        MultiTasksAdvancement plantAllCrops = multi("plant_all_crops", icon("farmersdelight:cabbage_seeds", Material.WHEAT_SEEDS), AdvancementFrameType.CHALLENGE, plantRice, 3, 7, CROPS);

        BaseAdvancement getOrganicCompost = base("get_organic_compost", icon("farmersdelight:organic_compost", Material.DIRT), AdvancementFrameType.TASK, getFdSeed, 2, 8);
        BaseAdvancement getRichSoil = base("get_rich_soil", icon("farmersdelight:rich_soil", Material.DIRT), AdvancementFrameType.GOAL, getOrganicCompost, 3, 8);
        BaseAdvancement hoeRichSoil = base("hoe_rich_soil", icon("farmersdelight:rich_soil_farmland", Material.FARMLAND), AdvancementFrameType.CHALLENGE, getRichSoil, 4, 8);

        BaseAdvancement harvestRopeloggedTomato = base("harvest_ropelogged_tomato", icon("farmersdelight:tomato", Material.RED_DYE), AdvancementFrameType.TASK, getFdSeed, 2, 9);

        Set<BaseAdvancement> all = new HashSet<>(Arrays.asList(
                craftKnife, placeCampfire, getFdSeed, getHam, harvestStraw, placeOrganicCompost, useCuttingBoard, netheriteKnife,
                useSkillet, placeSkillet, placeCookingPot, placeFeast, masterChef, eatNourishingFood,
                hitRaider, getMushroom, plantRice, plantAllCrops, harvestRopeloggedTomato,
                getOrganicCompost, getRichSoil, hoeRichSoil));
        tab.registerAdvancements(root, all);

        byId.clear();
        byId.put("root", root);
        byId.put("craft_knife", craftKnife);
        byId.put("place_campfire", placeCampfire);
        byId.put("get_fd_seed", getFdSeed);
        byId.put("get_ham", getHam);
        byId.put("harvest_straw", harvestStraw);
        byId.put("use_cutting_board", useCuttingBoard);
        byId.put("obtain_netherite_knife", netheriteKnife);
        byId.put("use_skillet", useSkillet);
        byId.put("place_skillet", placeSkillet);
        byId.put("place_cooking_pot", placeCookingPot);
        byId.put("place_feast", placeFeast);
        byId.put("master_chef", masterChef);
        byId.put("eat_nourishing_food", eatNourishingFood);
        byId.put("hit_raider_with_rotten_tomato", hitRaider);
        byId.put("get_mushroom_colony", getMushroom);
        byId.put("plant_rice", plantRice);
        byId.put("plant_all_crops", plantAllCrops);
        byId.put("get_organic_compost", getOrganicCompost);
        byId.put("get_rich_soil", getRichSoil);
        byId.put("hoe_rich_soil", hoeRichSoil);
        byId.put("place_organic_compost", placeOrganicCompost);
        byId.put("harvest_ropelogged_tomato", harvestRopeloggedTomato);
    }

    /** Builds the CraftEngine item for ceId; uses the vanilla fallback if it can't be resolved. */
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

    private BaseAdvancement base(String key, ItemStack icon, AdvancementFrameType frame, Advancement parent, float x, float y) {
        return new BaseAdvancement(key, display(key, icon, frame, x, y), parent);
    }

    private MultiTasksAdvancement multi(String key, ItemStack icon, AdvancementFrameType frame, Advancement parent,
                                        float x, float y, List<String> criteria) {
        MultiTasksAdvancement multi = new MultiTasksAdvancement(key, display(key, icon, frame, x, y), parent, criteria.size());
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

    public void showTo(Player player) {
        if (tab != null && tab.isInitialised() && player != null) {
            tab.showTab(player);
        }
    }

    /** Re-grant the root and re-show the tab to every online player after a rebuild. reload() = dispose() +
     *  load() recreates the UAA tab, which drops it from online clients; without this, players already online
     *  lose the tab until they rejoin or earn something. Mirrors AchievementListener.onPlayerJoin. */
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

    /** Clears a player's cached root-advancement grant state (called on quit) to keep the set bounded. */
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
