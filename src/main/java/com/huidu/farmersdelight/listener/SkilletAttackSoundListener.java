package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

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

        // Mirror the mod (SkilletItem.playSkilletAttackSound): a charged swing is a "strong" hit — full volume
        // with a randomized pitch (0.9-1.1) so repeated hits don't sound identical; an uncharged swing is a
        // quieter, lower "weak" hit. So strong vs weak, and successive strong hits, are all audibly distinct
        // (the previous fixed volume 1.0 / pitch 1.0 made every hit sound the same).
        boolean strongAttack = player.getAttackCooldown() > 0.8F;
        String soundKey = strongAttack
                ? Constants.SOUND_SKILLET_ATTACK_STRONG
                : Constants.SOUND_SKILLET_ATTACK_WEAK;
        Sound fallback = strongAttack
                ? Sound.ENTITY_PLAYER_ATTACK_STRONG
                : Sound.ENTITY_PLAYER_ATTACK_WEAK;
        float volume = strongAttack ? 1.0f : 0.8f;
        float pitch = strongAttack
                ? 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f
                : 0.9f;

        SoundUtils.play(
                event.getEntity().getWorld(),
                event.getEntity().getLocation(),
                soundKey,
                fallback,
                SoundCategory.PLAYERS,
                volume,
                pitch
        );
    }
}

