package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The blocks whose knife break earns the harvest_straw advancement.
 *
 *
 * The straw item itself is produced by the CraftEngine packs (vanilla grass and mature wheat through
 * vanilla_loots.yml, mature rice through the break-loot chain of farmersdelight:rice), so
 * this section is only a whitelist; a drop item is no longer read from it.
 */
public class StrawDropConfig {

    private final Set<String> blockKeys = ConcurrentHashMap.newKeySet();

    // The keys are a registry section: an entry removed from the file stays removed, which is why this
    // loader never re-adds defaults.
    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;
        for (String blockType : section.getKeys(false)) {
            blockKeys.add(blockType.toLowerCase(Locale.ROOT));
        }
    }

    public boolean hasRule(String blockType) {
        return blockType != null && blockKeys.contains(blockType.toLowerCase(Locale.ROOT));
    }
}
