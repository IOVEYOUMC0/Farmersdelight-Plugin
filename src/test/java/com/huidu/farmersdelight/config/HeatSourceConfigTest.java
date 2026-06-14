package com.huidu.farmersdelight.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HeatSourceConfigTest {

    @Test
    void acceptsStandardNamespacedBlockCharacters() throws Exception {
        assertNotNull(parseBlockState("my-pack:blocks/heat.source-1[fire:true,powered=false]"));
    }

    @Test
    void rejectsUppercaseBlockIds() throws Exception {
        assertNull(parseBlockState("MyPack:blocks/heater"));
    }

    private static Object parseBlockState(String value) throws Exception {
        Method method = HeatSourceConfig.class.getDeclaredMethod("parseBlockState", String.class);
        method.setAccessible(true);
        return method.invoke(new HeatSourceConfig(), value);
    }
}
