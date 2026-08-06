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

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FoodEatListener implements Listener {

    private enum BuffKind {
        COMFORT(Constants.DEFAULT_COMFORT_DURATION, "buff.comfort", "comfort-foods", "comfort-foods-enabled"),
        NOURISHMENT(Constants.DEFAULT_NOURISHMENT_DURATION, "buff.nourishment", "nourishment-foods", "nourishment-foods-enabled");

        final int defaultDuration;
        final String configPath;
        final String legacyConfigPath;
        final String legacyEnabledPath;

        BuffKind(int defaultDuration, String configPath, String legacyConfigPath, String legacyEnabledPath) {
            this.defaultDuration = defaultDuration;
            this.configPath = configPath;
            this.legacyConfigPath = legacyConfigPath;
            this.legacyEnabledPath = legacyEnabledPath;
        }
    }

    private final FarmersDelightPlugin plugin;
    // Legacy food-to-duration mappings loaded from the plugin config.
    private final Map<BuffKind, Map<String, Integer>> configDurations = new EnumMap<>(BuffKind.class);
    // External food-to-duration mappings registered through the API and unaffected by /fd reload.
    private final Map<BuffKind, Map<String, Integer>> externalDurations = new EnumMap<>(BuffKind.class);
    private final Map<BuffKind, Boolean> enabled = new EnumMap<>(BuffKind.class);

    public FoodEatListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        for (BuffKind kind : BuffKind.values()) {
            configDurations.put(kind, new ConcurrentHashMap<>());
            externalDurations.put(kind, new ConcurrentHashMap<>());
            enabled.put(kind, false);
        }
        loadLegacyFoodMappings();
    }

    private void loadLegacyFoodMappings() {
        for (BuffKind kind : BuffKind.values()) {
            Map<String, Integer> map = configDurations.get(kind);
            map.clear();

            ConfigurationSection section = plugin.getFirstConfigSection(kind.configPath, kind.legacyConfigPath);
            boolean foodEnabled = section != null && section.getBoolean("enabled", false);
            enabled.put(kind, foodEnabled);

            ConfigurationSection foodsSection = section != null ? section.getConfigurationSection("foods") : null;
            if (foodsSection != null) {
                for (String foodId : foodsSection.getKeys(false)) {
                    int duration = foodsSection.getInt(foodId + ".duration", kind.defaultDuration);
                    map.put(foodId, duration);
                }
            } else {
                // Legacy config structure: food ids at top level, enable flag on separate key
                enabled.put(kind, plugin.getConfig().getBoolean(kind.legacyEnabledPath, foodEnabled));
                if (section != null) {
                    for (String foodId : section.getKeys(false)) {
                        if ("enabled".equalsIgnoreCase(foodId) || "foods".equalsIgnoreCase(foodId)) {
                            continue;
                        }
                        int duration = section.getInt(foodId + ".duration", kind.defaultDuration);
                        map.put(foodId, duration);
                    }
                }
            }
        }
    }

    public void reload() {
        loadLegacyFoodMappings();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType().isAir()) return;

        String itemId = getItemId(item);
        if (itemId == null) return;

        AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();

        // Each distinct FD food awards one master_chef criterion on consume
        if (advancementManager != null && itemId.startsWith("farmersdelight:")) {
            advancementManager.awardCriteria(player, "master_chef", itemId.substring("farmersdelight:".length()));
        }

        for (BuffKind kind : BuffKind.values()) {
            Integer duration = externalDurations.get(kind).get(itemId);
            if (duration == null && enabled.get(kind)) {
                duration = configDurations.get(kind).get(itemId);
            }
            if (duration != null) {
                switch (kind) {
                    case COMFORT -> EffectManager.applyComfort(player, duration);
                    case NOURISHMENT -> EffectManager.applyNourishment(player, duration);
                }
            }
        }
    }

    public void registerComfortFood(String itemId, int durationSeconds) {
        registerFood(BuffKind.COMFORT, itemId, durationSeconds);
    }

    public void registerNourishmentFood(String itemId, int durationSeconds) {
        registerFood(BuffKind.NOURISHMENT, itemId, durationSeconds);
    }

    public void unregisterComfortFood(String itemId) {
        unregisterFood(BuffKind.COMFORT, itemId);
    }

    public void unregisterNourishmentFood(String itemId) {
        unregisterFood(BuffKind.NOURISHMENT, itemId);
    }

    private void registerFood(BuffKind kind, String itemId, int durationSeconds) {
        if (itemId != null && durationSeconds > 0) {
            externalDurations.get(kind).put(itemId, durationSeconds);
        }
    }

    private void unregisterFood(BuffKind kind, String itemId) {
        if (itemId != null) {
            externalDurations.get(kind).remove(itemId);
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
