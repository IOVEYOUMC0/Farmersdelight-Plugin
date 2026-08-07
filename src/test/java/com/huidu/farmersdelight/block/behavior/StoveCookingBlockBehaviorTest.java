package com.huidu.farmersdelight.block.behavior;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoveCookingBlockBehaviorTest {

    @Test
    void detectsEquippableComponentsAndShieldUse() {
        assertTrue(StoveCookingBlockBehavior.isEquippable(Material.IRON_CHESTPLATE, true));
        assertTrue(StoveCookingBlockBehavior.isEquippable(Material.CARVED_PUMPKIN, true));
        assertTrue(StoveCookingBlockBehavior.isEquippable(Material.SHIELD, false));
        assertFalse(StoveCookingBlockBehavior.isEquippable(Material.POTATO, false));
    }
}
