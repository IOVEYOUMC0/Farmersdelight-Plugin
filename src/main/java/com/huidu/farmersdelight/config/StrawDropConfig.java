package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

public class StrawDropConfig {

    private final Map<String, StrawDropRule> rules = new HashMap<>();

    public void loadDefaults() {
        addRule("short_grass", "farmersdelight:straw", 1, 2);
        addRule("tall_grass", "farmersdelight:straw", 1, 2);
        addRule("mature_wheat", "farmersdelight:straw", 1, 2);
        addRule("mature_rice", "farmersdelight:straw", 1, 2);
    }

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;

        for (String blockType : section.getKeys(false)) {
            ConfigurationSection ruleSection = section.getConfigurationSection(blockType);
            if (ruleSection == null) continue;

            String dropItem = ruleSection.getString("drop", "farmersdelight:straw");
            int minAmount = ruleSection.getInt("min-amount", 1);
            int maxAmount = ruleSection.getInt("max-amount", 2);

            if (minAmount > maxAmount) {
                int temp = minAmount;
                minAmount = maxAmount;
                maxAmount = temp;
            }

            if (minAmount < 0) minAmount = 0;
            if (maxAmount < 1) maxAmount = 1;

            if (ItemUtils.isEmptyItemId(dropItem)) {
                rules.remove(blockType.toLowerCase());
                continue;
            }

            addRule(blockType, dropItem, minAmount, maxAmount);
        }
    }

    private void addRule(String blockType, String dropItem, int minAmount, int maxAmount) {
        rules.put(blockType.toLowerCase(), new StrawDropRule(blockType, dropItem, minAmount, maxAmount));
    }

    public StrawDropRule getRule(String blockType) {
        return rules.get(blockType.toLowerCase());
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

