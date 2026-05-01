package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

public final class SkilletAttackSoundListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        String customItemId = ItemUtils.getCustomItemId(mainHand);
        if (!Constants.ITEM_SKILLET.equals(customItemId)) {
            return;
        }

        boolean strongAttack = player.getAttackCooldown() >= 0.9F;
        String soundKey = strongAttack
                ? Constants.SOUND_SKILLET_ATTACK_STRONG
                : Constants.SOUND_SKILLET_ATTACK_WEAK;
        Sound fallback = strongAttack
                ? Sound.ENTITY_PLAYER_ATTACK_STRONG
                : Sound.ENTITY_PLAYER_ATTACK_WEAK;

        SoundUtils.play(
                event.getEntity().getWorld(),
                event.getEntity().getLocation(),
                soundKey,
                fallback,
                1.0f,
                1.0f
        );
    }
}
