package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class ContainerReturnConfig {

    private final Map<String, ItemStack> containerReturnMap = new HashMap<>();

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;

        containerReturnMap.clear();

        for (String itemId : section.getKeys(false)) {
            String returnItemStr = section.getString(itemId);
            if (returnItemStr == null || returnItemStr.isEmpty()) continue;

            ItemStack returnItem = ItemUtils.createItem(returnItemStr);
            if (returnItem != null) {
                containerReturnMap.put(itemId.toLowerCase(), returnItem);
            }
        }
    }

    public ItemStack getReturnItem(String itemId, int amount) {
        if (itemId == null) return null;

        ItemStack returnItem = containerReturnMap.get(itemId.toLowerCase());
        if (returnItem == null) return null;

        ItemStack result = returnItem.clone();
        result.setAmount(amount);
        return result;
    }

    public boolean hasReturnItem(String itemId) {
        return itemId != null && containerReturnMap.containsKey(itemId.toLowerCase());
    }

    public Map<String, ItemStack> getContainerReturnMap() {
        return containerReturnMap;
    }
}
