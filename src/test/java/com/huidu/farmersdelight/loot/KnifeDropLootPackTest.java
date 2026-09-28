package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.util.Constants;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the knife drops the bundled CraftEngine pack declares and the CraftEngine types the plugin registers in
 * step: the pack references them by id, so a renamed or deleted constant would otherwise only show up as an
 * "unknown condition type" warning on a live server.
 *
 * <p>List contents are reached through the deserialized maps rather than a dotted path, because the Bukkit
 * configuration path syntax used by these tests has no list index segment.
 */
class KnifeDropLootPackTest {

    private static final String ROOT = "vanilla_loots.";
    private static final Path PACK = Path.of("src", "main", "resources", "craftengine", "farmersdelight",
            "configuration", "vanilla_loots.yml");

    @Test
    void customTypesReferencedByThePackAreRegisteredByThePlugin() {
        Set<String> used = new TreeSet<>();
        collectFarmersDelightTypes(config().getValues(true), used);
        assertEquals(new TreeSet<>(Set.of(
                Constants.CONDITION_IS_ADULT,
                Constants.CONDITION_IS_BURNING,
                Constants.CONDITION_IS_KNIFE,
                Constants.LOOT_FUNCTION_AWARD_ADVANCEMENT)), used);
    }

    @Test
    void pigAndHoglinHamRulesCarryTheChanceAndTheAdvancement() {
        for (String entity : List.of("pig", "hoglin")) {
            ConfigurationSection normal = section("farmersdelight:ham_from_" + entity);
            ConfigurationSection burning = section("farmersdelight:smoked_ham_from_burning_" + entity);

            assertEquals("minecraft:" + entity, normal.getString("target"));
            assertEquals("farmersdelight:ham", firstEntry(normal, 0).get("item"));
            assertEquals("farmersdelight:smoked_ham", firstEntry(burning, 0).get("item"));
            // The ham is what earns the advancement, so the augmentation rides on the item itself.
            assertEquals("get_ham", firstFunction(normal, 0, 0).get("advancement"));
            assertEquals("get_ham", firstFunction(burning, 0, 0).get("advancement"));
            assertEquals(List.of(Constants.LOOT_FUNCTION_AWARD_ADVANCEMENT),
                    functionTypes(normal, 0, 0));
        }
        // A pig's ham chance is 0.5 plus 0.1 per Looting level, which is the chances column table_bonus reads.
        assertEquals(List.of(0.5, 0.6, 0.7, 0.8), tableBonusChances(section("farmersdelight:ham_from_pig"), 0));
    }

    @Test
    void nonMeatRulesOnlyFireWhileTheTargetIsNotBurning() {
        Map<String, String> rules = Map.ofEntries(
                Map.entry("farmersdelight:leather_from_cow", "minecraft:cow|minecraft:leather"),
                Map.entry("farmersdelight:leather_from_mooshroom", "minecraft:mooshroom|minecraft:leather"),
                Map.entry("farmersdelight:leather_from_donkey", "minecraft:donkey|minecraft:leather"),
                Map.entry("farmersdelight:leather_from_horse", "minecraft:horse|minecraft:leather"),
                Map.entry("farmersdelight:leather_from_mule", "minecraft:mule|minecraft:leather"),
                Map.entry("farmersdelight:leather_from_llama", "minecraft:llama|minecraft:leather"),
                Map.entry("farmersdelight:leather_from_trader_llama", "minecraft:trader_llama|minecraft:leather"),
                Map.entry("farmersdelight:feather_from_chicken", "minecraft:chicken|minecraft:feather"),
                Map.entry("farmersdelight:string_from_spider", "minecraft:spider|minecraft:string"),
                Map.entry("farmersdelight:string_from_cave_spider", "minecraft:cave_spider|minecraft:string"),
                Map.entry("farmersdelight:rabbit_hide_from_rabbit", "minecraft:rabbit|minecraft:rabbit_hide"),
                Map.entry("farmersdelight:shulker_shell_from_shulker", "minecraft:shulker|minecraft:shulker_shell"));

        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String[] expected = rule.getValue().split("\\|");
            ConfigurationSection entry = section(rule.getKey());

            assertEquals(expected[0], entry.getString("target"), rule.getKey());
            assertEquals(expected[1], firstEntry(entry, 0).get("item"), rule.getKey());
            assertEquals(List.of("has_player", Constants.CONDITION_IS_ADULT, Constants.CONDITION_IS_KNIFE,
                    "inverted"), conditionTypes(entry, 0), rule.getKey());
            // The knife rule stays out of the way while the target burns; that is what the inverted term means.
            assertEquals(List.of(Constants.CONDITION_IS_BURNING),
                    types(conditions(entry, 0).get(3).get("terms")), rule.getKey());
            assertEquals(List.of(), functionTypes(entry, 0, 0), rule.getKey());
        }
    }

    private static void collectFarmersDelightTypes(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type instanceof String text && text.startsWith("farmersdelight:")) {
                out.add(text);
            }
            for (Object value : map.values()) {
                collectFarmersDelightTypes(value, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                collectFarmersDelightTypes(value, out);
            }
        }
    }

    private static YamlConfiguration config() {
        assertTrue(Files.exists(PACK), PACK.toString());
        return YamlConfiguration.loadConfiguration(PACK.toFile());
    }

    private static ConfigurationSection section(String id) {
        ConfigurationSection section = config().getConfigurationSection(ROOT + id);
        assertNotNull(section, id);
        return section;
    }

    private static List<Map<?, ?>> conditions(ConfigurationSection section, int pool) {
        return maps(pools(section).get(pool).get("conditions"));
    }

    private static Map<?, ?> firstEntry(ConfigurationSection section, int pool) {
        return maps(pools(section).get(pool).get("entries")).get(0);
    }

    private static Map<?, ?> firstFunction(ConfigurationSection section, int pool, int entry) {
        return maps(firstEntry(section, pool).get("functions")).get(0);
    }

    private static List<Map<?, ?>> pools(ConfigurationSection section) {
        return section.getMapList("loot.pools");
    }

    private static List<String> conditionTypes(ConfigurationSection section, int pool) {
        return types(conditions(section, pool));
    }

    private static List<String> functionTypes(ConfigurationSection section, int pool, int entry) {
        return types(maps(maps(pools(section).get(pool).get("entries")).get(entry).get("functions")));
    }

    private static List<Double> tableBonusChances(ConfigurationSection section, int pool) {
        for (Map<?, ?> condition : conditions(section, pool)) {
            if ("table_bonus".equals(condition.get("type"))) {
                List<Double> chances = new ArrayList<>();
                for (Object chance : (List<?>) condition.get("chances")) {
                    chances.add(((Number) chance).doubleValue());
                }
                return chances;
            }
        }
        return List.of();
    }

    private static List<String> types(Object node) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> map : maps(node)) {
            out.add(String.valueOf(map.get("type")));
        }
        return List.copyOf(new LinkedHashSet<>(out));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> maps(Object node) {
        if (node == null) {
            return List.of();
        }
        return (List<Map<?, ?>>) node;
    }
}
