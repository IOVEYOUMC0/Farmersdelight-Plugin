package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class ContainerReturnConfig {

    private final Map<String, ItemStack> containerReturnMap = new ConcurrentHashMap<>();

    public void loadDefaults() {
        addReturnItem("farmersdelight:milk_bottle", "minecraft:glass_bottle");
    }

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;

        for (String itemId : section.getKeys(false)) {
            String returnItemStr;
            try {
                returnItemStr = ConfigSectionReader.optionalString(section, itemId);
            } catch (RuntimeException e) {
                I18n.logWarning("plugin.config_value_invalid", "file", "config.yml",
                        "path", section.getCurrentPath() + "." + itemId, "error", e.getMessage());
                continue;
            }
            if (ItemUtils.isEmptyItemId(returnItemStr)) {
                containerReturnMap.remove(itemId.toLowerCase(Locale.ROOT));
                continue;
            }

            addReturnItem(itemId, returnItemStr);
        }
    }

    private void addReturnItem(String itemId, String returnItemId) {
        ItemStack returnItem = ItemUtils.createItem(returnItemId);
        if (returnItem != null) {
            containerReturnMap.put(itemId.toLowerCase(Locale.ROOT), returnItem);
        } else {
            I18n.logWarning("plugin.item_not_found", "path", itemId, "id", returnItemId);
        }
    }

    public ItemStack getReturnItem(String itemId, int amount) {
        if (itemId == null) return null;

        ItemStack returnItem = containerReturnMap.get(itemId.toLowerCase(Locale.ROOT));
        if (returnItem == null) return null;

        ItemStack result = returnItem.clone();
        result.setAmount(amount);
        return result;
    }

    public boolean hasReturnItem(String itemId) {
        return itemId != null && containerReturnMap.containsKey(itemId.toLowerCase(Locale.ROOT));
    }

    public Map<String, ItemStack> getContainerReturnMap() {
        return containerReturnMap;
    }
}

