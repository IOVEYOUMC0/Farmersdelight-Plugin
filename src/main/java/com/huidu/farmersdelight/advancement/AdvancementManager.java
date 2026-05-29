package com.huidu.farmersdelight.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class AdvancementManager {

    private final FarmersDelightPlugin plugin;
    private final Map<String, NamespacedKey> advancementKeys = new HashMap<>();

    public AdvancementManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        registerAdvancement("root", "main/root");
        registerAdvancement("craft_knife", "main/craft_knife");
        registerAdvancement("place_campfire", "main/place_campfire");
        registerAdvancement("use_skillet", "main/use_skillet");
        registerAdvancement("get_fd_seed", "main/get_fd_seed");
        registerAdvancement("hit_raider_with_rotten_tomato", "main/hit_raider_with_rotten_tomato");
        registerAdvancement("harvest_straw", "main/harvest_straw");
        registerAdvancement("place_cooking_pot", "main/place_cooking_pot");
        registerAdvancement("place_skillet", "main/place_skillet");
        registerAdvancement("place_feast", "main/place_feast");
        registerAdvancement("use_cutting_board", "main/use_cutting_board");
        registerAdvancement("plant_rice", "main/plant_rice");
        registerAdvancement("plant_all_crops", "main/plant_all_crops");
        registerAdvancement("get_mushroom_colony", "main/get_mushroom_colony");
        registerAdvancement("get_ham", "main/get_ham");
        registerAdvancement("netherite_knife", "main/obtain_netherite_knife");
        registerAdvancement("rotten_tomato_throw", "main/hit_raider_with_rotten_tomato");
        registerAdvancement("eat_comfort_food", "main/eat_comfort_food");
        registerAdvancement("eat_nourishing_food", "main/eat_nourishing_food");
        registerAdvancement("master_chef", "main/master_chef");

        I18n.logInfo("advancement.loaded_keys", "count", advancementKeys.size());
    }

    private void registerAdvancement(String id, String path) {
        NamespacedKey key = new NamespacedKey("farmersdelight", path);
        advancementKeys.put(id, key);
    }

    public void award(Player player, String advancementId) {
        if (!plugin.isAdvancementsEnabled()) return;
        if (player == null || advancementId == null) return;

        if (!"root".equals(advancementId)) {
            award(player, "root");
        }

        NamespacedKey key = advancementKeys.get(advancementId);
        if (key == null) {
            if (plugin.isDebugEnabled()) {
                I18n.logInfo("advancement.unknown", "id", advancementId);
            }
            return;
        }

        try {
            Advancement advancement = Bukkit.getAdvancement(key);
            if (advancement == null) {
                if (plugin.isDebugEnabled()) {
                    I18n.logInfo("advancement.not_found", "key", key);
                }
                return;
            }

            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            if (!progress.isDone()) {
                for (String criteria : progress.getRemainingCriteria()) {
                    progress.awardCriteria(criteria);
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugEnabled()) {
                I18n.logWarning("advancement.award_failed", "id", advancementId, "error", e.getMessage());
            }
        }
    }

    public void awardCriteria(Player player, String advancementId, String criterion) {
        if (!plugin.isAdvancementsEnabled()) return;
        if (player == null || advancementId == null || criterion == null) return;

        if (!"root".equals(advancementId)) {
            award(player, "root");
        }

        NamespacedKey key = advancementKeys.get(advancementId);
        if (key == null) {
            if (plugin.isDebugEnabled()) {
                I18n.logInfo("advancement.unknown", "id", advancementId);
            }
            return;
        }

        try {
            Advancement advancement = Bukkit.getAdvancement(key);
            if (advancement == null) {
                if (plugin.isDebugEnabled()) {
                    I18n.logInfo("advancement.not_found", "key", key);
                }
                return;
            }

            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            if (progress.getRemainingCriteria().contains(criterion)) {
                progress.awardCriteria(criterion);
            }
        } catch (Exception e) {
            if (plugin.isDebugEnabled()) {
                I18n.logWarning("advancement.award_criterion_failed",
                        "id", advancementId,
                        "criterion", criterion,
                        "error", e.getMessage());
            }
        }
    }

    public void revoke(Player player, String advancementId) {
        if (!plugin.isAdvancementsEnabled()) return;
        if (player == null || advancementId == null) return;

        NamespacedKey key = advancementKeys.get(advancementId);
        if (key == null) return;

        try {
            Advancement advancement = Bukkit.getAdvancement(key);
            if (advancement == null) return;

            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criteria : progress.getAwardedCriteria()) {
                progress.revokeCriteria(criteria);
            }
        } catch (Exception e) {
            if (plugin.isDebugEnabled()) {
                I18n.logWarning("advancement.revoke_failed", "id", advancementId, "error", e.getMessage());
            }
        }
    }

    public boolean hasAdvancement(Player player, String advancementId) {
        if (!plugin.isAdvancementsEnabled()) return false;
        if (player == null || advancementId == null) return false;

        NamespacedKey key = advancementKeys.get(advancementId);
        if (key == null) return false;

        try {
            Advancement advancement = Bukkit.getAdvancement(key);
            if (advancement == null) return false;

            return player.getAdvancementProgress(advancement).isDone();
        } catch (Exception e) {
            return false;
        }
    }

    public void reload() {
        advancementKeys.clear();
        load();
    }
}

