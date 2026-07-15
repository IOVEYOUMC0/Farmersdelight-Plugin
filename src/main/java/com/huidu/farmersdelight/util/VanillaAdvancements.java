package com.huidu.farmersdelight.util;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

/**
 * Grants vanilla advancements that FarmersDelight actions would earn if they used vanilla mechanics.
 * FarmersDelight crops are CraftEngine custom blocks placed programmatically (CraftEngineBlocks.place),
 * so vanilla's minecraft:placed_block trigger never fires and the corresponding advancement is
 * never awarded. These helpers complete it manually via the Bukkit Advancement API.
 */
public final class VanillaAdvancements {

    // "A Seedy Place" — earned in vanilla by planting any seed. Its criteria are a single OR requirement,
    // so completing one criterion finishes the advancement.
    private static final NamespacedKey PLANT_SEED = NamespacedKey.minecraft("husbandry/plant_seed");

    private VanillaAdvancements() {
    }

    /** Completes the vanilla "A Seedy Place" advancement for the player. Idempotent and null-safe. */
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
