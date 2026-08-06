package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class RottenTomatoListener implements Listener {

    private static final byte PROJECTILE_MARKER = 1;
    private static final String HIT_SOUND = "farmersdelight:entity.rotten_tomato.hit";

    private final FarmersDelightPlugin plugin;
    private final NamespacedKey projectileKey;

    public RottenTomatoListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.projectileKey = new NamespacedKey(plugin, "rotten_tomato_projectile");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) {
            return;
        }

        ItemStack item = snowball.getItem();
        if (item.getType() != Material.SNOWBALL) {
            return;
        }
        if (!Constants.ITEM_ROTTEN_TOMATO.equals(ItemUtils.getCustomItemId(item))) {
            return;
        }

        snowball.getPersistentDataContainer().set(this.projectileKey, PersistentDataType.BYTE, PROJECTILE_MARKER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball) || !isRottenTomato(snowball)) {
            return;
        }

        snowball.getWorld().playSound(snowball.getLocation(), HIT_SOUND, SoundCategory.PLAYERS, 1.0f, 1.0f);
        if (!(event.getHitEntity() instanceof Raider)) {
            return;
        }
        if (!(snowball.getShooter() instanceof Player player)) {
            return;
        }

        AdvancementManager advancementManager = this.plugin.getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "hit_raider_with_rotten_tomato");
        }
    }

    private boolean isRottenTomato(Snowball snowball) {
        Byte marker = snowball.getPersistentDataContainer().get(this.projectileKey, PersistentDataType.BYTE);
        return marker != null && marker == PROJECTILE_MARKER;
    }
}
