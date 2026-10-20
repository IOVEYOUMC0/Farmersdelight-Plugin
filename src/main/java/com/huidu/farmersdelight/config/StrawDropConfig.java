package com.huidu.farmersdelight.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The blocks whose knife break earns the harvest_straw advancement.
 */
public class StrawDropConfig {

    private final Set<String> blockKeys = ConcurrentHashMap.newKeySet();
    // Per-material answers for the rule keys that name a block type, so a hot path does not lowercase the
    // material name per call.
    private final Map<Material, Boolean> materialRules = new ConcurrentHashMap<>();

    // The keys are a registry section: an entry removed from the file stays removed, which is why this
    // loader never re-adds defaults.
    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;
        for (String blockType : section.getKeys(false)) {
            blockKeys.add(blockType.toLowerCase(Locale.ROOT));
        }
        materialRules.clear();
    }

    public boolean hasRule(String blockType) {
        return blockType != null && blockKeys.contains(blockType.toLowerCase(Locale.ROOT));
    }

    public boolean hasRule(Material material) {
        if (material == null) {
            return false;
        }
        return materialRules.computeIfAbsent(material,
                type -> blockKeys.contains(type.name().toLowerCase(Locale.ROOT)));
    }
}
