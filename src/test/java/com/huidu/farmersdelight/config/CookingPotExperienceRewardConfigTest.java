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
    void parsesProviderTargetsAndModesWithoutApplyingAuraSkillsNormalization() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                mode: both
                auraskills: {skill: farming}
                levelers:
                  - {plugin: MMOCORE, target: Custom:Cooking, amount: 4.0, chance: 50}
                  - {plugin: jobs, target: Cook, multiplier: 2.0}
                """);
        CookingPotExperienceRewardConfig config = new CookingPotExperienceRewardConfig();
        config.loadFromConfig(yaml);
        assertTrue(config.shouldDropVanillaExperience());
        assertTrue(config.shouldAwardLevelerExperience());
        assertTrue(config.shouldAwardAuraSkillsExperience());
        var first = config.levelerRewards().getFirst();
        assertEquals("mmocore", first.plugin());
        assertEquals("Custom:Cooking", first.target());
        assertEquals(4.0, first.experience(2));
        assertEquals(0.5, first.chance());
        assertEquals(4.0, config.levelerRewards().getLast().experience(2));
        yaml.set("mode", "levelers");
        config.loadFromConfig(yaml);
        assertFalse(config.shouldDropVanillaExperience());
        assertFalse(config.shouldAwardAuraSkillsExperience());
        assertTrue(config.shouldAwardLevelerExperience());
        config.loadFromConfig(null);
        assertTrue(config.levelerRewards().isEmpty());
    }

    @Test
    void rejectsInvalidRewardAmountsAndMultipliers() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                mode: both
                auraskills:
                  - {skill: farming, amount: .inf}
                  - {skill: farming, multiplier: .nan}
                levelers:
                  - {plugin: jobs, target: Cook, amount: .inf}
                  - {plugin: jobs, target: Cook, amount: -1}
                  - {plugin: jobs, target: Cook, multiplier: .nan}
                  - {plugin: jobs, target: Cook, multiplier: -1}
                  - {plugin: jobs, amount: 1}
                  - {target: Cook, amount: 1}
                """);
        CookingPotExperienceRewardConfig config = new CookingPotExperienceRewardConfig();
        config.loadFromConfig(yaml);
        assertTrue(config.auraSkillsRewards().isEmpty());
        assertTrue(config.levelerRewards().isEmpty());
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
