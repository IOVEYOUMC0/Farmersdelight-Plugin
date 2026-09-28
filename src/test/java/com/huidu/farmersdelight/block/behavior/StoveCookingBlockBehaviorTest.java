package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoveCookingBlockBehaviorTest {

    @Test
    void detectsEquippableComponentsAndShieldUse() {
        assertTrue(StoveCookingBlockBehavior.isEquippable(Material.IRON_CHESTPLATE, true));
        assertTrue(StoveCookingBlockBehavior.isEquippable(Material.CARVED_PUMPKIN, true));
        assertTrue(StoveCookingBlockBehavior.isEquippable(Material.SHIELD, false));
        assertFalse(StoveCookingBlockBehavior.isEquippable(Material.POTATO, false));
    }

    // The mod only ignites an unlit stove and only puts out a lit one (AbstractStoveBlock#useItemOn picks
    // between tryToIgnite and tryToExtinguish by the lit state), so a second flint & steel on a burning stove
    // and a shovel on a cold one must do nothing at all.
    @Test
    void lightsOnlyAnUnlitStoveAndExtinguishesOnlyALitOne() {
        assertEquals(StoveCookingBlockBehavior.StateChange.IGNITE,
                StoveCookingBlockBehavior.stateChangeAction(false, Material.FLINT_AND_STEEL, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.IGNITE,
                StoveCookingBlockBehavior.stateChangeAction(false, Material.FIRE_CHARGE, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.EXTINGUISH,
                StoveCookingBlockBehavior.stateChangeAction(true, Material.WATER_BUCKET, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.EXTINGUISH,
                StoveCookingBlockBehavior.stateChangeAction(true, Material.IRON_SHOVEL, true, true));

        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(true, Material.FLINT_AND_STEEL, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(true, Material.FIRE_CHARGE, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(false, Material.WATER_BUCKET, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(false, Material.IRON_SHOVEL, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(false, Material.POTATO, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(true, Material.AIR, true, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(false, Material.DIAMOND_SWORD, true, true));
    }

    // ignite-enabled / extinguish-enabled switch each half off without affecting the other.
    @Test
    void honorsTheIgniteAndExtinguishSwitches() {
        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(false, Material.FLINT_AND_STEEL, false, true));
        assertEquals(StoveCookingBlockBehavior.StateChange.IGNITE,
                StoveCookingBlockBehavior.stateChangeAction(false, Material.FLINT_AND_STEEL, true, false));
        assertEquals(StoveCookingBlockBehavior.StateChange.NONE,
                StoveCookingBlockBehavior.stateChangeAction(true, Material.IRON_SHOVEL, true, false));
        assertEquals(StoveCookingBlockBehavior.StateChange.EXTINGUISH,
                StoveCookingBlockBehavior.stateChangeAction(true, Material.IRON_SHOVEL, false, true));
    }

    // A sound id typo has to fail the block's own load with the config path, the way craftengine:crop_block
    // reports a missing 'age' property, instead of silently falling back to the vanilla sound.
    @Test
    void rejectsMalformedSoundIdsWithTheirConfigPath() {
        assertEquals("minecraft:block.fire.extinguish",
                StoveCookingBlockBehavior.requireSoundId("blocks.stove.behavior.0", "extinguish-sound",
                        "minecraft:block.fire.extinguish"));

        var malformed = assertThrows(KnownResourceException.class,
                () -> StoveCookingBlockBehavior.requireSoundId("blocks.stove.behavior.0", "ignite-sound", "Not A Sound"));
        assertEquals(ConfigConstants.PARSE_IDENTIFIER_FAILED, malformed.translationKey());
        assertEquals("blocks.stove.behavior.0.ignite-sound", malformed.node());
    }
}
