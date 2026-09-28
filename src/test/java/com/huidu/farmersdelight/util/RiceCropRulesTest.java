package com.huidu.farmersdelight.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiceCropRulesTest {

    @Test
    void acceptsOneSourceWaterBlockOnSoil() {
        assertTrue(RiceCropRules.canPlantRiceAt(water(0, Material.DIRT)));
    }

    @Test
    void rejectsFlowingWater() {
        assertFalse(RiceCropRules.canPlantRiceAt(water(1, Material.DIRT)));
    }

    @Test
    void rejectsWaterWithAnotherWaterBlockBelow() {
        assertFalse(RiceCropRules.canPlantRiceAt(water(0, Material.WATER)));
    }

    @Test
    void rejectsSourceWaterWithAnotherWaterBlockAbove() {
        assertFalse(RiceCropRules.canPlantRiceAt(waterWithAbove(Material.WATER)));
    }

    @Test
    void acceptsSourceWaterWithAirAbove() {
        assertTrue(RiceCropRules.isSingleSourceWater(water(0, Material.DIRT)));
    }

    @Test
    void rejectsWaterWithoutSupportingBlock() {
        assertFalse(RiceCropRules.canPlantRiceAt(water(0, Material.AIR)));
    }

    private static Block water(int level, Material belowType) {
        Levelled data = levelled(level);
        Block below = block(belowType, null, null);
        return block(Material.WATER, data, below, null);
    }

    private static Block waterWithAbove(Material aboveType) {
        return block(Material.WATER, levelled(0), block(Material.DIRT, null, null),
                block(aboveType, null, null));
    }

    private static Levelled levelled(int level) {
        int[] value = {level};
        return (Levelled) Proxy.newProxyInstance(
                Levelled.class.getClassLoader(),
                new Class<?>[]{Levelled.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLevel" -> value[0];
                    case "setLevel" -> {
                        value[0] = (Integer) args[0];
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Block block(Material type, BlockData data, Block below) {
        return block(type, data, below, null);
    }

    private static Block block(Material type, BlockData data, Block below, Block above) {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "getBlockData" -> data;
                    case "getRelative" -> args != null && args.length == 1
                            ? args[0] == BlockFace.DOWN ? below : args[0] == BlockFace.UP ? above : proxy
                            : proxy;
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
}
