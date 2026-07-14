package com.huidu.farmersdelight.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Couples CraftEngine "rug" furnitures with a real vanilla block placed under every cell of their
 * footprint. CE furniture interaction hitboxes carry no movement collision, so a thin walkable block
 * underneath (white_carpet by default) is what actually stops the player from sinking to floor level
 * and lets items rest on the rug.
 *
 * <p>A CE custom <i>block</i> can't supply that collision itself: a custom block's collision equals
 * the collision of the vanilla state its appearance maps to, and none of CraftEngine's remappable
 * states is carpet-shaped — the thin ones (tripwire / pressure_plate / sapling) have no collision at
 * all and every collision-bearing one is a full cube. Hence the underlying-block approach.
 *
 * <p>Config shape (a rugs: section of config.yml):
 * <pre>
 * rugs:
 *   "farmersdelight:canvas_rug":
 *     underlying-block: WHITE_CARPET
 *   "farmersdelight:half_tatami_mat":
 *     underlying-block: WHITE_CARPET
 * </pre>
 * Ids not listed keep their built-in default; listed ids override it. The underlying block is fully
 * configurable, so a pack can swap white_carpet for any other thin walkable block, or register brand
 * new rug ids without touching code.
 *
 * <p>Instances are built fully, then published once to a volatile field (see
 * FarmersDelightPlugin); region-thread readers in RugListener therefore never observe
 * a half-filled config. The internal maps are never mutated after loadFromConfig returns.
 */
public class RugConfig {

    private static final Material DEFAULT_UNDERLYING = Material.WHITE_CARPET;
    private static final String UNDERLYING_KEY = "underlying-block";

    private static Logger LOGGER;

    public static void setLogger(Logger logger) {
        LOGGER = logger;
    }

    /** Rug id (namespaced string) -> vanilla block placed under each footprint cell for collision. */
    private final Map<String, Material> underlyingByRug = new HashMap<>();
    /** Distinct underlying materials, derived from the map — the fast gate for block-event hot paths. */
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

    /** True if id (a namespaced furniture id string) is a managed rug. */
    public boolean isRug(String id) {
        return id != null && underlyingByRug.containsKey(id);
    }

    /** The vanilla collision block configured for id (default white_carpet if unlisted). */
    public Material underlyingOf(String id) {
        return underlyingByRug.getOrDefault(id, DEFAULT_UNDERLYING);
    }

    /** Fast gate for block-event hot paths: is material an underlying block of any rug? */
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
