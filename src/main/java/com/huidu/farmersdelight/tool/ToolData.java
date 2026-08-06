package com.huidu.farmersdelight.tool;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;

public record ToolData(int maxDurability, int enchantability, String attackSound, String attackSoundWeak) {

    public static ToolData fromConfig(ConfigSection section) {
        int maxDurability = section.getInt("max-durability", 0);
        int enchantability = section.getInt("enchantability", 0);
        String attackSound = section.getString("attack-sound", "minecraft:entity.player.attack.sweep");
        String attackSoundWeak = section.containsKey("attack-sound-weak")
                ? section.getString("attack-sound-weak", "")
                : null;
        return new ToolData(maxDurability, enchantability, attackSound, attackSoundWeak);
    }

    public boolean isValid() {
        return maxDurability > 0;
    }
}
