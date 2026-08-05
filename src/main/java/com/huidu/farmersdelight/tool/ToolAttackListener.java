package com.huidu.farmersdelight.tool;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Attack listener for farmersdelight:tool items: sweep cancel, custom sounds, durability.
 */
public final class ToolAttackListener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSweepAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (resolveToolData(weapon) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        ToolData data = resolveToolData(weapon);
        if (data == null) return;

        playAttackSound(player, data);

        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            consumeDurability(weapon, player.getLocation());
        }
    }

    private void playAttackSound(Player player, ToolData data) {
        boolean strong = player.getAttackCooldown() > 0.8F;
        String soundKey = data.attackSoundWeak() != null && !strong
                ? data.attackSoundWeak()
                : data.attackSound();
        float volume = strong ? 1.0f : 0.8f;
        float pitch = strong
                ? 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f
                : 0.9f;

        Sound sound = resolveSound(soundKey);
        if (sound == null) return;
        player.getWorld().playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static Sound resolveSound(String namespaced) {
        NamespacedKey key = NamespacedKey.fromString(namespaced);
        if (key == null) return null;
        return Registry.SOUNDS.get(key);
    }

    /** Resolve ToolData from a Bukkit ItemStack via CE custom item ID. */
    public static ToolData resolveToolData(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        Key id = CraftEngineItems.getCustomItemId(item);
        if (id == null) return null;
        return ToolRegistry.get(id).orElse(null);
    }

    /** Consume 1 durability, break the item at zero. Unbreaking applies. */
    public static void consumeDurability(ItemStack item, Location breakSoundLocation) {
        if (!(item.getItemMeta() instanceof Damageable damageable) || damageable.isUnbreakable()) return;
        if (!damageable.hasMaxDamage()) return;

        if (damageable.hasEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING)) {
            int level = damageable.getEnchantLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
            if (java.util.concurrent.ThreadLocalRandom.current().nextInt(level + 1) > 0) return;
        }

        int maxDamage = damageable.getMaxDamage();
        int currentDamage = damageable.getDamage();
        if (currentDamage >= maxDamage) return;

        currentDamage++;
        if (currentDamage >= maxDamage) {
            item.setAmount(0);
            if (breakSoundLocation != null) {
                breakSoundLocation.getWorld().playSound(breakSoundLocation, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            }
            return;
        }

        damageable.setDamage(currentDamage);
        item.setItemMeta(damageable);
    }
}
