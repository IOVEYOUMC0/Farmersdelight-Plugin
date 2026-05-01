package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;

/**
 * Stores and updates the custom comfort and nourishment effects.
 *
 * <p>Bukkit does not support registering custom potion effect types at runtime,
 * so the plugin stores remaining duration in the player's persistent data
 * container and applies the gameplay behavior manually during ticking.
 *
 * <p>Comfort:
 * heals 1 health every 4 seconds while the player has saturation food and is
 * not already under regeneration.
 *
 * <p>Nourishment:
 * reduces exhaustion to slow hunger drain, but pauses while the player is
 * naturally regenerating health from hunger.
 */
public final class EffectManager {

    private static final NamespacedKey COMFORT_KEY = new NamespacedKey("farmersdelight", "comfort_duration");
    private static final NamespacedKey NOURISHMENT_KEY = new NamespacedKey("farmersdelight", "nourishment_duration");

    private static final int COMFORT_HEAL_INTERVAL_TICKS = 80;
    private static final float NOURISHMENT_EXHAUSTION_REDUCTION = 4.0f;
    private static final int NOURISHMENT_MIN_FOOD_FOR_HEALING = 18;
    private static final int EFFECT_FADE_WARNING_TICKS = 200;

    private EffectManager() {
    }

    /**
     * Applies or refreshes comfort.
     *
     * @param player          target player
     * @param durationSeconds duration in seconds
     */
    public static void applyComfort(Player player, int durationSeconds) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        int currentDuration = pdc.getOrDefault(COMFORT_KEY, PersistentDataType.INTEGER, 0);
        int newDuration = Math.max(currentDuration, durationSeconds * 20);
        pdc.set(COMFORT_KEY, PersistentDataType.INTEGER, newDuration);
        if (currentDuration <= 0) {
            player.sendMessage(I18n.formatNamed(
                    "effects.comfort.start",
                    player,
                    durationPlaceholders(newDuration)
            ));
        }
        EffectListener.trackPlayer(player.getUniqueId());
    }

    /**
     * Applies or refreshes nourishment.
     *
     * @param player          target player
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
     * Returns whether the player currently has comfort.
     */
    public static boolean hasComfort(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        int duration = pdc.getOrDefault(COMFORT_KEY, PersistentDataType.INTEGER, 0);
        return duration > 0;
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
     * Returns remaining comfort duration in ticks.
     */
    public static int getComfortDuration(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        return pdc.getOrDefault(COMFORT_KEY, PersistentDataType.INTEGER, 0);
    }

    /**
     * Returns remaining nourishment duration in ticks.
     */
    public static int getNourishmentDuration(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        return pdc.getOrDefault(NOURISHMENT_KEY, PersistentDataType.INTEGER, 0);
    }

    /**
     * Removes comfort from the player.
     */
    public static void removeComfort(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(COMFORT_KEY);
        if (player.isOnline()) {
            player.sendMessage(I18n.get("effects.comfort.end", player));
        }
        checkAndUntrack(player);
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
     * Stops tracking players that no longer have any active custom effect.
     */
    private static void checkAndUntrack(Player player) {
        if (!hasComfort(player) && !hasNourishment(player)) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    /**
     * Updates effect durations and applies their gameplay behavior.
     */
    public static void tick(Player player) {
        if (player == null || !player.isValid() || !player.isOnline() || player.isDead()) {
            return;
        }

        try {
            PersistentDataContainer pdc = player.getPersistentDataContainer();

            Integer comfortDuration = pdc.get(COMFORT_KEY, PersistentDataType.INTEGER);
            Integer nourishmentDuration = pdc.get(NOURISHMENT_KEY, PersistentDataType.INTEGER);

            boolean hasComfort = comfortDuration != null && comfortDuration > 0;
            boolean hasNourishment = nourishmentDuration != null && nourishmentDuration > 0;

            if (!hasComfort && !hasNourishment) {
                EffectListener.untrackPlayer(player.getUniqueId());
                return;
            }

            if (hasComfort && comfortDuration != null) {
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
                        pdc.set(COMFORT_KEY, PersistentDataType.INTEGER, newDuration);
                    } else {
                        player.sendMessage(I18n.get("effects.comfort.end", player));
                        pdc.remove(COMFORT_KEY);
                    }
                }
            }

            if (hasNourishment && nourishmentDuration != null) {
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
            }

            if ((comfortDuration == null || comfortDuration <= 1)
                    && (nourishmentDuration == null || nourishmentDuration <= 1)) {
                EffectListener.untrackPlayer(player.getUniqueId());
            }
        } catch (Exception e) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    /**
     * Comfort heals the player periodically while they are not already
     * regenerating and still have hunger-based healing conditions.
     */
    private static void tickComfort(Player player, int duration) {
        if (player.hasPotionEffect(PotionEffectType.REGENERATION)) {
            return;
        }

        if (player.getSaturation() > 0) {
            return;
        }

        if (duration % COMFORT_HEAL_INTERVAL_TICKS == 0) {
            var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealthAttr != null) {
                double maxHealth = maxHealthAttr.getValue();
                if (player.getHealth() < maxHealth) {
                    player.setHealth(Math.min(player.getHealth() + 1.0, maxHealth));
                }
            }
        }
    }

    /**
     * Nourishment reduces exhaustion unless natural hunger regeneration is
     * currently consuming food for healing.
     */
    private static void tickNourishment(Player player) {
        if (player.isDead()) return;

        var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) return;

        boolean naturalRegen = Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRule.NATURAL_REGENERATION));
        boolean isHurt = player.getHealth() < maxHealthAttr.getValue();
        int foodLevel = player.getFoodLevel();

        boolean isPlayerHealingWithHunger = naturalRegen && isHurt && foodLevel >= NOURISHMENT_MIN_FOOD_FOR_HEALING;

        if (!isPlayerHealingWithHunger) {
            float exhaustion = player.getExhaustion();
            if (exhaustion > 0) {
                player.setExhaustion(Math.max(0, exhaustion - NOURISHMENT_EXHAUSTION_REDUCTION));
            }
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
