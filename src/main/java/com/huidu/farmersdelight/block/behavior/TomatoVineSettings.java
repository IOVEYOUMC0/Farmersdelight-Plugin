package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The thirteen configurable values of the farmersdelight:tomato_vine behavior, resolved once per
 * block load from the behavior's raw argument map.
 *
 * Two spellings are accepted for ten of them. The nested spelling groups the sibling block ids, the
 * per-stage growth caps and the bone meal numbers into sections:
 *
 *   blocks:
 *     budding: farmersdelight:budding_tomatoes
 *     tomatoes: farmersdelight:tomatoes
 *     crop-on-rope: farmersdelight:tomato_crop_on_rope
 *     rope: farmersdelight:rope
 *   max-age:
 *     budding: 3
 *     tomatoes: 3
 *     hanging: 3
 *   bonemeal:
 *     bonus-min: 1
 *     bonus-max: 4
 *     climb-chance: 0.3
 *
 * The flat spelling (budding-block, budding-max-age, bonemeal-bonus-min, ...) remains permanently
 * supported with identical meaning. It is an alias, not a deprecation: CraftEngine writes a pack
 * resource only when the target file is absent, so an installed crops.yml is never rewritten and a
 * rename would leave the old key silently unread while its default took over. Every flat key
 * therefore keeps working for as long as the behavior does.
 *
 * The three values that do not belong to a cluster (mature-age, max-stack-height, min-light) have
 * one spelling only, at the top level. A section wrapping a single key would add depth for nothing.
 *
 * Resolution order when both spellings appear for the same value: the nested one wins, and it wins
 * outright. A nested value that fails to parse falls back to the built-in default rather than to
 * the flat alias, so which key is in force depends only on which keys are written, never on whether
 * a value happens to be well formed. A conflict where the two spellings resolve differently is
 * reported once per key at load.
 *
 * Unknown keys inside a recognised section are reported too. Behavior keys are matched by exact
 * hyphenated name with no normalisation and nothing rejects a key it does not know, so a typo
 * otherwise vanishes and the default quietly takes effect. Accepting a second shape widens that
 * surface, which is why every section names its unrecognised keys on the console. Reporting stops
 * at the sections: the top level of the argument map is shared with keys the engine itself puts
 * there (the behavior's own type, among others), so FarmersDelight does not own that key space and
 * cannot tell a typo from an engine key there.
 *
 * A warning never removes the block from the game. Warnings are collected here and emitted by the
 * caller, which keeps this resolution free of plugin state and directly testable.
 */
public final class TomatoVineSettings {

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

    /** A console line the caller emits through the plugin's I18n logger. */
    public static final class Warning {
        private final String key;
        private final Object[] arguments;

        Warning(String key, Object... arguments) {
            this.key = key;
            this.arguments = arguments;
        }

        public String key() {
            return key;
        }

        public Object[] arguments() {
            return arguments.clone();
        }
    }

    private final String buddingBlock;
    private final String tomatoesBlock;
    private final String cropOnRopeBlock;
    private final String ropeBlock;
    private final int buddingMaxAge;
    private final int tomatoesMaxAge;
    private final int hangingMaxAge;
    private final int bonemealBonusMin;
    private final int bonemealBonusMax;
    private final float bonemealClimbChance;
    private final int matureAge;
    private final int minLight;
    private final int maxStackHeight;
    private final List<Warning> warnings;

    private TomatoVineSettings(Resolver resolver) {
        Map<String, Object> blocks = resolver.section(SECTION_BLOCKS, BLOCKS_KEYS);
        this.buddingBlock = resolver.string(blocks, SECTION_BLOCKS, "budding", "budding-block", DEFAULT_BUDDING_BLOCK);
        this.tomatoesBlock = resolver.string(blocks, SECTION_BLOCKS, "tomatoes", "tomatoes-block", DEFAULT_TOMATOES_BLOCK);
        this.cropOnRopeBlock = resolver.string(blocks, SECTION_BLOCKS, "crop-on-rope", "crop-on-rope-block", DEFAULT_CROP_ON_ROPE_BLOCK);
        this.ropeBlock = resolver.string(blocks, SECTION_BLOCKS, "rope", "rope-block", DEFAULT_ROPE_BLOCK);

        Map<String, Object> maxAge = resolver.section(SECTION_MAX_AGE, MAX_AGE_KEYS);
        this.buddingMaxAge = resolver.integer(maxAge, SECTION_MAX_AGE, "budding", "budding-max-age", DEFAULT_MAX_AGE);
        this.tomatoesMaxAge = resolver.integer(maxAge, SECTION_MAX_AGE, "tomatoes", "tomatoes-max-age", DEFAULT_MAX_AGE);
        this.hangingMaxAge = resolver.integer(maxAge, SECTION_MAX_AGE, "hanging", "hanging-max-age", DEFAULT_MAX_AGE);

        Map<String, Object> bonemeal = resolver.section(SECTION_BONEMEAL, BONEMEAL_KEYS);
        this.bonemealBonusMin = resolver.integer(bonemeal, SECTION_BONEMEAL, "bonus-min", "bonemeal-bonus-min", DEFAULT_BONEMEAL_BONUS_MIN);
        this.bonemealBonusMax = resolver.integer(bonemeal, SECTION_BONEMEAL, "bonus-max", "bonemeal-bonus-max", DEFAULT_BONEMEAL_BONUS_MAX);
        this.bonemealClimbChance = resolver.decimal(bonemeal, SECTION_BONEMEAL, "climb-chance", "bonemeal-climb-chance", DEFAULT_BONEMEAL_CLIMB_CHANCE);

        // No cluster to join, so no section: one spelling, read straight off the top level.
        this.matureAge = BehaviorArgParser.getInt(resolver.arguments, "mature-age", DEFAULT_MATURE_AGE);
        this.minLight = BehaviorArgParser.getInt(resolver.arguments, "min-light", DEFAULT_MIN_LIGHT);
        this.maxStackHeight = BehaviorArgParser.getInt(resolver.arguments, "max-stack-height", DEFAULT_MAX_STACK_HEIGHT);

        this.warnings = List.copyOf(resolver.warnings);
    }

    /**
     * Resolve every value from one behavior's arguments. The block id is only used to name the
     * offending block in warnings.
     */
    public static TomatoVineSettings parse(Map<String, Object> arguments, String blockId) {
        return new TomatoVineSettings(new Resolver(arguments, blockId));
    }

    private static final class Resolver {
        private final Map<String, Object> arguments;
        private final String blockId;
        private final List<Warning> warnings = new ArrayList<>();

        Resolver(Map<String, Object> arguments, String blockId) {
            this.arguments = arguments != null ? arguments : Map.of();
            this.blockId = blockId != null ? blockId : "?";
        }

        /**
         * The sub-keys written under a section name, or null when the author did not write the
         * section. A section name carrying a scalar instead of sub-keys is reported and then
         * treated as absent, which leaves the flat aliases in charge rather than dropping the
         * values to their defaults.
         */
        Map<String, Object> section(String sectionKey, Set<String> knownKeys) {
            if (!BehaviorArgParser.isPresent(arguments, sectionKey)) {
                return null;
            }
            Map<String, Object> nested = BehaviorArgParser.getSection(arguments, sectionKey);
            if (nested == null) {
                warnings.add(new Warning("behavior.nested_not_a_section",
                        "block", blockId, "section", sectionKey, "known", String.join(", ", knownKeys)));
                return null;
            }
            for (String key : nested.keySet()) {
                if (!knownKeys.contains(key)) {
                    warnings.add(new Warning("behavior.nested_unknown_key",
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

        /**
         * Report a value written both ways whose two spellings do not agree. Writing the same value
         * twice is harmless and stays silent; only a disagreement changes what the block does and
         * therefore needs saying, since the flat key an author edited would otherwise appear to be
         * ignored for no visible reason.
         */
        private void compare(String sectionKey, String nestedKey, String flatKey, Object nestedValue, Object flatValue) {
            if (!BehaviorArgParser.isPresent(arguments, flatKey) || Objects.equals(nestedValue, flatValue)) {
                return;
            }
            warnings.add(new Warning("behavior.nested_overrides_flat",
                    "block", blockId,
                    "nested", sectionKey + "." + nestedKey, "nested_value", nestedValue,
                    "flat", flatKey, "flat_value", flatValue));
        }
    }

    private static Set<String> keys(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }

    public String buddingBlock() {
        return buddingBlock;
    }

    public String tomatoesBlock() {
        return tomatoesBlock;
    }

    public String cropOnRopeBlock() {
        return cropOnRopeBlock;
    }

    public String ropeBlock() {
        return ropeBlock;
    }

    public int buddingMaxAge() {
        return buddingMaxAge;
    }

    public int tomatoesMaxAge() {
        return tomatoesMaxAge;
    }

    public int hangingMaxAge() {
        return hangingMaxAge;
    }

    public int bonemealBonusMin() {
        return bonemealBonusMin;
    }

    public int bonemealBonusMax() {
        return bonemealBonusMax;
    }

    public float bonemealClimbChance() {
        return bonemealClimbChance;
    }

    public int matureAge() {
        return matureAge;
    }

    public int minLight() {
        return minLight;
    }

    public int maxStackHeight() {
        return maxStackHeight;
    }

    public List<Warning> warnings() {
        return warnings;
    }
}
