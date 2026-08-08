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

    // The tomato is a snowball-based CraftEngine item, so the thrown Snowball keeps its custom id on its own
    // item stack; identify it at hit time straight from that item instead of marking every launched snowball
    // with a PDC flag. The snowball base already reproduces the mod's RottenTomatoEntity on-hit behaviour
    // (zero-damage hit + knockback + item-crack splat particles), so this only layers on the custom hit sound
    // and the raider advancement. The mod applies no mob effect on hit, so neither do we.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball) || !isRottenTomato(snowball)) {
            return;
        }

        snowball.getWorld().playSound(snowball.getLocation(), HIT_SOUND, SoundCategory.PLAYERS, 1.0f, 1.0f);
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
