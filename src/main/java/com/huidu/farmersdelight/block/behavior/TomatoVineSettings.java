package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record TomatoVineSettings(
        String buddingBlock,
        String tomatoesBlock,
        String cropOnRopeBlock,
        String ropeBlock,
        int buddingMaxAge,
        int tomatoesMaxAge,
        int hangingMaxAge,
        int bonemealBonusMin,
        int bonemealBonusMax,
        float bonemealClimbChance,
        int matureAge,
        int minLight,
        int maxStackHeight,
        List<Warning> warnings
) {

    public static final String SECTION_BLOCKS = "blocks";
    public static final String SECTION_MAX_AGE = "max-age";
    public static final String SECTION_BONEMEAL = "bonemeal";

    private static final Set<String> BLOCKS_KEYS = keys("budding", "tomatoes", "crop-on-rope", "rope");
    private static final Set<String> MAX_AGE_KEYS = keys("budding", "tomatoes", "hanging");
    private static final Set<String> BONEMEAL_KEYS = keys("bonus-min", "bonus-max", "climb-chance");

    public static final String DEFAULT_BUDDING_BLOCK = "farmersdelight:budding_tomatoes";
    public static final String DEFAULT_TOMATOES_BLOCK = "farmersdelight:tomatoes";
    public static final String DEFAULT_CROP_ON_ROPE_BLOCK = "farmersdelight:tomato_crop_on_rope";
    public static final String DEFAULT_ROPE_BLOCK = "farmersdelight:rope";
    public static final int DEFAULT_MATURE_AGE = 0;
    public static final int DEFAULT_MIN_LIGHT = 9;
    public static final int DEFAULT_MAX_STACK_HEIGHT = 3;
    public static final int DEFAULT_MAX_AGE = 3;
    public static final float DEFAULT_BONEMEAL_CLIMB_CHANCE = 0.3F;
    public static final int DEFAULT_BONEMEAL_BONUS_MIN = 1;
    public static final int DEFAULT_BONEMEAL_BONUS_MAX = 4;

    public record Warning(String key, Object[] arguments) {
        public static Warning of(String key, Object... args) { return new Warning(key, args); }
        public Object[] arguments() { return arguments.clone(); }
    }

    public static TomatoVineSettings parse(Map<String, Object> arguments, String blockId) {
        Resolver resolver = new Resolver(arguments, blockId);

        Map<String, Object> blocks = resolver.section(SECTION_BLOCKS, BLOCKS_KEYS);
        String buddingBlock = resolver.string(blocks, SECTION_BLOCKS, "budding", "budding-block", DEFAULT_BUDDING_BLOCK);
        String tomatoesBlock = resolver.string(blocks, SECTION_BLOCKS, "tomatoes", "tomatoes-block", DEFAULT_TOMATOES_BLOCK);
        String cropOnRopeBlock = resolver.string(blocks, SECTION_BLOCKS, "crop-on-rope", "crop-on-rope-block", DEFAULT_CROP_ON_ROPE_BLOCK);
        String ropeBlock = resolver.string(blocks, SECTION_BLOCKS, "rope", "rope-block", DEFAULT_ROPE_BLOCK);

        Map<String, Object> maxAge = resolver.section(SECTION_MAX_AGE, MAX_AGE_KEYS);
        int buddingMaxAge = resolver.integer(maxAge, SECTION_MAX_AGE, "budding", "budding-max-age", DEFAULT_MAX_AGE);
        int tomatoesMaxAge = resolver.integer(maxAge, SECTION_MAX_AGE, "tomatoes", "tomatoes-max-age", DEFAULT_MAX_AGE);
        int hangingMaxAge = resolver.integer(maxAge, SECTION_MAX_AGE, "hanging", "hanging-max-age", DEFAULT_MAX_AGE);

        Map<String, Object> bonemeal = resolver.section(SECTION_BONEMEAL, BONEMEAL_KEYS);
        int bonemealBonusMin = resolver.integer(bonemeal, SECTION_BONEMEAL, "bonus-min", "bonemeal-bonus-min", DEFAULT_BONEMEAL_BONUS_MIN);
        int bonemealBonusMax = resolver.integer(bonemeal, SECTION_BONEMEAL, "bonus-max", "bonemeal-bonus-max", DEFAULT_BONEMEAL_BONUS_MAX);
        float bonemealClimbChance = resolver.decimal(bonemeal, SECTION_BONEMEAL, "climb-chance", "bonemeal-climb-chance", DEFAULT_BONEMEAL_CLIMB_CHANCE);

        int matureAge = BehaviorArgParser.getInt(resolver.arguments, "mature-age", DEFAULT_MATURE_AGE);
        int minLight = BehaviorArgParser.getInt(resolver.arguments, "min-light", DEFAULT_MIN_LIGHT);
        int maxStackHeight = BehaviorArgParser.getInt(resolver.arguments, "max-stack-height", DEFAULT_MAX_STACK_HEIGHT);

        return new TomatoVineSettings(
                buddingBlock, tomatoesBlock, cropOnRopeBlock, ropeBlock,
                buddingMaxAge, tomatoesMaxAge, hangingMaxAge,
                bonemealBonusMin, bonemealBonusMax, bonemealClimbChance,
                matureAge, minLight, maxStackHeight,
                List.copyOf(resolver.warnings)
        );
    }

    private static final class Resolver {
        private final Map<String, Object> arguments;
        private final String blockId;
        private final List<Warning> warnings = new ArrayList<>();

        Resolver(Map<String, Object> arguments, String blockId) {
            this.arguments = arguments != null ? arguments : Map.of();
            this.blockId = blockId != null ? blockId : "?";
        }

        Map<String, Object> section(String sectionKey, Set<String> knownKeys) {
            if (!BehaviorArgParser.isPresent(arguments, sectionKey)) {
                return null;
            }
            Map<String, Object> nested = BehaviorArgParser.getSection(arguments, sectionKey);
            if (nested == null) {
                warnings.add(Warning.of("behavior.nested_not_a_section",
                        "block", blockId, "section", sectionKey, "known", String.join(", ", knownKeys)));
                return null;
            }
            for (String key : nested.keySet()) {
                if (!knownKeys.contains(key)) {
                    warnings.add(Warning.of("behavior.nested_unknown_key",
                            "block", blockId, "section", sectionKey, "key", key,
                            "known", String.join(", ", knownKeys)));
                }
            }
            return nested;
        }

        String string(Map<String, Object> nested, String sectionKey, String nestedKey, String flatKey, String fallback) {
            if (nested != null && BehaviorArgParser.isPresent(nested, nestedKey)) {
                String value = BehaviorArgParser.getStringStrict(nested, nestedKey, fallback);
                compare(sectionKey, nestedKey, flatKey, value,
                        BehaviorArgParser.getStringStrict(arguments, flatKey, fallback));
                return value;
            }
            return BehaviorArgParser.getStringStrict(arguments, flatKey, fallback);
        }

        int integer(Map<String, Object> nested, String sectionKey, String nestedKey, String flatKey, int fallback) {
            if (nested != null && BehaviorArgParser.isPresent(nested, nestedKey)) {
                int value = BehaviorArgParser.getInt(nested, nestedKey, fallback);
                compare(sectionKey, nestedKey, flatKey, value,
                        BehaviorArgParser.getInt(arguments, flatKey, fallback));
                return value;
            }
            return BehaviorArgParser.getInt(arguments, flatKey, fallback);
        }

        float decimal(Map<String, Object> nested, String sectionKey, String nestedKey, String flatKey, float fallback) {
            if (nested != null && BehaviorArgParser.isPresent(nested, nestedKey)) {
                float value = BehaviorArgParser.getFloat(nested, nestedKey, fallback);
                compare(sectionKey, nestedKey, flatKey, value,
                        BehaviorArgParser.getFloat(arguments, flatKey, fallback));
                return value;
            }
            return BehaviorArgParser.getFloat(arguments, flatKey, fallback);
        }

        private void compare(String sectionKey, String nestedKey, String flatKey, Object nestedValue, Object flatValue) {
            if (!BehaviorArgParser.isPresent(arguments, flatKey) || Objects.equals(nestedValue, flatValue)) {
                return;
            }
            warnings.add(Warning.of("behavior.nested_overrides_flat",
                    "block", blockId,
                    "nested", sectionKey + "." + nestedKey, "nested_value", nestedValue,
                    "flat", flatKey, "flat_value", flatValue));
        }
    }

    private static Set<String> keys(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }
}
