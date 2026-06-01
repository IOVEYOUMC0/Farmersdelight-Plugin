package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.compat.AuraSkillsHook;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookingPotExperienceRewardConfigTest {

    @Test
    void defaultsToVanillaExperience() {
        CookingPotExperienceRewardConfig config = new CookingPotExperienceRewardConfig();
        config.loadFromConfig(null);

        assertTrue(config.shouldDropVanillaExperience());
        assertFalse(config.shouldAwardAuraSkillsExperience());
    }

    @Test
    void parsesAuraSkillsReplacementRewards() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                mode: auraskills
                auraskills:
                  farming:
                    multiplier: 2.5
                    raw: true
                    chance: 50
                  custom_namespace/custom_skill:
                    amount: 4.0
                """);

        CookingPotExperienceRewardConfig config = new CookingPotExperienceRewardConfig();
        config.loadFromConfig(yaml);

        assertFalse(config.shouldDropVanillaExperience());
        assertTrue(config.shouldAwardAuraSkillsExperience());
        assertEquals(2, config.auraSkillsRewards().size());

        CookingPotExperienceRewardConfig.AuraSkillsReward farmingReward = config.auraSkillsRewards().getFirst();
        AuraSkillsHook.XpDefinition farming = farmingReward.toXpDefinition(1.2D);
        assertEquals("farming", farming.skill());
        assertEquals(3.0D, farming.amount(), 0.0001D);
        assertTrue(farming.raw());
        assertEquals(0.5D, farmingReward.chance(), 0.0001D);

        AuraSkillsHook.XpDefinition custom =
                config.auraSkillsRewards().get(1).toXpDefinition(1.2D);
        assertEquals("custom_namespace/custom_skill", custom.skill());
        assertEquals(4.0D, custom.amount(), 0.0001D);
        assertFalse(custom.raw());
    }
}
