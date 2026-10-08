package com.huidu.farmersdelight.compat;

import com.huidu.farmersdelight.config.CookingPotExperienceRewardConfig;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.compatibility.CompatibilityManager;
import net.momirealms.craftengine.core.plugin.compatibility.LevelerProvider;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class LevelerExperienceRewards {
    private LevelerExperienceRewards() {
    }

    // The caller owns the player's entity scheduler; raw AuraSkills retains its dedicated API semantics.
    public static void award(CookingPotExperienceRewardConfig config, double baseExperience,
                             Player player, CompatibilityManager manager,
                             Consumer<AuraSkillsHook.XpDefinition> rawAuraSkills, Consumer<String> failure) {
        if (!Double.isFinite(baseExperience) || baseExperience <= 0.0D) return;
        if (config.shouldAwardAuraSkillsExperience()) {
            for (var reward : config.auraSkillsRewards()) {
                if (!applies(reward.chance())) continue;
                var xp = reward.toXpDefinition(baseExperience);
                if (!Double.isFinite(xp.amount()) || xp.amount() <= 0.0D) continue;
                if (xp.raw()) {
                    try {
                        rawAuraSkills.accept(xp);
                    } catch (RuntimeException | LinkageError unavailable) {
                        failure.accept("auraskills:" + xp.skill() + ": " + unavailable.getMessage());
                    }
                } else add(player, manager, "auraskills", xp.skill(), xp.amount(), failure);
            }
        }
        if (config.shouldAwardLevelerExperience()) {
            for (var reward : config.levelerRewards()) {
                if (applies(reward.chance())) {
                    add(player, manager, reward.plugin(), reward.target(), reward.experience(baseExperience), failure);
                }
            }
        }
    }

    private static boolean applies(double chance) {
        return Double.isFinite(chance) && (chance >= 1.0D
                || (chance > 0.0D && ThreadLocalRandom.current().nextDouble() < chance));
    }

    private static void add(Player player, CompatibilityManager manager, String plugin,
                            String target, double amount, Consumer<String> failure) {
        if (player == null || manager == null || !Double.isFinite(amount) || amount <= 0.0D) return;
        try {
            LevelerProvider provider = manager.getLevelerProvider(plugin);
            if (provider != null) provider.addExp(player, target, amount);
        } catch (RuntimeException | LinkageError unavailable) {
            failure.accept(plugin + ":" + target + ": " + unavailable.getMessage());
        }
    }
}
