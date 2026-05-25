package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.GameRule;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores and updates custom food effect state for online players.
 */
public final class EffectManager {

    private static final int EFFECT_FADE_WARNING_TICKS = 200;
    private static final int COMFORT_HEAL_INTERVAL_TICKS = 80;
    private static final Map<UUID, Integer> comfortDurations = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> nourishmentDurations = new ConcurrentHashMap<>();

    private EffectManager() {
    }

    public static void applyComfort(Player player, int durationSeconds) {
        UUID playerId = player.getUniqueId();
        int currentDuration = comfortDurations.getOrDefault(playerId, 0);
        int newDuration = Math.max(currentDuration, durationSeconds * 20);
        comfortDurations.put(playerId, newDuration);
        if (currentDuration <= 0) {
            player.sendMessage(I18n.formatNamed(
                    "effects.comfort.start",
                    player,
                    durationPlaceholders(newDuration)
            ));
        }
        EffectListener.trackPlayer(player);
    }

    /**
     * Applies or refreshes nourishment.
     *
     * @param player target player
     * @param durationSeconds duration in seconds
     */
    public static void applyNourishment(Player player, int durationSeconds) {
        UUID playerId = player.getUniqueId();
        int currentDuration = nourishmentDurations.getOrDefault(playerId, 0);
        int newDuration = Math.max(currentDuration, durationSeconds * 20);
        nourishmentDurations.put(playerId, newDuration);
        if (currentDuration <= 0) {
            player.sendMessage(I18n.formatNamed(
                    "effects.nourishment.start",
                    player,
                    durationPlaceholders(newDuration)
            ));
        }
        EffectListener.trackPlayer(player);
    }

    /**
     * Returns whether the player currently has nourishment.
     */
    public static boolean hasNourishment(Player player) {
        return getNourishmentDuration(player) > 0;
    }

    public static boolean hasComfort(Player player) {
        return getComfortDuration(player) > 0;
    }

    public static void removeComfort(Player player) {
        comfortDurations.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.sendMessage(I18n.get("effects.comfort.end", player));
        }
        checkAndUntrack(player);
    }

    /**
     * Removes nourishment from the player.
     */
    public static void removeNourishment(Player player) {
        nourishmentDurations.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.sendMessage(I18n.get("effects.nourishment.end", player));
        }
        checkAndUntrack(player);
    }

    /**
     * Stops tracking players that no longer have active custom effects.
     */
    private static void checkAndUntrack(Player player) {
        if (!hasComfort(player) && !hasNourishment(player)) {
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
            UUID playerId = player.getUniqueId();
            int comfortDuration = comfortDurations.getOrDefault(playerId, 0);
            int nourishmentDuration = nourishmentDurations.getOrDefault(playerId, 0);

            if (comfortDuration <= 0 && nourishmentDuration <= 0) {
                EffectListener.untrackPlayer(playerId);
                return;
            }

            if (comfortDuration > 0) {
                tickComfort(player, comfortDuration);
                if (player.isValid()) {
                    if (comfortDuration == EFFECT_FADE_WARNING_TICKS) {
                        player.sendMessage(I18n.formatNamed(
                                "effects.comfort.fade",
                                player,
                                durationPlaceholders(comfortDuration)
                        ));
                    }
                    int newDuration = comfortDuration - 1;
                    if (newDuration > 0) {
                        comfortDurations.put(playerId, newDuration);
                    } else {
                        player.sendMessage(I18n.get("effects.comfort.end", player));
                        comfortDurations.remove(playerId);
                    }
                }
            }

            if (nourishmentDuration > 0) {
                tickNourishment(player);
            }
            if (player.isValid() && nourishmentDuration > 0) {
                if (nourishmentDuration == EFFECT_FADE_WARNING_TICKS) {
                    player.sendMessage(I18n.formatNamed(
                            "effects.nourishment.fade",
                            player,
                            durationPlaceholders(nourishmentDuration)
                    ));
                }
                int newDuration = nourishmentDuration - 1;
                if (newDuration > 0) {
                    nourishmentDurations.put(playerId, newDuration);
                } else {
                    player.sendMessage(I18n.get("effects.nourishment.end", player));
                    nourishmentDurations.remove(playerId);
                }
            }

            if (comfortDuration <= 1 && nourishmentDuration <= 1) {
                EffectListener.untrackPlayer(playerId);
            }
        } catch (Exception e) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    public static void clearPlayer(Player player) {
        if (player != null) {
            comfortDurations.remove(player.getUniqueId());
            nourishmentDurations.remove(player.getUniqueId());
        }
    }

    public static void clearAll() {
        comfortDurations.clear();
        nourishmentDurations.clear();
    }

    private static int getComfortDuration(Player player) {
        if (player == null) {
            return 0;
        }
        return comfortDurations.getOrDefault(player.getUniqueId(), 0);
    }

    private static int getNourishmentDuration(Player player) {
        if (player == null) {
            return 0;
        }
        return nourishmentDurations.getOrDefault(player.getUniqueId(), 0);
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

    private static void tickComfort(Player player, int durationTicks) {
        if (player.isDead() || player.hasPotionEffect(PotionEffectType.REGENERATION)) {
            return;
        }
        if (player.getSaturation() > 0.0F) {
            return;
        }
        if (durationTicks % COMFORT_HEAL_INTERVAL_TICKS != 0) {
            return;
        }

        var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) {
            return;
        }
        double maxHealth = maxHealthAttr.getValue();
        if (player.getHealth() < maxHealth) {
            player.setHealth(Math.min(player.getHealth() + 1.0D, maxHealth));
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
