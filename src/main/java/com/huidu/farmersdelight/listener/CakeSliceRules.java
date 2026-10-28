package com.huidu.farmersdelight.listener;

import org.bukkit.Material;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pure rules for slicing vanilla cakes with a knife: which blocks are targets, what the next bite count is, how
 * many slices a broken pastry yields, and the double-hand de-duplication the click handler needs. Kept out of the
 * listener so the boundaries stay unit-testable without a server.
 */
final class CakeSliceRules {

    /** Last bite count of a vanilla cake; slicing it removes the block. */
    static final int MAX_CAKE_BITES = 6;
    /** Slices a full cake yields when broken with a knife. */
    static final int CAKE_SLICES = 7;
    /** Slices a full pie yields when broken with a knife. */
    static final int PIE_SLICES = 4;

    private static final Map<Material, Material> CANDLES = candles();

    /** One interaction, used to drop the second hand's duplicate event for the same block and tick. */
    record Click(UUID worldId, int x, int y, int z, int tick, boolean offHand) {
    }

    private CakeSliceRules() {
    }

    /** Whether this block is a cake a knife can slice; a CustomBlock state means the block is not a vanilla cake. */
    static boolean target(Material material, boolean customBlock) {
        return !customBlock && (material == Material.CAKE || CANDLES.containsKey(material));
    }

    /** The candle a candle-cake returns when first sliced, or null for a plain cake. */
    static Material candleFor(Material material) {
        return CANDLES.get(material);
    }

    /**
     * Next bite count after one slice, or -1 when the last slice consumes the block. A candle cake always
     * becomes a plain cake with one bite, which is what returns it to a sliceable state.
     */
    static int nextBites(int bites, boolean candleCake) {
        if (bites < 0 || bites > MAX_CAKE_BITES) {
            throw new IllegalArgumentException("cake bites must be in 0.." + MAX_CAKE_BITES + ": " + bites);
        }
        if (candleCake) {
            return 1;
        }
        return bites >= MAX_CAKE_BITES ? -1 : bites + 1;
    }

    /** Slices a broken pastry yields for its remaining bites; cakes have seven, pies four. */
    static int remainingSlices(int bites, boolean cake) {
        int total = cake ? CAKE_SLICES : PIE_SLICES;
        if (bites < 0 || bites >= total) {
            throw new IllegalArgumentException("bites must be in 0.." + (total - 1) + ": " + bites);
        }
        return total - bites;
    }

    /** True when this click is the other hand of the interaction just handled for the same block. */
    static boolean duplicateHand(Click previous, Click current) {
        return previous != null && current != null
                && previous.tick() == current.tick()
                && previous.worldId().equals(current.worldId())
                && previous.x() == current.x() && previous.y() == current.y() && previous.z() == current.z()
                && previous.offHand() != current.offHand();
    }

    private static Map<Material, Material> candles() {
        EnumMap<Material, Material> result = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.equals("CANDLE_CAKE") || name.endsWith("_CANDLE_CAKE")) {
                result.put(material, Material.valueOf(name.substring(0, name.length() - "_CAKE".length())));
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
