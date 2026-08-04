package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.util.ItemUtils;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Locale;

/** Applies the configurable positional damage effect of the backstabbing enchantment. */
public final class BackstabListener implements Listener {

    private final FarmersDelightPlugin plugin;
    private volatile EnchantmentSettings.Backstabbing settings;
    private volatile boolean enabled;
    private volatile Enchantment backstabEnchantment;

    public BackstabListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        reload(plugin.getEnchantmentSettings(), plugin.isBackstabEnchantmentEnabled());
    }

    public void reload(EnchantmentSettings enchantmentSettings, boolean effectiveEnabled) {
        EnchantmentSettings resolved = enchantmentSettings == null
                ? EnchantmentSettings.defaults()
                : enchantmentSettings;
        settings = resolved.backstabbing();
        enabled = resolved.enabled() && effectiveEnabled && settings.enabled();
        backstabEnchantment = null;
    }

    /** Kept for addon/source compatibility; config reloads should use reload. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled && settings != null && settings.enabled();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        EnchantmentSettings.Backstabbing current = settings;
        if (!enabled || current == null || !(event.getDamager() instanceof LivingEntity attacker)
                || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }

        EnchantmentSettings.Backstabbing.Combat combat = current.combat();
        if (combat.playersOnly() && !(attacker instanceof Player)) {
            return;
        }

        ItemStack weapon = attacker.getEquipment() == null
                ? null
                : attacker.getEquipment().getItemInMainHand();
        if (weapon == null || weapon.isEmpty() || (combat.requireKnife() && !isKnife(weapon))) {
            return;
        }

        Enchantment enchantment = resolveEnchantment(current.id());
        if (enchantment == null) {
            return;
        }
        int level = weapon.getEnchantmentLevel(enchantment);
        if (level <= 0 || !isBehindTarget(target, attacker, combat.behindDotThreshold(),
                combat.minimumHorizontalDistanceSquared())) {
            return;
        }

        event.setDamage(event.getDamage() * combat.multiplier(level));
        LivingEntity soundOwner = combat.soundLocation() == EnchantmentSettings.SoundLocation.ATTACKER
                ? attacker
                : target;
        soundOwner.getWorld().playSound(
                soundOwner.getLocation(),
                combat.sound(),
                combat.soundVolume(),
                combat.soundPitch()
        );
    }

    private Enchantment resolveEnchantment(String id) {
        Enchantment cached = backstabEnchantment;
        if (cached != null) {
            return cached;
        }
        NamespacedKey key = NamespacedKey.fromString(id);
        if (key == null) {
            return null;
        }
        cached = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
        backstabEnchantment = cached;
        return cached;
    }

    private boolean isKnife(ItemStack item) {
        for (String itemId : ItemUtils.getItemIds(item)) {
            if (plugin.isKnifeItemId(itemId)) {
                return true;
            }
        }
        for (String tagId : ItemUtils.getItemTagIds(item)) {
            if (plugin.getKnifeTagIds().contains(tagId.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean isBehindTarget(
            LivingEntity target,
            LivingEntity attacker,
            double threshold,
            double minimumDistanceSquared
    ) {
        return isBehind(
                target.getLocation().getDirection(),
                attacker.getLocation().toVector().subtract(target.getLocation().toVector()),
                threshold,
                minimumDistanceSquared
        );
    }

    static boolean isBehind(Vector targetFacing, Vector targetToAttacker, double threshold,
                            double minimumDistanceSquared) {
        Vector horizontalFacing = targetFacing.clone().setY(0);
        Vector horizontalOffset = targetToAttacker.clone().setY(0);
        if (horizontalFacing.lengthSquared() < 1.0E-12
                || horizontalOffset.lengthSquared() < minimumDistanceSquared) {
            return false;
        }
        return horizontalFacing.normalize().dot(horizontalOffset.normalize()) < threshold;
    }
}
