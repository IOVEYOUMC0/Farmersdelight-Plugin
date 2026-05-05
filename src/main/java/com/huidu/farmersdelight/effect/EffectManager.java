package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

/**
 * Stores and updates custom nourishment state for players.
 * Bukkit cannot register a runtime potion effect type here, so the remaining
 * duration is stored in player PDC and applied manually during ticking.
 */
public final class EffectManager {

    private static final NamespacedKey NOURISHMENT_KEY = new NamespacedKey("farmersdelight", "nourishment_duration");

    private static final int EFFECT_FADE_WARNING_TICKS = 200;

    private EffectManager() {
    }

    /**
     * Applies or refreshes nourishment.
     *
     * @param player target player
     * @param durationSeconds duration in seconds
     */
    public static void applyNourishment(Player player, int durationSeconds) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        int currentDuration = pdc.getOrDefault(NOURISHMENT_KEY, PersistentDataType.INTEGER, 0);
        int newDuration = Math.max(currentDuration, durationSeconds * 20);
        pdc.set(NOURISHMENT_KEY, PersistentDataType.INTEGER, newDuration);
        if (currentDuration <= 0) {
            player.sendMessage(I18n.formatNamed(
                    "effects.nourishment.start",
                    player,
                    durationPlaceholders(newDuration)
            ));
        }
        EffectListener.trackPlayer(player.getUniqueId());
    }

    /**
     * Returns whether the player currently has nourishment.
     */
    public static boolean hasNourishment(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        int duration = pdc.getOrDefault(NOURISHMENT_KEY, PersistentDataType.INTEGER, 0);
        return duration > 0;
    }

    /**
     * Removes nourishment from the player.
     */
    public static void removeNourishment(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(NOURISHMENT_KEY);
        if (player.isOnline()) {
            player.sendMessage(I18n.get("effects.nourishment.end", player));
        }
        checkAndUntrack(player);
    }

    /**
     * Stops tracking players that no longer have active custom effects.
     */
    private static void checkAndUntrack(Player player) {
        if (!hasNourishment(player)) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    /**
     * Updates remaining duration and applies the in-game nourishment behavior.
     */
    public static void tick(Player player) {
        if (player == null || !player.isValid() || !player.isOnline() || player.isDead()) {
            return;
        }

        try {
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            int nourishmentDuration = pdc.getOrDefault(NOURISHMENT_KEY, PersistentDataType.INTEGER, 0);

            if (nourishmentDuration <= 0) {
                EffectListener.untrackPlayer(player.getUniqueId());
                return;
            }

            tickNourishment(player);
            if (player.isValid()) {
                if (nourishmentDuration == EFFECT_FADE_WARNING_TICKS) {
                    player.sendMessage(I18n.formatNamed(
                            "effects.nourishment.fade",
                            player,
                            durationPlaceholders(nourishmentDuration)
                    ));
                }
                int newDuration = nourishmentDuration - 1;
                if (newDuration > 0) {
                    pdc.set(NOURISHMENT_KEY, PersistentDataType.INTEGER, newDuration);
                } else {
                    player.sendMessage(I18n.get("effects.nourishment.end", player));
                    pdc.remove(NOURISHMENT_KEY);
                }
            }

            if (nourishmentDuration <= 1) {
                EffectListener.untrackPlayer(player.getUniqueId());
            }
        } catch (Exception e) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    /**
     * Nourishment reduces exhaustion unless hunger-based natural regeneration is active.
     */
    private static void tickNourishment(Player player) {
        if (player.isDead()) {
            return;
        }

        var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) {
            return;
        }

        boolean naturalRegen = Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRule.NATURAL_REGENERATION));
        boolean isHurt = player.getHealth() < maxHealthAttr.getValue();
        boolean isPlayerHealingWithSaturation = naturalRegen && isHurt && player.getSaturation() > 0;

        if (!isPlayerHealingWithSaturation) {
            player.setExhaustion(0);
        }
    }

    private static Map<String, String> durationPlaceholders(int durationTicks) {
        int seconds = Math.max(0, durationTicks / 20);
        int minutes = seconds / 60;
        int remainSeconds = seconds % 60;
        return Map.of(
                "seconds", String.valueOf(seconds),
                "minutes", String.valueOf(minutes),
                "time", minutes > 0
                        ? minutes + "m " + remainSeconds + "s"
                        : seconds + "s"
        );
    }
}
