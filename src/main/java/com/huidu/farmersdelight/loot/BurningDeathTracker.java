package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Entity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers a lethal Fire Aspect hit long enough for the death/loot evaluation.
 *
 * Vanilla treats a Fire Aspect killing blow as "on fire" even when the victim dies before the fire tick
 * applies; Entity#getFireTicks() is still 0 at the point CraftEngine evaluates the loot condition. The
 * vanilla cooked-meat rules therefore cannot be reproduced from the fire-ticks field alone. This tracker marks
 * the lethal hit when it happens (before death) and is_burning accepts either the live field or the mark.
 */
public final class BurningDeathTracker implements Listener {

    // A lethal hit resolves on the same tick; the short TTL only cleans up hits that were prevented from
    // killing (totem, another plugin cancelling the death) or deaths whose loot never ran.
    static final long TTL_NANOS = 2_000_000_000L;

    private static final ConcurrentHashMap<UUID, Long> LETHAL_FIRE_ASPECT_HITS = new ConcurrentHashMap<>();

    private final FarmersDelightPlugin plugin;

    public BurningDeathTracker(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        // Damage events may run on the victim's region. Reading the attacker's equipment from there is a Folia
        // cross-region read; leave the vanilla/other-region path untouched rather than risk a thread violation.
        if (plugin.scheduler() != null
                && (!plugin.scheduler().isOwnedByCurrentRegion(victim)
                || !plugin.scheduler().isOwnedByCurrentRegion(attacker))) {
            return;
        }
        if (event.getFinalDamage() < victim.getHealth()) {
            return;
        }
        if (!hasFireAspect(attacker)) {
            return;
        }
        mark(victim.getUniqueId());
    }

    static boolean hasFireAspect(Player player) {
        // Vanilla only applies Fire Aspect from the main-hand attack; counting the offhand would mark unenchanted
        // kills as cooked.
        return fireAspectLevel(player.getInventory().getItemInMainHand()) > 0;
    }

    private static int fireAspectLevel(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        return item.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
    }

    static void mark(UUID victimId) {
        if (victimId != null) {
            pruneExpired(System.nanoTime());
            LETHAL_FIRE_ASPECT_HITS.put(victimId, System.nanoTime() + TTL_NANOS);
        }
    }

    private static void pruneExpired(long now) {
        if (LETHAL_FIRE_ASPECT_HITS.size() > 64) {
            LETHAL_FIRE_ASPECT_HITS.entrySet().removeIf(entry -> entry.getValue() - now <= 0);
        }
    }

    /** True when the entity was hit by a lethal Fire Aspect blow within the TTL (or is still on fire). */
    public static boolean wasBurning(Entity entity) {
        return entity != null && wasBurning(entity.getUniqueId());
    }

    public static boolean wasBurning(UUID victimId) {
        return wasBurning(victimId, System.nanoTime());
    }

    static boolean wasBurning(UUID victimId, long now) {
        if (victimId == null) {
            return false;
        }
        Long expiresAt = LETHAL_FIRE_ASPECT_HITS.get(victimId);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt - now <= 0) {
            LETHAL_FIRE_ASPECT_HITS.remove(victimId, expiresAt);
            return false;
        }
        return true;
    }

    public static void clear() {
        LETHAL_FIRE_ASPECT_HITS.clear();
    }
}
