package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.compat.AuraSkillsHook;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CookingPotExperienceRewardConfig {

    private RewardMode mode = RewardMode.VANILLA;
    private List<AuraSkillsReward> auraSkillsRewards = List.of();

    public void loadFromConfig(ConfigurationSection section) {
        mode = RewardMode.VANILLA;
        auraSkillsRewards = List.of();
        if (section == null) {
            return;
        }

        mode = RewardMode.from(section.getString("mode", "vanilla"));
        List<AuraSkillsReward> rewards = new ArrayList<>();
        loadAuraSkillsRewards(section, rewards, "auraskills");
        loadAuraSkillsRewards(section, rewards, "aura-skills");
        loadAuraSkillsRewards(section, rewards, "auraskills-xp");
        auraSkillsRewards = List.copyOf(rewards);
    }

    public boolean shouldDropVanillaExperience() {
        return mode == RewardMode.VANILLA || mode == RewardMode.BOTH;
    }

    public boolean shouldAwardAuraSkillsExperience() {
        return (mode == RewardMode.AURASKILLS || mode == RewardMode.BOTH) && !auraSkillsRewards.isEmpty();
    }

    public List<AuraSkillsReward> auraSkillsRewards() {
        return auraSkillsRewards;
    }

    private void loadAuraSkillsRewards(ConfigurationSection section, List<AuraSkillsReward> rewards, String path) {
        Object raw = section.get(path);
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                AuraSkillsReward reward = parseRewardEntry(entry, null);
                if (reward != null) {
                    rewards.add(reward);
                }
            }
            return;
        }

        ConfigurationSection auraSection = section.getConfigurationSection(path);
        if (auraSection == null) {
            return;
        }

        if (auraSection.contains("skill") || auraSection.contains("id") || auraSection.contains("name")) {
            AuraSkillsReward reward = parseRewardEntry(auraSection.getValues(false), null);
            if (reward != null) {
                rewards.add(reward);
            }
        }

        for (String key : auraSection.getKeys(false)) {
            if (isControlKey(key)) {
                continue;
            }

            ConfigurationSection rewardSection = auraSection.getConfigurationSection(key);
            if (rewardSection != null) {
                AuraSkillsReward reward = parseRewardEntry(rewardSection.getValues(false), key);
                if (reward != null) {
                    rewards.add(reward);
                }
                continue;
            }

            Object value = auraSection.get(key);
            if (value == null) {
                continue;
            }
            AuraSkillsReward reward = parseRewardEntry(Map.of("skill", key, "amount", value), key);
            if (reward != null) {
                rewards.add(reward);
            }
        }
    }

    private AuraSkillsReward parseRewardEntry(Object entry, String fallbackSkill) {
        if (entry instanceof Map<?, ?> mapEntry) {
            Object rawSkill = ConfigValues.firstPresent(mapEntry, "skill", "id", "name");
            String skill = normalizeSkillId(rawSkill != null ? String.valueOf(rawSkill) : fallbackSkill);
            if (skill.isEmpty()) {
                return null;
            }

            Double fixedAmount = getOptionalPositiveDouble(ConfigValues.firstPresent(mapEntry, "amount", "xp", "value"));
            double multiplier = Math.max(0.0D, ConfigValues.doubleValue(ConfigValues.firstPresent(mapEntry,
                    "multiplier",
                    "amount-multiplier",
                    "xp-multiplier"), 1.0D));
            if (fixedAmount == null && multiplier <= 0.0D) {
                return null;
            }

            boolean raw = ConfigValues.booleanValue(ConfigValues.firstPresent(mapEntry,
                    "raw",
                    "bypass-multipliers",
                    "ignore-multipliers",
                    "exact"), false);
            return new AuraSkillsReward(skill, fixedAmount, multiplier, raw, getChance(mapEntry));
        }

        if (entry instanceof String text) {
            String[] parts = text.trim().split("\\s+", 2);
            if (parts.length != 2) {
                return null;
            }
            String skill = normalizeSkillId(parts[0]);
            Double amount = getOptionalPositiveDouble(parts[1]);
            return skill.isEmpty() || amount == null
                    ? null
                    : new AuraSkillsReward(skill, amount, 1.0D, false, 1.0D);
        }

        return null;
    }

    private boolean isControlKey(String key) {
        return "enabled".equalsIgnoreCase(key)
                || "skill".equalsIgnoreCase(key)
                || "id".equalsIgnoreCase(key)
                || "name".equalsIgnoreCase(key)
                || "amount".equalsIgnoreCase(key)
                || "xp".equalsIgnoreCase(key)
                || "value".equalsIgnoreCase(key)
                || "multiplier".equalsIgnoreCase(key)
                || "amount-multiplier".equalsIgnoreCase(key)
                || "xp-multiplier".equalsIgnoreCase(key)
                || "raw".equalsIgnoreCase(key)
                || "chance".equalsIgnoreCase(key)
                || "probability".equalsIgnoreCase(key);
    }

    private double getChance(Map<?, ?> map) {
        double chance = ConfigValues.doubleValue(ConfigValues.firstPresent(map, "chance", "probability"), 1.0D);
        if (chance > 1.0D) {
            chance /= 100.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, chance));
    }

    private Double getOptionalPositiveDouble(Object value) {
        if (value == null) {
            return null;
        }
        double parsed = ConfigValues.doubleValue(value, 0.0D);
        return parsed > 0.0D ? parsed : null;
    }

    private String normalizeSkillId(String skillId) {
        if (skillId == null) {
            return "";
        }
        return skillId.trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace(':', '/');
    }

    private enum RewardMode {
        VANILLA,
        AURASKILLS,
        BOTH,
        NONE;

        private static RewardMode from(String value) {
            if (value == null) {
                return VANILLA;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (normalized) {
                case "auraskills", "aura_skills", "skills" -> AURASKILLS;
                case "both", "all" -> BOTH;
                case "none", "off", "disabled", "false" -> NONE;
                default -> VANILLA;
            };
        }
    }

    public record AuraSkillsReward(String skill, Double amount, double multiplier, boolean raw, double chance) {
        public AuraSkillsHook.XpDefinition toXpDefinition(double baseExperience) {
            double xp = amount != null ? amount : baseExperience * multiplier;
            return new AuraSkillsHook.XpDefinition(skill, xp, raw);
        }
    }
}
