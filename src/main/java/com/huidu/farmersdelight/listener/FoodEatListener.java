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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FoodEatListener implements Listener {

    private final FarmersDelightPlugin plugin;
    private final Map<String, Integer> comfortFoodDurations = new ConcurrentHashMap<>();
    private final Map<String, Integer> nourishmentFoodDurations = new ConcurrentHashMap<>();
    // Addon-registered food → effect mappings (via the API). Survive /fd reload (config reload only clears
    // the config-loaded maps above) and apply regardless of the comfort/nourishment-foods enabled flags.
    private final Map<String, Integer> externalComfortFoods = new ConcurrentHashMap<>();
    private final Map<String, Integer> externalNourishmentFoods = new ConcurrentHashMap<>();
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
            // Compatible with the legacy plugin config structure:
            // comfort-foods-enabled: false
            // comfort-foods:
            //   item_id:
            //     duration: 300
            comfortFoodsEnabled = plugin.getConfig().getBoolean("comfort-foods-enabled", comfortFoodsEnabled);
            if (comfortSection != null) {
                for (String foodId : comfortSection.getKeys(false)) {
                    if ("enabled".equalsIgnoreCase(foodId) || "foods".equalsIgnoreCase(foodId)) {
                        continue;
                    }
                    int duration = comfortSection.getInt(foodId + ".duration", Constants.DEFAULT_COMFORT_DURATION);
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

        // Each distinct FD dish eaten completes one master_chef criterion; non-dish ids are ignored.
        if (advancementManager != null && itemId.startsWith("farmersdelight:")) {
            advancementManager.awardCriteria(player, "master_chef", itemId.substring("farmersdelight:".length()));
        }

        Integer comfortDuration = externalComfortFoods.get(itemId);
        if (comfortDuration == null && comfortFoodsEnabled) {
            comfortDuration = comfortFoodDurations.get(itemId);
        }
        if (comfortDuration != null) {
            EffectManager.applyComfort(player, comfortDuration);
        }

        Integer nourishmentDuration = externalNourishmentFoods.get(itemId);
        if (nourishmentDuration == null && nourishmentFoodsEnabled) {
            nourishmentDuration = nourishmentFoodDurations.get(itemId);
        }
        if (nourishmentDuration != null) {
            EffectManager.applyNourishment(player, nourishmentDuration);
        }
    }

    /** Registers (or replaces) an addon food → comfort-effect mapping. Survives /fd reload. */
    public void registerComfortFood(String itemId, int durationSeconds) {
        if (itemId != null && durationSeconds > 0) {
            externalComfortFoods.put(itemId, durationSeconds);
        }
    }

    /** Registers (or replaces) an addon food → nourishment-effect mapping. Survives /fd reload. */
    public void registerNourishmentFood(String itemId, int durationSeconds) {
        if (itemId != null && durationSeconds > 0) {
            externalNourishmentFoods.put(itemId, durationSeconds);
        }
    }

    /** Removes an addon comfort-food mapping registered via registerComfortFood. */
    public void unregisterComfortFood(String itemId) {
        if (itemId != null) {
            externalComfortFoods.remove(itemId);
        }
    }

    /** Removes an addon nourishment-food mapping registered via registerNourishmentFood. */
    public void unregisterNourishmentFood(String itemId) {
        if (itemId != null) {
            externalNourishmentFoods.remove(itemId);
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
        return "minecraft:" + type.name().toLowerCase(java.util.Locale.ROOT);
    }
}
