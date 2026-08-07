package com.huidu.farmersdelight.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class RugConfig {

    private static final Material DEFAULT_UNDERLYING = Material.WHITE_CARPET;
    private static final String UNDERLYING_KEY = "underlying-block";

    private static Logger LOGGER;

    public static void setLogger(Logger logger) {
        LOGGER = logger;
    }

    private final Map<String, Material> underlyingByRug = new HashMap<>();
    private Set<Material> underlyingMaterials = Set.of();

    public void loadDefaults() {
        underlyingByRug.put("farmersdelight:canvas_rug", DEFAULT_UNDERLYING);
        underlyingByRug.put("farmersdelight:half_tatami_mat", DEFAULT_UNDERLYING);
        underlyingByRug.put("farmersdelight:full_tatami_mat", DEFAULT_UNDERLYING);
        rebuildMaterials();
    }

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) {
            rebuildMaterials();
            return;
        }
        for (String id : section.getKeys(false)) {
            String rugId = id.trim();
            if (rugId.isEmpty()) continue;
            ConfigurationSection rug = section.getConfigurationSection(id);
            String matName = rug != null ? rug.getString(UNDERLYING_KEY) : null;
            if (matName == null || matName.isBlank()) {
                // Bare "id: {}" (or "id:") means "register this rug with the default underlying block".
                underlyingByRug.putIfAbsent(rugId, DEFAULT_UNDERLYING);
                continue;
            }
            Material mat = Material.matchMaterial(matName.trim());
            if (mat == null || !mat.isBlock()) {
                warn("Invalid " + UNDERLYING_KEY + " '" + matName + "' for rug '" + rugId
                        + "', keeping " + underlyingByRug.getOrDefault(rugId, DEFAULT_UNDERLYING));
                continue;
            }
            underlyingByRug.put(rugId, mat);
        }
        rebuildMaterials();
    }

    private void rebuildMaterials() {
        this.underlyingMaterials = Set.copyOf(underlyingByRug.values());
    }

    public boolean isRug(String id) {
        return id != null && underlyingByRug.containsKey(id);
    }

    public Material underlyingOf(String id) {
        return underlyingByRug.getOrDefault(id, DEFAULT_UNDERLYING);
    }

    public boolean isUnderlyingMaterial(Material material) {
        return material != null && underlyingMaterials.contains(material);
    }

    public boolean isEmpty() {
        return underlyingByRug.isEmpty();
    }

    private static void warn(String message) {
        if (LOGGER != null) {
            LOGGER.warning("[rugs] " + message);
        }
    }
}
