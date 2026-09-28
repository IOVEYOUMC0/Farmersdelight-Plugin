package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagDatapackInstallerTest {

    @Test
    void rendersMembersInStableOrder() {
        assertEquals("{\n  \"replace\": false,\n  \"values\": [\n"
                        + "    \"minecraft:apple\",\n"
                        + "    \"minecraft:bread\"\n  ]\n}\n",
                TagDatapackInstaller.renderTag(Set.of("minecraft:bread", "minecraft:apple")));
    }
}
