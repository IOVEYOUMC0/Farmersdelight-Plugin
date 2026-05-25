package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.concurrent.ThreadLocalRandom;

public class PetFoodListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public PetFoodListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof LivingEntity entity)) return;
        
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getHand());
        
        if (item == null || item.getType().isAir()) return;
        
        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId == null) return;
        
        PetFoodConfig config = plugin.getPetFoodConfig();
        if (config == null) return;
        
        PetFoodConfig.PetFoodDefinition definition = config.getFoodDefinition(customItemId);
        if (definition == null) return;
        
        if (!definition.entities.contains(entity.getType())) return;
        
        if (handlePetFood(player, entity, item, definition)) {
            event.setCancelled(true);
        }
    }

    private boolean handlePetFood(Player player, LivingEntity entity, ItemStack item, PetFoodConfig.PetFoodDefinition definition) {
        if (definition.requireTamed) {
            if (entity instanceof Tameable tameable) {
                if (!tameable.isTamed()) return false;
            } else if (entity instanceof AbstractHorse horse) {
                if (!horse.isTamed()) return false;
            }
        }
        
        if (entity.isDead()) return false;
        
        if (definition.restoreHealth) {
            var maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealthAttr != null) {
                entity.setHealth(maxHealthAttr.getValue());
            }
        }
        
        for (PetFoodConfig.EffectDefinition effect : definition.effects) {
            entity.addPotionEffect(new PotionEffect(
                    effect.type(),
                    effect.duration(),
                    effect.amplifier(),
                    effect.ambient(),
                    effect.particles()
            ));
        }
        
        if (definition.sound != null) {
            entity.getWorld().playSound(entity.getLocation(), definition.sound, definition.soundVolume, definition.soundPitch);
        }
        
        if (definition.particles) {
            spawnParticles(entity, definition.particleType, definition.particleCount);
        }
        
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        
        return true;
    }

    private void spawnParticles(LivingEntity entity, Particle particleType, int count) {
        World world = entity.getWorld();
        Location loc = entity.getLocation();
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        for (int i = 0; i < count; i++) {
            double offsetX = (random.nextDouble() - 0.5) * entity.getWidth();
            double offsetY = random.nextDouble() * entity.getHeight();
            double offsetZ = (random.nextDouble() - 0.5) * entity.getWidth();
            
            double speedX = random.nextGaussian() * 0.02;
            double speedY = random.nextGaussian() * 0.02;
            double speedZ = random.nextGaussian() * 0.02;
            
            world.spawnParticle(
                    particleType,
                    loc.getX() + offsetX,
                    loc.getY() + offsetY + 0.5,
                    loc.getZ() + offsetZ,
                    1,
                    speedX, speedY, speedZ,
                    0.0
            );
        }
    }
}

