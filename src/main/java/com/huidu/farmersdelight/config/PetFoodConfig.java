package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.util.Constants;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.ItemManager;
import net.momirealms.craftengine.core.item.setting.CustomItemSettingType;
import net.momirealms.craftengine.core.item.setting.ItemSettings;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifier;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifierFactory;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifierType;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigValue;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.registry.Registries;
import net.momirealms.craftengine.core.registry.WritableRegistry;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.ResourceKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class PetFoodConfig {

    static final CustomItemSettingType<PetFoodDefinition> KEY = CustomItemSettingType.simple();

    private static Logger LOGGER;
    private static boolean registered;
    private final Map<String, PetFoodDefinition> petFoods = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setLogger(Logger logger) {
        LOGGER = logger;
    }

    @SuppressWarnings("unchecked")
    public static synchronized void registerCraftEngineSetting() {
        if (registered) {
            return;
        }
        registered = true;

        ItemSettingsModifierType<ItemSettingsModifier> type = new ItemSettingsModifierType<>(
                Key.of(Constants.ITEM_SETTING_PET_FOOD),
                (ItemSettingsModifierFactory<ItemSettingsModifier>) (ConfigValue value) ->
                        (ItemSettingsModifier) settings -> {
                            PetFoodDefinition definition = parseFoodDefinition(value.getAsSection());
                            if (definition != null) {
                                settings.addCustomData(KEY, definition);
                            }
                        }
        );
        ((WritableRegistry<ItemSettingsModifierType<? extends ItemSettingsModifier>>)
                BuiltInRegistries.ITEM_SETTINGS_TYPE)
                .register(ResourceKey.create(Registries.ITEM_SETTINGS_TYPE.location(), type.id()), type);
    }

    public void loadFromCraftEngine() {
        petFoods.clear();
        CraftEngine craftEngine = CraftEngine.instance();
        if (craftEngine == null) {
            return;
        }
        ItemManager itemManager = craftEngine.itemManager();
        for (Map.Entry<Key, ItemDefinition> entry : itemManager.loadedItems().entrySet()) {
            ItemSettings settings = entry.getValue().settings();
            PetFoodDefinition definition = settings.getCustomData(KEY);
            if (definition != null) {
                petFoods.put(entry.getKey().toString(), definition);
            }
        }
    }

    public void loadFromConfig(ConfigurationSection section) {
        petFoods.clear();
        mergeFromConfig(section);
    }

    public void mergeFromConfig(ConfigurationSection section) {
        if (section == null) return;

        for (String foodId : section.getKeys(false)) {
            ConfigurationSection foodSection = section.getConfigurationSection(foodId);
            if (foodSection == null) continue;

            PetFoodDefinition definition = parseFoodDefinition(foodSection);
            if (definition != null) {
                petFoods.put(foodId, definition);
            }
        }
    }

    private static PetFoodDefinition parseFoodDefinition(ConfigurationSection section) {
        Set<EntityType> entities = new HashSet<>();
        List<EffectDefinition> effects = new ArrayList<>();

        List<String> entityList = section.getStringList("entities");
        for (String entityId : entityList) {
            try {
                EntityType type = EntityType.valueOf(entityId.toUpperCase(java.util.Locale.ROOT));
                entities.add(type);
            } catch (IllegalArgumentException e) {
                if (LOGGER != null) {
                    LOGGER.fine("Invalid entity type: " + entityId);
                }
            }
        }

        if (entities.isEmpty()) return null;

        boolean requireTamed = section.getBoolean("require-tamed", true);
        boolean restoreHealth = section.getBoolean("restore-health", true);

        // Load potion effects
        loadEffectDefinitions(section, effects);

        // Load visual / sound effects
        Sound sound = Sound.ENTITY_GENERIC_EAT;
        String soundName = section.getString("sound");
        if (soundName != null) {
            Sound resolvedSound = resolveSound(soundName);
            if (resolvedSound != null) {
                sound = resolvedSound;
            }
        }

        float soundVolume = (float) section.getDouble("sound-volume", 0.8);
        float soundPitch = (float) section.getDouble("sound-pitch", 0.8);

        boolean particles = section.getBoolean("particles", true);

        String particleName = section.getString("particle-type", "END_ROD");
        Particle particleType;
        try {
            particleType = Particle.valueOf(particleName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            particleType = Particle.END_ROD;
            if (LOGGER != null) {
                LOGGER.fine("Invalid particle type: " + particleName + ", using END_ROD");
            }
        }

        int particleCount = section.getInt("particle-count", 5);
        FeedVisual visual = new FeedVisual(sound, soundVolume, soundPitch, particles, particleType, particleCount);

        // Load tempt settings
        TemptSettings tempt = loadTemptDefinition(section);

        return new PetFoodDefinition(
                Collections.unmodifiableSet(entities),
                Collections.unmodifiableList(effects),
                requireTamed, restoreHealth, visual, tempt
        );
    }

    static PetFoodDefinition parseFoodDefinition(ConfigSection section) {
        Set<EntityType> entities = new HashSet<>();
        List<EffectDefinition> effects = new ArrayList<>();

        for (String entityId : section.getStringList("entities", List.of())) {
            EntityType type = parseEntityType(entityId);
            if (type != null) {
                entities.add(type);
            }
        }
        if (entities.isEmpty()) {
            return null;
        }

        List<EffectDefinition> parsedEffects = section.getSectionList("effects", PetFoodConfig::parseEffectDefinition);
        if (parsedEffects != null) {
            parsedEffects.stream().filter(java.util.Objects::nonNull).forEach(effects::add);
        }

        Sound sound = resolveSound(section.getString("sound"));
        FeedVisual visual = new FeedVisual(
                sound == null ? Sound.ENTITY_GENERIC_EAT : sound,
                section.getFloat("sound-volume", 0.8F),
                section.getFloat("sound-pitch", 0.8F),
                section.getBoolean("particles", true),
                parseParticle(section.getString("particle-type", "END_ROD")),
                Math.max(0, section.getInt("particle-count", 5))
        );

        TemptSettings tempt = parseTemptDefinition(section.getSection("tempt"));

        return new PetFoodDefinition(
                Set.copyOf(entities),
                List.copyOf(effects),
                section.getBoolean("require-tamed", true),
                section.getBoolean("restore-health", true),
                visual,
                tempt
        );
    }

    static TemptSettings parseTemptDefinition(ConfigSection section) {
        if (section == null) {
            return new TemptSettings(false, 10.0D, 1.25D, 10L, true);
        }
        return new TemptSettings(
                section.getBoolean("enabled", false),
                Math.max(1.0D, section.getDouble("range", 10.0D)),
                Math.max(0.1D, section.getDouble("move-speed", 1.25D)),
                Math.max(1L, section.getLong("tick-interval", 10L)),
                section.getBoolean("ignore-owned-tamed", true)
        );
    }

    private static EffectDefinition parseEffectDefinition(ConfigSection section) {
        PotionEffectType effectType = resolveEffectType(section.getString("type"));
        if (effectType == null) {
            return null;
        }
        return new EffectDefinition(
                effectType,
                Math.max(1, section.getInt("duration", 6000)),
                Math.max(0, section.getInt("amplifier", 0)),
                section.getBoolean("ambient", false),
                section.getBoolean("particles", true)
        );
    }

    private static EntityType parseEntityType(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(entityId.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (LOGGER != null) {
                LOGGER.fine("Invalid entity type: " + entityId);
            }
            return null;
        }
    }

    private static Particle parseParticle(String particleName) {
        if (particleName == null || particleName.isBlank()) {
            return Particle.END_ROD;
        }
        try {
            return Particle.valueOf(particleName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (LOGGER != null) {
                LOGGER.fine("Invalid particle type: " + particleName + ", using END_ROD");
            }
            return Particle.END_ROD;
        }
    }

    private static TemptSettings loadTemptDefinition(ConfigurationSection section) {
        ConfigurationSection temptSection = section.getConfigurationSection("tempt");
        if (temptSection == null) {
            return new TemptSettings(false, 10.0D, 1.25D, 10L, true);
        }

        boolean enabled = temptSection.getBoolean("enabled", false);
        double range = Math.max(1.0D, temptSection.getDouble("range", 10.0D));
        double moveSpeed = Math.max(0.1D, temptSection.getDouble("move-speed", 1.25D));
        long tickInterval = Math.max(1L, temptSection.getLong("tick-interval", 10L));
        boolean ignoreOwnedTamed = temptSection.getBoolean("ignore-owned-tamed", true);
        return new TemptSettings(enabled, range, moveSpeed, tickInterval, ignoreOwnedTamed);
    }

    private static void loadEffectDefinitions(ConfigurationSection section, List<EffectDefinition> effects) {
        List<Map<?, ?>> effectList = section.getMapList("effects");
        if (!effectList.isEmpty()) {
            for (Map<?, ?> effectMap : effectList) {
                addEffectDefinition(effectMap, effects);
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
                addEffectDefinition(effectSection.getValues(false), effects);
            }
        }
    }

    private static void addEffectDefinition(Map<?, ?> effectMap, List<EffectDefinition> effects) {
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
        effects.add(new EffectDefinition(effectType, duration, amplifier, ambient, particles));
    }

    private static PotionEffectType resolveEffectType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }

        String normalized = typeName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        PotionEffectType effectType = lookupEffect(normalized);
        if (effectType != null) {
            return effectType;
        }

        effectType = lookupEffect(normalized.replace('_', '.'));
        if (effectType != null) {
            return effectType;
        }

        if (LOGGER != null) {
            LOGGER.fine("Invalid potion effect: " + typeName);
        }
        return null;
    }

    private static PotionEffectType lookupEffect(String key) {
        NamespacedKey namespacedKey = safeKey(key);
        return namespacedKey == null ? null : Registry.EFFECT.get(namespacedKey);
    }

    private static Sound resolveSound(String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return null;
        }

        String trimmed = soundName.trim();
        String registryKey = trimmed.toLowerCase(Locale.ROOT).replace('_', '.');
        NamespacedKey key = safeKey(registryKey);
        Sound registeredSound = key == null ? null : Registry.SOUNDS.get(key);
        if (registeredSound != null) {
            return registeredSound;
        }

        if (LOGGER != null) {
            LOGGER.fine("Invalid sound: " + soundName);
        }
        return null;
    }

    private static NamespacedKey safeKey(String raw) {
        try {
            return raw.indexOf(':') >= 0 ? NamespacedKey.fromString(raw) : NamespacedKey.minecraft(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int getInt(Object value, int defaultValue) {
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

    private static boolean getBoolean(Object value, boolean defaultValue) {
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

    // ======================== Data definition records ========================

    public record FeedVisual(Sound sound, float volume, float pitch,
                             boolean particles, Particle particleType, int particleCount) {}

    public record TemptSettings(boolean enabled, double range, double moveSpeed,
                                long tickInterval, boolean ignoreOwnedTamed) {
        public double rangeSquared() { return range * range; }
    }

    public record PetFoodDefinition(
            Set<EntityType> entities,
            List<EffectDefinition> effects,
            boolean requireTamed,
            boolean restoreHealth,
            FeedVisual visual,
            TemptSettings tempt
    ) {}

    public record EffectDefinition(PotionEffectType type, int duration, int amplifier, boolean ambient,
                                   boolean particles) {
    }
}
