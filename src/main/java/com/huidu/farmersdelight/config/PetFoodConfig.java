package com.huidu.farmersdelight.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.logging.Logger;

public class PetFoodConfig {

    private static Logger LOGGER;
    private final Map<String, PetFoodDefinition> petFoods = new HashMap<>();

    public static void setLogger(Logger logger) {
        LOGGER = logger;
    }

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;

        petFoods.clear();

        for (String foodId : section.getKeys(false)) {
            ConfigurationSection foodSection = section.getConfigurationSection(foodId);
            if (foodSection == null) continue;

            PetFoodDefinition definition = parseFoodDefinition(foodSection);
            if (definition != null) {
                petFoods.put(foodId, definition);
            }
        }
    }

    private PetFoodDefinition parseFoodDefinition(ConfigurationSection section) {
        PetFoodDefinition definition = new PetFoodDefinition();

        List<String> entityList = section.getStringList("entities");
        for (String entityId : entityList) {
            try {
                EntityType type = EntityType.valueOf(entityId.toUpperCase());
                definition.entities.add(type);
            } catch (IllegalArgumentException e) {
                if (LOGGER != null) {
                    LOGGER.fine("Invalid entity type: " + entityId);
                }
            }
        }

        if (definition.entities.isEmpty()) return null;

        definition.requireTamed = section.getBoolean("require-tamed", true);
        definition.restoreHealth = section.getBoolean("restore-health", true);

        loadEffectDefinitions(section, definition);

        String soundName = section.getString("sound");
        if (soundName != null) {
            Sound resolvedSound = resolveSound(soundName);
            if (resolvedSound != null) {
                definition.sound = resolvedSound;
            }
        }

        definition.soundVolume = (float) section.getDouble("sound-volume", 0.8);
        definition.soundPitch = (float) section.getDouble("sound-pitch", 0.8);

        definition.particles = section.getBoolean("particles", true);

        String particleName = section.getString("particle-type", "END_ROD");
        try {
            definition.particleType = Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            definition.particleType = Particle.END_ROD;
            if (LOGGER != null) {
                LOGGER.fine("Invalid particle type: " + particleName + ", using END_ROD");
            }
        }

        definition.particleCount = section.getInt("particle-count", 5);
        loadTemptDefinition(section, definition);

        return definition;
    }

    private void loadTemptDefinition(ConfigurationSection section, PetFoodDefinition definition) {
        ConfigurationSection temptSection = section.getConfigurationSection("tempt");
        if (temptSection == null) {
            return;
        }

        definition.temptEnabled = temptSection.getBoolean("enabled", false);
        definition.temptRange = Math.max(1.0D, temptSection.getDouble("range", 10.0D));
        definition.temptRangeSquared = definition.temptRange * definition.temptRange;
        definition.temptMoveSpeed = Math.max(0.1D, temptSection.getDouble("move-speed", 1.25D));
        definition.temptTickInterval = Math.max(1L, temptSection.getLong("tick-interval", 10L));
        definition.temptIgnoreOwnedTamed = temptSection.getBoolean("ignore-owned-tamed", true);
    }

    private void loadEffectDefinitions(ConfigurationSection section, PetFoodDefinition definition) {
        List<Map<?, ?>> effectList = section.getMapList("effects");
        if (!effectList.isEmpty()) {
            for (Map<?, ?> effectMap : effectList) {
                addEffectDefinition(effectMap, definition);
            }
            return;
        }

        ConfigurationSection effectsSection = section.getConfigurationSection("effects");
        if (effectsSection == null) {
            return;
        }

        for (String effectKey : effectsSection.getKeys(false)) {
            ConfigurationSection effectSection = effectsSection.getConfigurationSection(effectKey);
            if (effectSection != null) {
                addEffectDefinition(effectSection.getValues(false), definition);
            }
        }
    }

    private void addEffectDefinition(Map<?, ?> effectMap, PetFoodDefinition definition) {
        Object rawType = effectMap.get("type");
        if (rawType == null) {
            return;
        }

        PotionEffectType effectType = resolveEffectType(String.valueOf(rawType));
        if (effectType == null) {
            return;
        }

        int duration = getInt(effectMap.get("duration"), 6000);
        int amplifier = getInt(effectMap.get("amplifier"), 0);
        boolean ambient = getBoolean(effectMap.get("ambient"), false);
        boolean particles = getBoolean(effectMap.get("particles"), true);
        definition.effects.add(new EffectDefinition(effectType, duration, amplifier, ambient, particles));
    }

    private PotionEffectType resolveEffectType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }

        String normalized = typeName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        PotionEffectType effectType = Registry.EFFECT.get(NamespacedKey.minecraft(normalized));
        if (effectType != null) {
            return effectType;
        }

        String dottedNormalized = normalized.replace('_', '.');
        effectType = Registry.EFFECT.get(NamespacedKey.minecraft(dottedNormalized));
        if (effectType != null) {
            return effectType;
        }

        if (LOGGER != null) {
            LOGGER.fine("Invalid potion effect: " + typeName);
        }
        return null;
    }

    private Sound resolveSound(String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return null;
        }

        String trimmed = soundName.trim();
        String registryKey = trimmed.toLowerCase(Locale.ROOT).replace('_', '.');
        Sound registeredSound = Registry.SOUNDS.get(NamespacedKey.minecraft(registryKey));
        if (registeredSound != null) {
            return registeredSound;
        }

        if (LOGGER != null) {
            LOGGER.fine("Invalid sound: " + soundName);
        }
        return null;
    }

    private int getInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean getBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return defaultValue;
    }

    public PetFoodDefinition getFoodDefinition(String foodId) {
        return petFoods.get(foodId);
    }

    public Map<String, PetFoodDefinition> getFoodDefinitions() {
        return Collections.unmodifiableMap(petFoods);
    }

    public static class PetFoodDefinition {
        public final Set<EntityType> entities = new HashSet<>();
        public final List<EffectDefinition> effects = new ArrayList<>();
        public boolean requireTamed = true;
        public boolean restoreHealth = true;
        public Sound sound = Sound.ENTITY_GENERIC_EAT;
        public float soundVolume = 0.8f;
        public float soundPitch = 0.8f;
        public boolean particles = true;
        public Particle particleType = Particle.END_ROD;
        public int particleCount = 5;
        public boolean temptEnabled = false;
        public double temptRange = 10.0D;
        public double temptRangeSquared = 100.0D;
        public double temptMoveSpeed = 1.25D;
        public long temptTickInterval = 10L;
        public boolean temptIgnoreOwnedTamed = true;
    }

    public record EffectDefinition(PotionEffectType type, int duration, int amplifier, boolean ambient,
                                   boolean particles) {
    }
}

