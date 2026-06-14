package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.GameRule;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储并更新在线玩家的自定义食物效果状态。
 */
public final class EffectManager {

    private static final int DEFAULT_EFFECT_FADE_WARNING_TICKS = 200;
    private static final int DEFAULT_COMFORT_HEAL_INTERVAL_TICKS = 80;
    // 必须与效果任务（EffectListener）的执行周期保持一致。持续时间以真实 tick 数存储，
    // 因此每次调用之间必须按经过的真实 tick 数进行递减。
    private static final int TICK_INTERVAL = (int) EffectListener.TICK_INTERVAL;
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
            player.sendMessage(I18n.getComponent(
                    "effects.comfort.start",
                    player,
                    durationPlaceholders(newDuration)
            ));
        }
        EffectListener.trackPlayer(player);
    }

    /**
     * 施加或刷新滋养（nourishment）效果。
     *
     * @param player 目标玩家
     * @param durationSeconds 持续时间（单位：秒）
     */
    public static void applyNourishment(Player player, int durationSeconds) {
        UUID playerId = player.getUniqueId();
        int currentDuration = nourishmentDurations.getOrDefault(playerId, 0);
        int newDuration = Math.max(currentDuration, durationSeconds * 20);
        nourishmentDurations.put(playerId, newDuration);
        if (currentDuration <= 0) {
            player.sendMessage(I18n.getComponent(
                    "effects.nourishment.start",
                    player,
                    durationPlaceholders(newDuration)
            ));
        }
        EffectListener.trackPlayer(player);
    }

    /**
     * 返回该玩家当前是否拥有滋养（nourishment）效果。
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
            player.sendMessage(I18n.getComponent("effects.comfort.end", player));
        }
        checkAndUntrack(player);
    }

    /**
     * 从该玩家身上移除滋养（nourishment）效果。
     */
    public static void removeNourishment(Player player) {
        nourishmentDurations.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.sendMessage(I18n.getComponent("effects.nourishment.end", player));
        }
        checkAndUntrack(player);
    }

    /**
     * 停止追踪不再拥有任何激活中自定义效果的玩家。
     */
    private static void checkAndUntrack(Player player) {
        if (!hasComfort(player) && !hasNourishment(player)) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    /**
     * 更新剩余持续时间，并应用游戏内的滋养（nourishment）行为。
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
                    if (shouldSendFadeWarning(comfortDuration, getComfortFadeWarningTicks())) {
                        player.sendMessage(I18n.getComponent(
                                "effects.comfort.fade",
                                player,
                                durationPlaceholders(comfortDuration)
                        ));
                    }
                    int newDuration = comfortDuration - TICK_INTERVAL;
                    if (newDuration > 0) {
                        comfortDurations.put(playerId, newDuration);
                    } else {
                        player.sendMessage(I18n.getComponent("effects.comfort.end", player));
                        comfortDurations.remove(playerId);
                    }
                }
            }

            if (nourishmentDuration > 0) {
                tickNourishment(player);
            }
            if (player.isValid() && nourishmentDuration > 0) {
                if (shouldSendFadeWarning(nourishmentDuration, getNourishmentFadeWarningTicks())) {
                    player.sendMessage(I18n.getComponent(
                            "effects.nourishment.fade",
                            player,
                            durationPlaceholders(nourishmentDuration)
                    ));
                }
                int newDuration = nourishmentDuration - TICK_INTERVAL;
                if (newDuration > 0) {
                    nourishmentDurations.put(playerId, newDuration);
                } else {
                    player.sendMessage(I18n.getComponent("effects.nourishment.end", player));
                    nourishmentDurations.remove(playerId);
                }
            }

            if (comfortDuration <= TICK_INTERVAL && nourishmentDuration <= TICK_INTERVAL) {
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
     * 滋养（nourishment）会降低疲劳值，除非基于饥饿度的自然回血（natural regeneration）正在生效。
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
        int healIntervalTicks = getComfortHealIntervalTicks();
        if (healIntervalTicks <= 0 || durationTicks % healIntervalTicks != 0) {
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

    private static boolean shouldSendFadeWarning(int durationTicks, int warningTicks) {
        return warningTicks > 0
                && durationTicks <= warningTicks
                && durationTicks > warningTicks - TICK_INTERVAL;
    }

    private static int getComfortHealIntervalTicks() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? DEFAULT_COMFORT_HEAL_INTERVAL_TICKS
                : Math.max(0, plugin.getConfigInt(DEFAULT_COMFORT_HEAL_INTERVAL_TICKS,
                "comfort-foods.heal-interval-ticks"));
    }

    private static int getComfortFadeWarningTicks() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? DEFAULT_EFFECT_FADE_WARNING_TICKS
                : Math.max(0, plugin.getConfigInt(DEFAULT_EFFECT_FADE_WARNING_TICKS,
                "comfort-foods.fade-warning-ticks",
                "food-effects.fade-warning-ticks"));
    }

    private static int getNourishmentFadeWarningTicks() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? DEFAULT_EFFECT_FADE_WARNING_TICKS
                : Math.max(0, plugin.getConfigInt(DEFAULT_EFFECT_FADE_WARNING_TICKS,
                "nourishment-foods.fade-warning-ticks",
                "food-effects.fade-warning-ticks"));
    }
}
