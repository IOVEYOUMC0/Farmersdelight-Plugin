package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;

public final class RottenTomatoListener implements Listener {

    private static final String HIT_SOUND = "farmersdelight:entity.rotten_tomato.hit";

    private final FarmersDelightPlugin plugin;

    public RottenTomatoListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    // Identify rotten tomatoes from the thrown snowball's item stack.
    // The projectile supplies normal hit particles and knockback; this listener adds hit sound
    // and the raider advancement without attaching extra state to every launched snowball.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball) || !isRottenTomato(snowball)) {
            return;
        }

        // Randomize impact sound pitch between 0.8 and 1.2.
        snowball.getWorld().playSound(snowball.getLocation(), HIT_SOUND, SoundCategory.NEUTRAL, 1.0f,
                (float) ((Math.random() - Math.random()) * 0.2 + 1.0));
        if (event.getHitEntity() instanceof Raider && snowball.getShooter() instanceof Player player) {
            AdvancementManager advancementManager = this.plugin.getAdvancementManager();
            if (advancementManager != null) {
                advancementManager.award(player, "hit_raider_with_rotten_tomato");
            }
        }
    }

    private static boolean isRottenTomato(Snowball snowball) {
        ItemStack item = snowball.getItem();
        return item.getType() == Material.SNOWBALL
                && Constants.ITEM_ROTTEN_TOMATO.equals(ItemUtils.getCustomItemId(item));
    }
}
