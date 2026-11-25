package com.huidu.farmersdelight.tool;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.api.tag.FarmersDelightTags;
import com.huidu.farmersdelight.util.ItemUtils;
import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

public final class ToolAttackListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public ToolAttackListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reduces the knockback of a hit made with a knife by 0.1 strength, matching the mod: a knife trades
     * knockback for damage. The event only exposes the resulting acceleration vector, so the strength is
     * converted to the scale the vector needs.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackByEntityEvent event) {
        if (!(event.getHitBy() instanceof Player player)) {
            return;
        }
        // On Folia the damage event may run on the victim's region; the attacker inventory/state belongs to
        // another region and must not be read here.
        if (!owns(player)) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon == null || weapon.isEmpty()
                || !ItemUtils.matchesCustomOrVanillaTag(weapon, FarmersDelightTags.ITEM_KNIVES)) {
            return;
        }
        float strength = event.getKnockbackStrength();
        if (strength <= 0.1F) {
            return;
        }
        Vector knockback = event.getKnockback();
        if (knockback == null) {
            return;
        }
        event.setKnockback(knockback.multiply((strength - 0.1F) / strength));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        // Sound + durability are attacker-owned state. If the event runs on the victim's region, hop to the
        // player's scheduler instead of touching the player's inventory from a foreign region.
        if (!owns(player)) {
            plugin.scheduler().runForEntity(player, () -> applyAttack(player));
            return;
        }
        applyAttack(player);
    }

    private void applyAttack(Player player) {
        ItemStack weapon = player.getInventory().getItemInMainHand();
        ToolData data = resolveToolData(weapon);
        if (data == null) return;

        playAttackSound(player, data);

        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeDurability(weapon, player.getLocation());
        }
    }

    private boolean owns(Player player) {
        return plugin.scheduler() == null || plugin.scheduler().isOwnedByCurrentRegion(player);
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

        // Custom resource-pack sounds (e.g. farmersdelight:item.skillet.attack.strong) are NOT in Bukkit's
        // Registry.SOUNDS; SoundUtils falls back to playing the string key, which resolves against sounds.json.
        SoundUtils.play(player.getWorld(), player.getLocation(), soundKey,
                Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, volume, pitch);
    }

    public static ToolData resolveToolData(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        Key id = CraftEngineItems.getCustomItemId(item);
        if (id == null) return null;
        return ToolRegistry.get(id).orElse(null);
    }

    public static void consumeDurability(ItemStack item, Location breakSoundLocation) {
        if (item == null || item.isEmpty()
                || !(item.getItemMeta() instanceof Damageable damageable)
                || damageable.isUnbreakable()) {
            return;
        }

        if (damageable.hasEnchant(Enchantment.UNBREAKING)) {
            int level = damageable.getEnchantLevel(Enchantment.UNBREAKING);
            if (ThreadLocalRandom.current().nextInt(level + 1) > 0) {
                return;
            }
        }

        int maxDamage = damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
        if (maxDamage <= 0) {
            return;
        }

        int currentDamage = Math.max(0, damageable.getDamage());
        int damageAfterUse = damageAfterUse(currentDamage, maxDamage);
        if (damageAfterUse < 0) {
            item.setAmount(0);
            if (breakSoundLocation != null && breakSoundLocation.getWorld() != null) {
                breakSoundLocation.getWorld().playSound(breakSoundLocation, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            }
            return;
        }

        damageable.setDamage(damageAfterUse);
        item.setItemMeta(damageable);
    }

    static int damageAfterUse(int currentDamage, int maxDamage) {
        int normalizedDamage = Math.max(0, currentDamage);
        if (maxDamage <= 0) {
            return normalizedDamage;
        }
        return normalizedDamage + 1 >= maxDamage ? -1 : normalizedDamage + 1;
    }
}
