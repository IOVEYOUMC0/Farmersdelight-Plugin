package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StoveDamageDatapackTest {

    @Test
    void stoveBurnDamageDisablesKnockback() throws Exception {
        Path damageType = Path.of("src", "main", "resources", "datapack", "loot", "data",
                "farmersdelight", "damage_type", "stove_burn.json");
        Path noKnockbackTag = Path.of("src", "main", "resources", "datapack", "loot", "data",
                "minecraft", "tags", "damage_type", "no_knockback.json");

        assertTrue(Files.readString(damageType).contains("\"effects\": \"burning\""));
        String tag = Files.readString(noKnockbackTag);
        assertTrue(tag.contains("\"replace\": false"));
        assertTrue(tag.contains("\"farmersdelight:stove_burn\""));
    }
}
