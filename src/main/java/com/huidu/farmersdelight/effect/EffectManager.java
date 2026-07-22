package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.buff.BuffBossbar;
import com.huidu.farmersdelight.util.CompatAttributes;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores and updates custom food effect state for online players.
 */
public final class EffectManager {

    private static final int DEFAULT_COMFORT_HEAL_INTERVAL_TICKS = 80;
    private static final double DEFAULT_COMFORT_HEAL_AMOUNT = 1.0D;

    // Bossbar styles for the two FD buffs. Volatile + setters so /fd reload can mutate them without
    // touching the per-tick push path. Defaults match the original NamedTextColor mapping
    // (Nourishment GREEN, Comfort BLUE).
    private static volatile BossBar.Color nourishmentBarColor = BossBar.Color.GREEN;
    private static volatile BossBar.Overlay nourishmentBarOverlay = BossBar.Overlay.PROGRESS;
    private static volatile BossBar.Color comfortBarColor = BossBar.Color.BLUE;
    private static volatile BossBar.Overlay comfortBarOverlay = BossBar.Overlay.PROGRESS;

    /** Apply the per-buff bossbar visual from FD's buff.display.styles config section. Called on
     *  enable AND on /fd reload so colour edits land immediately. section may be null (defaults retained). */
    public static void applyBossbarStyles(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return;
        org.bukkit.configuration.ConfigurationSection n = section.getConfigurationSection("nourishment");
        if (n != null) {
            nourishmentBarColor = com.huidu.farmersdelight.api.buff.BuffBossbar.parseColor(n.getString("color"), BossBar.Color.GREEN);
            nourishmentBarOverlay = com.huidu.farmersdelight.api.buff.BuffBossbar.parseOverlay(n.getString("overlay"), BossBar.Overlay.PROGRESS);
        }
        org.bukkit.configuration.ConfigurationSection c = section.getConfigurationSection("comfort");
        if (c != null) {
            comfortBarColor = com.huidu.farmersdelight.api.buff.BuffBossbar.parseColor(c.getString("color"), BossBar.Color.BLUE);
            comfortBarOverlay = com.huidu.farmersdelight.api.buff.BuffBossbar.parseOverlay(c.getString("overlay"), BossBar.Overlay.PROGRESS);
        }
    }
    // Real ticks elapsed between effect-task passes. Mirrors EffectListener's resolved tick interval
    // so durations (stored in real ticks) decrement by the actual elapsed ticks and self-correct.
    private static int tickInterval() {
        return (int) EffectListener.tickInterval();
    }

    private static final Map<UUID, Integer> comfortDurations = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> nourishmentDurations = new ConcurrentHashMap<>();
    // Parallel "initial duration" maps — needed so the buff bossbar can render progress as
    // remaining/initial rather than against a hard-coded max. Updated to the larger value when an
    // overlapping dose is applied; removed alongside the duration map.
    private static final Map<UUID, Integer> comfortInitial = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> nourishmentInitial = new ConcurrentHashMap<>();
    // Effect level (1-based; 1 = baseline / amp 0). Drives the vanilla-style stacking rule and the
    // "II"/"III" suffix on the display. Absent entry == level 1. Removed alongside the duration map.
    private static final Map<UUID, Integer> comfortLevels = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> nourishmentLevels = new ConcurrentHashMap<>();

    public static final NamespacedKey KEY_NOURISHMENT = Objects.requireNonNull(NamespacedKey.fromString("farmersdelight:nourishment"));
    public static final NamespacedKey KEY_COMFORT = Objects.requireNonNull(NamespacedKey.fromString("farmersdelight:comfort"));

    // PDC keys mirroring each buff's remaining + initial ticks onto the player entity so the effect
    // survives relog / restart, and rides HuskSync-style whole-PDC sync across a proxy network.
    private static final NamespacedKey PDC_COMFORT = Objects.requireNonNull(NamespacedKey.fromString("farmersdelight:comfort_ticks"));
    private static final NamespacedKey PDC_COMFORT_INITIAL = Objects.requireNonNull(NamespacedKey.fromString("farmersdelight:comfort_initial_ticks"));
    private static final NamespacedKey PDC_NOURISHMENT = Objects.requireNonNull(NamespacedKey.fromString("farmersdelight:nourishment_ticks"));
    private static final NamespacedKey PDC_NOURISHMENT_INITIAL = Objects.requireNonNull(NamespacedKey.fromString("farmersdelight:nourishment_initial_ticks"));

    // Title now interpolates the remaining time via the "<name> [%s]" pattern, so each title is built
    // inline per push with the player's current seconds-left. Translatable + server fallback applied
    // in FarmersDelightText.translatable so packs without the lang entry still show readable text.
    private static Component titleNourishment(int durationTicks) {
        return com.huidu.farmersdelight.api.text.FarmersDelightText.translatable(
                "buff.farmersdelight.nourishment.title",
                com.huidu.farmersdelight.api.text.FarmersDelightText.formatDuration(Math.max(0, durationTicks) / 20));
    }

    private static Component titleComfort(int durationTicks) {
        return com.huidu.farmersdelight.api.text.FarmersDelightText.translatable(
                "buff.farmersdelight.comfort.title",
                com.huidu.farmersdelight.api.text.FarmersDelightText.formatDuration(Math.max(0, durationTicks) / 20));
    }

    private EffectManager() {
    }

    public static void applyComfort(Player player, int durationSeconds) {
        applyComfort(player, durationSeconds, 1);
    }

    /**
     * Applies the Comfort effect at level (1-based) for durationSeconds, using the vanilla
     * potion stacking rule: a higher level replaces the active effect and refreshes its duration; an equal
     * level extends to whichever remaining time is longer; a lower level is ignored while a stronger Comfort
     * is active. (These effects aren't chainable, so a masked weaker dose isn't stored to resume later.)
     */
    public static void applyComfort(Player player, int durationSeconds, int level) {
        if (!com.huidu.farmersdelight.api.buff.CustomBuffRegistry.isSystemEnabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        int lvl = Math.max(1, level);
        int currentDuration = comfortDurations.getOrDefault(playerId, 0);
        int currentLevel = currentDuration > 0 ? comfortLevels.getOrDefault(playerId, 1) : 0;
        int[] result = stack(currentDuration, currentLevel, durationSeconds * 20, lvl);
        if (result == null) {
            return; // weaker dose while a stronger effect is active
        }
        comfortDurations.put(playerId, result[0]);
        comfortLevels.put(playerId, result[1]);
        if (currentDuration <= 0 || lvl > currentLevel) {
            comfortInitial.put(playerId, result[0]); // fresh / stronger dose resets the progress bar
        } else {
            comfortInitial.merge(playerId, result[0], Math::max);
        }
        EffectListener.trackPlayer(player);
    }

    /** Vanilla-style stack of an incoming (durationTicks, level) dose onto the active one. Returns the new
     *  [durationTicks, level], or null when the incoming dose is weaker than the active one
     *  (and therefore ignored). */
    private static int[] stack(int currentDuration, int currentLevel, int incomingTicks, int incomingLevel) {
        if (currentDuration <= 0 || incomingLevel > currentLevel) {
            return new int[]{incomingTicks, incomingLevel};
        }
        if (incomingLevel == currentLevel) {
            return new int[]{Math.max(currentDuration, incomingTicks), currentLevel};
        }
        return null;
    }

    /**
     * Applies or refreshes the nourishment effect.
     *
     * @param player the target player
     * @param durationSeconds duration in seconds
     */
    public static void applyNourishment(Player player, int durationSeconds) {
        applyNourishment(player, durationSeconds, 1);
    }

    /**
     * Applies or refreshes the nourishment effect at level (1-based), using the same vanilla stacking
     * rule as applyComfort(Player, int, int).
     */
    public static void applyNourishment(Player player, int durationSeconds, int level) {
        if (!com.huidu.farmersdelight.api.buff.CustomBuffRegistry.isSystemEnabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        int lvl = Math.max(1, level);
        int currentDuration = nourishmentDurations.getOrDefault(playerId, 0);
        int currentLevel = currentDuration > 0 ? nourishmentLevels.getOrDefault(playerId, 1) : 0;
        int[] result = stack(currentDuration, currentLevel, durationSeconds * 20, lvl);
        if (result == null) {
            return;
        }
        nourishmentDurations.put(playerId, result[0]);
        nourishmentLevels.put(playerId, result[1]);
        if (currentDuration <= 0 || lvl > currentLevel) {
            nourishmentInitial.put(playerId, result[0]);
        } else {
            nourishmentInitial.merge(playerId, result[0], Math::max);
        }
        EffectListener.trackPlayer(player);
        // Award whenever nourishment is applied or refreshed (AdvancementManager guards against
        // re-awarding already-granted advancements).
        var advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "eat_nourishing_food");
        }
    }

    /**
     * Returns whether the player currently has the nourishment effect.
     */
    public static boolean hasNourishment(Player player) {
        return getNourishmentDuration(player) > 0;
    }

    public static boolean hasComfort(Player player) {
        return getComfortDuration(player) > 0;
    }

    public static void removeComfort(Player player) {
        UUID playerId = player.getUniqueId();
        comfortDurations.remove(playerId);
        comfortInitial.remove(playerId);
        if (player.isOnline()) {
            BuffBossbar.hide(FarmersDelightPlugin.getInstance(), player, KEY_COMFORT);
        }
        checkAndUntrack(player);
    }

    /**
     * Removes the nourishment effect from the player.
     */
    public static void removeNourishment(Player player) {
        UUID playerId = player.getUniqueId();
        nourishmentDurations.remove(playerId);
        nourishmentInitial.remove(playerId);
        if (player.isOnline()) {
            BuffBossbar.hide(FarmersDelightPlugin.getInstance(), player, KEY_NOURISHMENT);
        }
        checkAndUntrack(player);
    }

    /**
     * Stops tracking a player that no longer has any active custom effect.
     */
    private static void checkAndUntrack(Player player) {
        if (!hasComfort(player) && !hasNourishment(player)) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    /**
     * Updates remaining durations and applies in-game nourishment behavior.
     */
    public static void tick(Player player) {
        if (player == null || !player.isValid() || !player.isOnline() || player.isDead()) {
            return;
        }
        // Buff master switch. The reload path also cancels the ticker, so this only covers a pass that was
        // already in flight when the switch went off.
        if (!com.huidu.farmersdelight.api.buff.CustomBuffRegistry.isSystemEnabled()) {
            EffectListener.untrackPlayer(player.getUniqueId());
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
                    int newDuration = comfortDuration - tickInterval();
                    if (newDuration > 0) {
                        comfortDurations.put(playerId, newDuration);
                    } else {
                        comfortDurations.remove(playerId);
                        comfortInitial.remove(playerId);
                    }
                }
            }

            if (nourishmentDuration > 0) {
                tickNourishment(player);
            }
            if (player.isValid() && nourishmentDuration > 0) {
                int newDuration = nourishmentDuration - tickInterval();
                if (newDuration > 0) {
                    nourishmentDurations.put(playerId, newDuration);
                } else {
                    nourishmentDurations.remove(playerId);
                    nourishmentInitial.remove(playerId);
                }
            }

            int interval = tickInterval();
            if (comfortDuration <= interval && nourishmentDuration <= interval) {
                EffectListener.untrackPlayer(playerId);
            }

            // Bossbar feed — push current state every tick this method runs. Tick rate is the effect
            // task's configured interval (default 4 ticks = 5 Hz), plenty for a smooth progress bar.
            pushBossbar(player, playerId);
        } catch (Exception e) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    private static void pushBossbar(Player player, UUID playerId) {
        if (!BuffBossbar.isEnabled()) return;
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) return;

        int nourish = nourishmentDurations.getOrDefault(playerId, 0);
        if (nourish > 0) {
            int initial = Math.max(nourish, nourishmentInitial.getOrDefault(playerId, nourish));
            BuffBossbar.update(plugin, player, KEY_NOURISHMENT,
                    titleNourishment(nourish),
                    Math.min(1F, (float) nourish / initial),
                    nourishmentBarColor, nourishmentBarOverlay);
        } else {
            BuffBossbar.hide(plugin, player, KEY_NOURISHMENT);
        }

        int comfort = comfortDurations.getOrDefault(playerId, 0);
        if (comfort > 0) {
            int initial = Math.max(comfort, comfortInitial.getOrDefault(playerId, comfort));
            BuffBossbar.update(plugin, player, KEY_COMFORT,
                    titleComfort(comfort),
                    Math.min(1F, (float) comfort / initial),
                    comfortBarColor, comfortBarOverlay);
        } else {
            BuffBossbar.hide(plugin, player, KEY_COMFORT);
        }
    }

    public static void clearPlayer(Player player) {
        if (player != null) {
            UUID playerId = player.getUniqueId();
            comfortDurations.remove(playerId);
            nourishmentDurations.remove(playerId);
            comfortInitial.remove(playerId);
            nourishmentInitial.remove(playerId);
            comfortLevels.remove(playerId);
            nourishmentLevels.remove(playerId);
            // Hide both bars — caller is typically PlayerQuit which would also clear via BuffBossbar's
            // own quit listener, but a manual remove() call (e.g. /effect clear) shouldn't leave a bar.
            if (player.isOnline()) {
                BuffBossbar.hide(FarmersDelightPlugin.getInstance(), player, KEY_NOURISHMENT);
                BuffBossbar.hide(FarmersDelightPlugin.getInstance(), player, KEY_COMFORT);
            }
        }
    }

    public static void clearAll() {
        comfortDurations.clear();
        nourishmentDurations.clear();
        comfortInitial.clear();
        nourishmentInitial.clear();
        comfortLevels.clear();
        nourishmentLevels.clear();
    }

    // Each buff persists its own slice via the CustomBuff.saveState / restoreState hooks (wired in
    // EffectListener), so FD's Comfort / Nourishment go through the exact same central lifecycle as any
    // addon buff. On a network that syncs the whole PDC (HuskSync etc.) these also carry across servers.

    /** Mirror Comfort's remaining + initial ticks into the player's PDC (inactive → keys removed). */
    public static void saveComfortToPdc(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        writeOrRemove(pdc, PDC_COMFORT, comfortDurations.get(playerId));
        writeOrRemove(pdc, PDC_COMFORT_INITIAL, comfortInitial.get(playerId));
    }

    /** Mirror Nourishment's remaining + initial ticks into the player's PDC (inactive → keys removed). */
    public static void saveNourishmentToPdc(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        writeOrRemove(pdc, PDC_NOURISHMENT, nourishmentDurations.get(playerId));
        writeOrRemove(pdc, PDC_NOURISHMENT_INITIAL, nourishmentInitial.get(playerId));
    }

    private static void writeOrRemove(PersistentDataContainer pdc, NamespacedKey key, Integer value) {
        if (value != null && value > 0) {
            pdc.set(key, PersistentDataType.INTEGER, value);
        } else {
            pdc.remove(key);
        }
    }

    /** Restore Comfort's remaining ticks from the player's PDC. Gap-filling: leaves an already-active
     *  Comfort untouched, so the immediate-join call and the delayed retry are both safe and never
     *  overwrite a dose the player gained since joining. The PDC copy is left in place (overwritten on
     *  the next quit) so a crash mid-session doesn't drop the buff. Returns true if Comfort is
     *  active afterwards (so the caller can start tracking). */
    public static boolean restoreComfortFromPdc(Player player) {
        if (player == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (comfortDurations.getOrDefault(playerId, 0) > 0) {
            return true;
        }
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Integer comfort = pdc.get(PDC_COMFORT, PersistentDataType.INTEGER);
        if (comfort != null && comfort > 0) {
            comfortDurations.put(playerId, comfort);
            Integer initial = pdc.get(PDC_COMFORT_INITIAL, PersistentDataType.INTEGER);
            comfortInitial.put(playerId, initial != null && initial >= comfort ? initial : comfort);
            return true;
        }
        return false;
    }

    /** Restore Nourishment's remaining ticks from the player's PDC. Gap-filling — see
     *  restoreComfortFromPdc. Returns true if Nourishment is active afterwards. */
    public static boolean restoreNourishmentFromPdc(Player player) {
        if (player == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (nourishmentDurations.getOrDefault(playerId, 0) > 0) {
            return true;
        }
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Integer nourishment = pdc.get(PDC_NOURISHMENT, PersistentDataType.INTEGER);
        if (nourishment != null && nourishment > 0) {
            nourishmentDurations.put(playerId, nourishment);
            Integer initial = pdc.get(PDC_NOURISHMENT_INITIAL, PersistentDataType.INTEGER);
            nourishmentInitial.put(playerId, initial != null && initial >= nourishment ? initial : nourishment);
            return true;
        }
        return false;
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

    /** Seconds left on player's Comfort buff (rounded up), or 0 when inactive.
     *  Backs the %farmersdelight_buff_*% placeholder family. */
    public static int comfortRemainingSeconds(Player player) {
        int ticks = getComfortDuration(player);
        return ticks <= 0 ? 0 : (int) Math.max(1, (ticks + 19L) / 20L);
    }

    /** Seconds left on player's Nourishment buff (rounded up), or 0 when inactive. */
    public static int nourishmentRemainingSeconds(Player player) {
        int ticks = getNourishmentDuration(player);
        return ticks <= 0 ? 0 : (int) Math.max(1, (ticks + 19L) / 20L);
    }

    /**
     * Nourishment reduces exhaustion unless hunger-based natural regeneration is active.
     */
    private static void tickNourishment(Player player) {
        if (player.isDead()) {
            return;
        }

        var maxHealthAttr = player.getAttribute(CompatAttributes.MAX_HEALTH);
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

        var maxHealthAttr = player.getAttribute(CompatAttributes.MAX_HEALTH);
        if (maxHealthAttr == null) {
            return;
        }
        double maxHealth = maxHealthAttr.getValue();
        if (player.getHealth() < maxHealth) {
            player.setHealth(Math.min(player.getHealth() + getComfortHealAmount(), maxHealth));
        }
    }

    private static int getComfortHealIntervalTicks() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? DEFAULT_COMFORT_HEAL_INTERVAL_TICKS
                : Math.max(0, plugin.getConfigInt(DEFAULT_COMFORT_HEAL_INTERVAL_TICKS,
                "buff.comfort.heal-interval-ticks",
                "comfort-foods.heal-interval-ticks"));
    }

    private static double getComfortHealAmount() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? DEFAULT_COMFORT_HEAL_AMOUNT
                : Math.max(0, plugin.getConfigDouble(DEFAULT_COMFORT_HEAL_AMOUNT,
                "buff.comfort.heal-amount",
                "comfort-foods.heal-amount"));
    }

}
