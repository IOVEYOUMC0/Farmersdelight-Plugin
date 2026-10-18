package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.huidu.farmersdelight.FarmersDelightBootstrap;
import com.huidu.farmersdelight.api.util.DamageTypePack;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StoveDamageDatapackTest {
    @TempDir Path directory;

    @Test
    void stoveBurnDamageDisablesKnockback() throws Exception {
        DamageTypePack.prepare(directory.resolve("damage-types.yml"),
                FarmersDelightBootstrap.class.getResourceAsStream("/damage-types.yml"), directory, "test", "26.2");
        Path damageType = directory.resolve("data/farmersdelight/damage_type/stove_burn.json");
        Path noKnockbackTag = directory.resolve("data/minecraft/tags/damage_type/no_knockback.json");

        assertTrue(Files.readString(damageType).contains("\"effects\":\"burning\""));
        String tag = Files.readString(noKnockbackTag);
        assertTrue(tag.contains("\"replace\":false"));
        assertTrue(tag.contains("\"farmersdelight:stove_burn\""));
    }
}
