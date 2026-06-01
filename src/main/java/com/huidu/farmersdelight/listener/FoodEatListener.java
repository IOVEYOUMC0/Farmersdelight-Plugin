package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.effect.EffectManager;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class FoodEatListener implements Listener {

    private final FarmersDelightPlugin plugin;
    private final Map<String, Integer> comfortFoodDurations = new HashMap<>();
    private final Map<String, Integer> nourishmentFoodDurations = new HashMap<>();
    private boolean comfortFoodsEnabled;
    private boolean nourishmentFoodsEnabled;

    public FoodEatListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        loadNourishmentFoods();
    }

    private void loadNourishmentFoods() {
        comfortFoodDurations.clear();
        ConfigurationSection comfortSection = plugin.getConfig().getConfigurationSection("comfort-foods");
        comfortFoodsEnabled = comfortSection != null && comfortSection.getBoolean("enabled", false);
        ConfigurationSection comfortFoodsSection = comfortSection != null
                ? comfortSection.getConfigurationSection("foods")
                : null;
        if (comfortFoodsSection != null) {
            for (String foodId : comfortFoodsSection.getKeys(false)) {
                int duration = comfortFoodsSection.getInt(foodId + ".duration", Constants.DEFAULT_COMFORT_DURATION);
                comfortFoodDurations.put(foodId, duration);
            }
        } else {
            // Backward compatibility with the old plugin config shape:
            // comfort-foods-enabled: false
            // comfort-foods:
            //   item_id:
            //     duration: 300
            comfortFoodsEnabled = plugin.getConfig().getBoolean("comfort-foods-enabled", comfortFoodsEnabled);
            ConfigurationSection legacyComfortSection = plugin.getConfig().getConfigurationSection("comfort-foods");
            if (legacyComfortSection != null) {
                for (String foodId : legacyComfortSection.getKeys(false)) {
                    if ("enabled".equalsIgnoreCase(foodId) || "foods".equalsIgnoreCase(foodId)) {
                        continue;
                    }
                    int duration = legacyComfortSection.getInt(foodId + ".duration", Constants.DEFAULT_COMFORT_DURATION);
                    comfortFoodDurations.put(foodId, duration);
                }
            }
        }

        nourishmentFoodDurations.clear();
        ConfigurationSection nourishmentSection = plugin.getConfig().getConfigurationSection("nourishment-foods");
        nourishmentFoodsEnabled = nourishmentSection != null && nourishmentSection.getBoolean("enabled", false);
        ConfigurationSection foodsSection = nourishmentSection != null
                ? nourishmentSection.getConfigurationSection("foods")
                : null;
        if (foodsSection != null) {
            for (String foodId : foodsSection.getKeys(false)) {
                int duration = foodsSection.getInt(foodId + ".duration", Constants.DEFAULT_NOURISHMENT_DURATION);
                nourishmentFoodDurations.put(foodId, duration);
            }
        } else if (nourishmentSection != null) {
            for (String foodId : nourishmentSection.getKeys(false)) {
                if ("enabled".equalsIgnoreCase(foodId) || "foods".equalsIgnoreCase(foodId)) {
                    continue;
                }
                int duration = nourishmentSection.getInt(foodId + ".duration", Constants.DEFAULT_NOURISHMENT_DURATION);
                nourishmentFoodDurations.put(foodId, duration);
            }
        }
    }

    public void reload() {
        loadNourishmentFoods();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType().isAir()) return;

        String itemId = getItemId(item);
        if (itemId == null) return;

        AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();

        if (comfortFoodDurations.containsKey(itemId)) {
            if (comfortFoodsEnabled) {
                int duration = comfortFoodDurations.get(itemId);
                EffectManager.applyComfort(player, duration);
            }

            if (advancementManager != null) {
                advancementManager.award(player, "eat_comfort_food");
            }
        }

        if (nourishmentFoodDurations.containsKey(itemId)) {
            if (nourishmentFoodsEnabled) {
                int duration = nourishmentFoodDurations.get(itemId);
                EffectManager.applyNourishment(player, duration);
            }

            if (advancementManager != null) {
                advancementManager.award(player, "eat_nourishing_food");
            }
        }
    }

    private String getItemId(ItemStack item) {
        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId != null) {
            return customItemId;
        }

        Material type = item.getType();
        if (type.isAir()) {
            return null;
        }
        return "minecraft:" + type.name().toLowerCase();
    }
}
