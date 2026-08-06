package com.huidu.farmersdelight.util;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

public final class VanillaAdvancements {

    // "A Seedy Place" — earned in vanilla by planting any seed. Its criteria are a single OR requirement,
    // so completing one criterion finishes the advancement.
    private static final NamespacedKey PLANT_SEED = NamespacedKey.minecraft("husbandry/plant_seed");

    private VanillaAdvancements() {
    }

    public static void grantPlantSeed(Player player) {
        if (player == null) {
            return;
        }
        Advancement advancement = Bukkit.getAdvancement(PLANT_SEED);
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (progress.isDone()) {
            return;
        }
        for (String criterion : progress.getRemainingCriteria()) {
            progress.awardCriteria(criterion);
            return;
        }
    }
}
