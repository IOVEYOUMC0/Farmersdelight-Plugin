package com.huidu.farmersdelight.listener;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnifeHarvestRulesTest {

    @Test
    void cuttingAMatureCropReplantsIt() {
        assertEquals(KnifeHarvestRules.Decision.REPLANT, KnifeHarvestRules.decide(true, false, true));
    }

    @Test
    void aknifeCannotCutAnImmatureCrop() {
        assertEquals(KnifeHarvestRules.Decision.CANCEL, KnifeHarvestRules.decide(true, false, false));
    }

    @Test
    void blocksThatAreNotHarvestableCropsBreakNormally() {
        assertEquals(KnifeHarvestRules.Decision.IGNORE, KnifeHarvestRules.decide(false, false, true));
        assertEquals(KnifeHarvestRules.Decision.IGNORE, KnifeHarvestRules.decide(false, false, false));
    }

    @Test
    void unaffectedBlocksBreakNormally() {
        assertEquals(KnifeHarvestRules.Decision.IGNORE, KnifeHarvestRules.decide(true, true, true));
        assertEquals(KnifeHarvestRules.Decision.IGNORE, KnifeHarvestRules.decide(true, true, false));
    }

    @Test
    void vanillaCropsAreTheCropBlockVariantsOnly() {
        for (Material material : List.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
                Material.BEETROOTS, Material.PITCHER_CROP, Material.TORCHFLOWER_CROP)) {
            assertTrue(KnifeHarvestListener.isVanillaCrop(material), material + " is a vanilla crop");
        }
    }

    @Test
    void ageableBlocksThatAreNotCropBlocksAreLeftAlone() {
        for (Material material : List.of(Material.MELON_STEM, Material.PUMPKIN_STEM, Material.NETHER_WART,
                Material.COCOA, Material.SWEET_BERRY_BUSH, Material.FARMLAND, Material.AIR)) {
            assertFalse(KnifeHarvestListener.isVanillaCrop(material), material + " is not a knife-harvest crop");
        }
    }
}
