package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class ContainerReturnConfig {

    private final Map<String, ItemStack> containerReturnMap = new HashMap<>();

    public void loadDefaults() {
        addReturnItem("farmersdelight:milk_bottle", "minecraft:glass_bottle");
    }

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;

        for (String itemId : section.getKeys(false)) {
            String returnItemStr = section.getString(itemId);
            if (ItemUtils.isEmptyItemId(returnItemStr)) {
                containerReturnMap.remove(itemId.toLowerCase(java.util.Locale.ROOT));
                continue;
            }

            addReturnItem(itemId, returnItemStr);
        }
    }

    private void addReturnItem(String itemId, String returnItemId) {
        ItemStack returnItem = ItemUtils.createItem(returnItemId);
        if (returnItem != null) {
            containerReturnMap.put(itemId.toLowerCase(java.util.Locale.ROOT), returnItem);
        }
    }

    public ItemStack getReturnItem(String itemId, int amount) {
        if (itemId == null) return null;

        ItemStack returnItem = containerReturnMap.get(itemId.toLowerCase(java.util.Locale.ROOT));
        if (returnItem == null) return null;

        ItemStack result = returnItem.clone();
        result.setAmount(amount);
        return result;
    }

    public boolean hasReturnItem(String itemId) {
        return itemId != null && containerReturnMap.containsKey(itemId.toLowerCase(java.util.Locale.ROOT));
    }

    public Map<String, ItemStack> getContainerReturnMap() {
        return containerReturnMap;
    }
}

