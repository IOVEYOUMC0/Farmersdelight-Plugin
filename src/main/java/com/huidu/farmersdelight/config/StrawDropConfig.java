package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class StrawDropConfig {

    private final Map<String, StrawDropRule> rules = new ConcurrentHashMap<>();

    public void loadDefaults() {
        addRule("short_grass", "farmersdelight:straw", 1, 1);
        addRule("tall_grass", "farmersdelight:straw", 1, 1);
        addRule("mature_wheat", "farmersdelight:straw", 1, 1);
        addRule("mature_rice", "farmersdelight:straw", 1, 1);
    }

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;

        for (String blockType : section.getKeys(false)) {
            ConfigurationSection ruleSection = section.getConfigurationSection(blockType);
            if (ruleSection == null) {
                I18n.logWarning("plugin.config_value_invalid",
                        "file", "drops.yml", "path", section.getCurrentPath() + "." + blockType,
                        "error", "expected a section");
                continue;
            }

            String dropItem = ConfigSectionReader.optionalString(ruleSection, "drop", "farmersdelight:straw");
            int minAmount = ConfigSectionReader.optionalInt(ruleSection, "min-amount", 1);
            int maxAmount = ConfigSectionReader.optionalInt(ruleSection, "max-amount", 2);

            if (minAmount > maxAmount) {
                int temp = minAmount;
                minAmount = maxAmount;
                maxAmount = temp;
            }

            if (minAmount < 0) minAmount = 0;
            if (maxAmount < 1) maxAmount = 1;

            if (ItemUtils.isEmptyItemId(dropItem)) {
                rules.remove(blockType.toLowerCase(Locale.ROOT));
                continue;
            }

            ItemStack resolvedDrop = ItemUtils.createItem(dropItem);
            if (ItemUtils.isAnyCustomItemLoaded()
                    && (resolvedDrop == null || resolvedDrop.getType().isAir())) {
                I18n.logWarning("plugin.item_not_found",
                        "path", section.getCurrentPath() + "." + blockType + ".drop", "id", dropItem);
                continue;
            }

            addRule(blockType, dropItem, minAmount, maxAmount);
        }
    }

    private void addRule(String blockType, String dropItem, int minAmount, int maxAmount) {
        rules.put(blockType.toLowerCase(Locale.ROOT), new StrawDropRule(blockType, dropItem, minAmount, maxAmount));
    }

    public StrawDropRule getRule(String blockType) {
        return rules.get(blockType.toLowerCase(Locale.ROOT));
    }

    public Map<String, StrawDropRule> getRules() {
        return Map.copyOf(rules);
    }

    public record StrawDropRule(String blockType, String dropItem, int minAmount, int maxAmount) {
        public String getBlockType() {
            return blockType;
        }

        public String getDropItem() {
            return dropItem;
        }

        public int getMinAmount() {
            return minAmount;
        }

        public int getMaxAmount() {
            return maxAmount;
        }
    }
}
