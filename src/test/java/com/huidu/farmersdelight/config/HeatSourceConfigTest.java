package com.huidu.farmersdelight.config;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeatSourceConfigTest {

    @Test
    void acceptsStandardNamespacedBlockCharacters() throws Exception {
        assertNotNull(parseBlockState("my-pack:blocks/heat.source-1[fire:true,powered=false]"));
    }

    @Test
    void rejectsUppercaseBlockIds() throws Exception {
        assertNull(parseBlockState("MyPack:blocks/heater"));
    }

    // The shipped config makes a campfire hot only while lit (a negative "lit: false" entry followed by the
    // vanilla campfires tag), and every caller -- pot, placed skillet, tray, stove and the portable (handheld)
    // skillet -- asks that one question, so an extinguished campfire is cold everywhere.
    @Test
    void campfireIsHotOnlyWhileLit() {
        HeatSourceConfig config = new HeatSourceConfig();
        config.addVanillaTag("minecraft:campfires");

        assertTrue(config.isHeatSource(campfire(true)));
        assertFalse(config.isHeatSource(campfire(false)));
        assertFalse(config.isHeatSource(stone()));
    }

    // The shape the shipped config uses for the campfire: the negative state entry comes first, so it carves the
    // unlit state out of the broader tag entry below it.
    @Test
    void negativeStateEntryCarvesTheUnlitStateOutOfTheBroaderEntry() {
        HeatSourceConfig config = new HeatSourceConfig();
        addEntry(config, Material.CAMPFIRE, Map.of("lit", "false"), false);
        addEntry(config, Material.CAMPFIRE, Map.of(), true);

        assertTrue(config.isHeatSource(campfire(true)));
        assertFalse(config.isHeatSource(campfire(false)));
        assertFalse(config.isHeatSource(stone()));
    }

    // A rule that only ever says "matches, but is not a heat source" keeps the block cold.
    @Test
    void negativeOnlyRuleStaysCold() {
        HeatSourceConfig config = new HeatSourceConfig();
        addEntry(config, Material.CAMPFIRE, Map.of(), false);

        assertFalse(config.isHeatSource(campfire(true)));
        assertFalse(config.isHeatSource(campfire(false)));
    }

    // The entry types are private implementation details, so the test builds them reflectively instead of
    // widening their visibility. A rename or signature change surfaces here as a clear failure.
    @SuppressWarnings("unchecked")
    private static void addEntry(HeatSourceConfig config, Material material, Map<String, String> states,
                                 boolean heatSource) {
        try {
            Class<?> matcherType = Class.forName(
                    "com.huidu.farmersdelight.config.HeatSourceConfig$VanillaBlockMatcher");
            Constructor<?> matcherConstructor = matcherType.getDeclaredConstructor(Material.class, Map.class);
            matcherConstructor.setAccessible(true);
            Object matcher = matcherConstructor.newInstance(material, states);

            Class<?> entryMatcherType = Class.forName(
                    "com.huidu.farmersdelight.config.HeatSourceConfig$EntryMatcher");
            Class<?> entryType = Class.forName("com.huidu.farmersdelight.config.HeatSourceConfig$HeatEntry");
            Constructor<?> entryConstructor = entryType.getDeclaredConstructor(
                    entryMatcherType, boolean.class, boolean.class);
            entryConstructor.setAccessible(true);
            Object entry = entryConstructor.newInstance(matcher, heatSource, false);

            Field entries = HeatSourceConfig.class.getDeclaredField("entries");
            entries.setAccessible(true);
            ((List<Object>) entries.get(config)).add(entry);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("heat-source entry types changed; update this test", e);
        }
    }

    private static Block campfire(boolean lit) {
        return block(Material.CAMPFIRE, lightable(lit));
    }

    private static Block stone() {
        return block(Material.STONE, null);
    }

    private static Lightable lightable(boolean lit) {
        boolean[] value = {lit};
        return (Lightable) Proxy.newProxyInstance(
                Lightable.class.getClassLoader(),
                new Class<?>[]{Lightable.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isLit" -> value[0];
                    case "setLit" -> {
                        value[0] = (Boolean) args[0];
                        yield null;
                    }
                    case "getAsString" -> "[lit=" + value[0] + "]";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Block block(Material type, BlockData data) {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "getBlockData" -> data;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }

    private static Object parseBlockState(String value) throws Exception {
        Method method = HeatSourceConfig.class.getDeclaredMethod("parseBlockState", String.class);
        method.setAccessible(true);
        return method.invoke(new HeatSourceConfig(), value);
    }
}
