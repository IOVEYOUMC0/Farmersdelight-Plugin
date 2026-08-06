package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.buff.BuffBossbar;
import com.huidu.farmersdelight.api.text.FarmersDelightText;
import com.huidu.farmersdelight.util.CompatAttributes;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EffectManager {

    private static final int DEFAULT_COMFORT_HEAL_INTERVAL_TICKS = 80;
    private static final double DEFAULT_COMFORT_HEAL_AMOUNT = 1.0D;

    // Internal types

    private enum BuffKind {
        COMFORT(
            "farmersdelight:comfort_ticks", "farmersdelight:comfort_initial_ticks", "farmersdelight:comfort_level",
            "farmersdelight:comfort", "buff.farmersdelight.comfort.title",
            BossBar.Color.BLUE, BossBar.Overlay.PROGRESS
        ),
        NOURISHMENT(
            "farmersdelight:nourishment_ticks", "farmersdelight:nourishment_initial_ticks", "farmersdelight:nourishment_level",
            "farmersdelight:nourishment", "buff.farmersdelight.nourishment.title",
            BossBar.Color.GREEN, BossBar.Overlay.PROGRESS
        );

        final NamespacedKey pdcDurationKey;
        final NamespacedKey pdcInitialKey;
        final NamespacedKey pdcLevelKey;
        final NamespacedKey bossbarKey;
        final String titleKey;
        volatile BossBar.Color barColor;
        volatile BossBar.Overlay barOverlay;

        BuffKind(String pdcDur, String pdcInit, String pdcLevel, String bbKey, String titleKey,
                 BossBar.Color defColor, BossBar.Overlay defOverlay) {
            this.pdcDurationKey = Objects.requireNonNull(NamespacedKey.fromString(pdcDur));
            this.pdcInitialKey = Objects.requireNonNull(NamespacedKey.fromString(pdcInit));
            this.pdcLevelKey = Objects.requireNonNull(NamespacedKey.fromString(pdcLevel));
            this.bossbarKey = Objects.requireNonNull(NamespacedKey.fromString(bbKey));
            this.titleKey = titleKey;
            this.barColor = defColor;
            this.barOverlay = defOverlay;
        }

        Component title(int durationTicks) {
            return FarmersDelightText.translatable(
                titleKey,
                FarmersDelightText.formatDuration(Math.max(0, durationTicks) / 20));
        }
    }

    private record BuffState(int duration, int initial, int level) {
        boolean isActive() { return duration > 0; }
        static final BuffState EMPTY = new BuffState(0, 0, 1);
    }

    // Public key aliases (for external compatibility)

    public static final NamespacedKey KEY_NOURISHMENT = BuffKind.NOURISHMENT.bossbarKey;
    public static final NamespacedKey KEY_COMFORT = BuffKind.COMFORT.bossbarKey;

    // Unified state storage

    private static final Map<UUID, EnumMap<BuffKind, BuffState>> playerBuffs = new ConcurrentHashMap<>();

    private static EnumMap<BuffKind, BuffState> ensurePlayer(UUID playerId) {
        return playerBuffs.computeIfAbsent(playerId, k -> {
            EnumMap<BuffKind, BuffState> map = new EnumMap<>(BuffKind.class);
            for (BuffKind k2 : BuffKind.values()) map.put(k2, BuffState.EMPTY);
            return map;
        });
    }

    private static BuffState getBuff(UUID playerId, BuffKind kind) {
        EnumMap<BuffKind, BuffState> buffs = playerBuffs.get(playerId);
        if (buffs == null) return BuffState.EMPTY;
        return buffs.getOrDefault(kind, BuffState.EMPTY);
    }

    private static void putBuff(UUID playerId, BuffKind kind, BuffState state) {
        ensurePlayer(playerId).put(kind, state);
    }

    // Bossbar styles

    public static void applyBossbarStyles(ConfigurationSection section) {
        if (section == null) return;
        updateStyle(section, "nourishment", BuffKind.NOURISHMENT);
        updateStyle(section, "comfort", BuffKind.COMFORT);
    }

    private static void updateStyle(ConfigurationSection section, String key, BuffKind kind) {
        ConfigurationSection s = section.getConfigurationSection(key);
        if (s == null) return;
        kind.barColor = BuffBossbar.parseColor(s.getString("color"), kind.barColor);
        kind.barOverlay = BuffBossbar.parseOverlay(s.getString("overlay"), kind.barOverlay);
    }

    // Tick interval: the actual elapsed ticks of EffectListener's resolved tick interval,
    // used so durations (stored in real ticks) decrement by real elapsed ticks and self-correct.
    private static int tickInterval() {
        return (int) EffectListener.tickInterval();
    }

    private EffectManager() {}

    // Apply Buff

    public static void applyComfort(Player player, int durationSeconds) {
        applyBuff(player, BuffKind.COMFORT, durationSeconds, 1);
    }

    public static void applyComfort(Player player, int durationSeconds, int level) {
        applyBuff(player, BuffKind.COMFORT, durationSeconds, level);
    }

    public static void applyNourishment(Player player, int durationSeconds) {
        applyBuff(player, BuffKind.NOURISHMENT, durationSeconds, 1);
    }

    public static void applyNourishment(Player player, int durationSeconds, int level) {
        applyBuff(player, BuffKind.NOURISHMENT, durationSeconds, level);
    }

    public static int comfortLevel(Player player) {
        return buffLevel(player, BuffKind.COMFORT);
    }

    public static int nourishmentLevel(Player player) {
        return buffLevel(player, BuffKind.NOURISHMENT);
    }

    private static int buffLevel(Player player, BuffKind kind) {
        if (player == null) return 0;
        BuffState state = getBuff(player.getUniqueId(), kind);
        return state.isActive() ? Math.max(1, state.level()) : 0;
    }

    private static void applyBuff(Player player, BuffKind kind, int durationSeconds, int level) {
        if (!com.huidu.farmersdelight.api.buff.CustomBuffRegistry.isSystemEnabled()) return;
        UUID playerId = player.getUniqueId();
        int lvl = Math.max(1, level);
        BuffState current = getBuff(playerId, kind);
        int[] result = stack(current.duration(), current.level(), durationSeconds * 20, lvl);
        if (result == null) return; // Weaker dose, ignored when a stronger effect is active
        int newInitial;
        if (current.duration() <= 0 || lvl > current.level()) {
            newInitial = result[0]; // New / stronger dose resets progress bar
        } else {
            newInitial = Math.max(current.initial(), result[0]);
        }
        putBuff(playerId, kind, new BuffState(result[0], newInitial, result[1]));
        EffectListener.trackPlayer(player);
        if (kind == BuffKind.NOURISHMENT) {
            var advMgr = FarmersDelightPlugin.getInstance().getAdvancementManager();
            if (advMgr != null) advMgr.award(player, "eat_nourishing_food");
        }
    }

    private static int[] stack(int currentDuration, int currentLevel, int incomingTicks, int incomingLevel) {
        if (currentDuration <= 0 || incomingLevel > currentLevel) {
            return new int[]{incomingTicks, incomingLevel};
        }
        if (incomingLevel == currentLevel) {
            return new int[]{Math.max(currentDuration, incomingTicks), currentLevel};
        }
        return null;
    }

    // Query / Remove

    public static boolean hasNourishment(Player player) {
        return hasBuff(player, BuffKind.NOURISHMENT);
    }

    public static boolean hasComfort(Player player) {
        return hasBuff(player, BuffKind.COMFORT);
    }

    private static boolean hasBuff(Player player, BuffKind kind) {
        return getBuff(player.getUniqueId(), kind).isActive();
    }

    public static void removeComfort(Player player) {
        removeBuff(player, BuffKind.COMFORT);
    }

    public static void removeNourishment(Player player) {
        removeBuff(player, BuffKind.NOURISHMENT);
    }

    private static void removeBuff(Player player, BuffKind kind) {
        UUID playerId = player.getUniqueId();
        putBuff(playerId, kind, BuffState.EMPTY);
        if (player.isOnline()) {
            BuffBossbar.hide(FarmersDelightPlugin.getInstance(), player, kind.bossbarKey);
        }
        checkAndUntrack(player);
    }

    private static void checkAndUntrack(Player player) {
        if (!hasComfort(player) && !hasNourishment(player)) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    // Tick

    public static void tick(Player player) {
        if (player == null || !player.isValid() || !player.isOnline() || player.isDead()) return;
        if (!com.huidu.farmersdelight.api.buff.CustomBuffRegistry.isSystemEnabled()) {
            EffectListener.untrackPlayer(player.getUniqueId());
            return;
        }
        try {
            UUID playerId = player.getUniqueId();
            EnumMap<BuffKind, BuffState> buffs = playerBuffs.get(playerId);
            if (buffs == null || buffs.values().stream().noneMatch(BuffState::isActive)) {
                EffectListener.untrackPlayer(playerId);
                return;
            }
            int interval = tickInterval();
            for (BuffKind kind : BuffKind.values()) {
                BuffState state = buffs.get(kind);
                if (!state.isActive()) continue;
                if (kind == BuffKind.COMFORT) tickComfort(player, state.duration());
                else if (kind == BuffKind.NOURISHMENT) tickNourishment(player);
                if (!player.isValid()) break;
                int newDuration = state.duration() - interval;
                buffs.put(kind, newDuration > 0
                    ? new BuffState(newDuration, state.initial(), state.level())
                    : BuffState.EMPTY);
            }
            if (buffs.values().stream().noneMatch(BuffState::isActive)) {
                EffectListener.untrackPlayer(playerId);
            }
            pushBossbar(player, playerId);
        } catch (Exception e) {
            EffectListener.untrackPlayer(player.getUniqueId());
        }
    }

    private static void pushBossbar(Player player, UUID playerId) {
        if (!BuffBossbar.isEnabled()) return;
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) return;
        for (BuffKind kind : BuffKind.values()) {
            BuffState state = getBuff(playerId, kind);
            if (state.isActive()) {
                int initial = Math.max(state.duration(), state.initial());
                BuffBossbar.update(plugin, player, kind.bossbarKey,
                    kind.title(state.duration()),
                    Math.min(1F, (float) state.duration() / initial),
                    kind.barColor, kind.barOverlay);
            } else {
                BuffBossbar.hide(plugin, player, kind.bossbarKey);
            }
        }
    }

    // Cleanup

    public static void clearPlayer(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        playerBuffs.remove(playerId);
        if (player.isOnline()) {
            for (BuffKind kind : BuffKind.values()) {
                BuffBossbar.hide(FarmersDelightPlugin.getInstance(), player, kind.bossbarKey);
            }
        }
    }

    public static void clearAll() {
        playerBuffs.clear();
    }

    // PDC persistence

    public static void saveComfortToPdc(Player player) {
        saveBuffToPdc(player, BuffKind.COMFORT);
    }

    public static void saveNourishmentToPdc(Player player) {
        saveBuffToPdc(player, BuffKind.NOURISHMENT);
    }

    private static void saveBuffToPdc(Player player, BuffKind kind) {
        if (player == null) return;
        BuffState state = getBuff(player.getUniqueId(), kind);
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        writeOrRemove(pdc, kind.pdcDurationKey, state.isActive() ? state.duration() : null);
        writeOrRemove(pdc, kind.pdcInitialKey, state.isActive() ? state.initial() : null);
        writeOrRemove(pdc, kind.pdcLevelKey, state.isActive() ? state.level() : null);
    }

    private static void writeOrRemove(PersistentDataContainer pdc, NamespacedKey key, Integer value) {
        if (value != null && value > 0) {
            pdc.set(key, PersistentDataType.INTEGER, value);
        } else {
            pdc.remove(key);
        }
    }

    public static boolean restoreComfortFromPdc(Player player) {
        return restoreBuffFromPdc(player, BuffKind.COMFORT);
    }

    public static boolean restoreNourishmentFromPdc(Player player) {
        return restoreBuffFromPdc(player, BuffKind.NOURISHMENT);
    }

    private static boolean restoreBuffFromPdc(Player player, BuffKind kind) {
        if (player == null) return false;
        UUID playerId = player.getUniqueId();
        if (getBuff(playerId, kind).isActive()) return true;
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Integer duration = pdc.get(kind.pdcDurationKey, PersistentDataType.INTEGER);
        if (duration != null && duration > 0) {
            Integer initial = pdc.get(kind.pdcInitialKey, PersistentDataType.INTEGER);
            int init = (initial != null && initial >= duration) ? initial : duration;
            Integer storedLevel = pdc.get(kind.pdcLevelKey, PersistentDataType.INTEGER);
            int level = storedLevel == null ? 1 : Math.max(1, storedLevel);
            ensurePlayer(playerId).put(kind, new BuffState(duration, init, level));
            return true;
        }
        return false;
    }

    // Remaining seconds

    public static int comfortRemainingSeconds(Player player) {
        return remainingSeconds(player, BuffKind.COMFORT);
    }

    public static int nourishmentRemainingSeconds(Player player) {
        return remainingSeconds(player, BuffKind.NOURISHMENT);
    }

    private static int remainingSeconds(Player player, BuffKind kind) {
        if (player == null) return 0;
        int ticks = getBuff(player.getUniqueId(), kind).duration();
        return ticks <= 0 ? 0 : (int) Math.max(1, (ticks + 19L) / 20L);
    }

    // Tick game logic

    private static void tickNourishment(Player player) {
        if (player.isDead()) return;

        var maxHealthAttr = player.getAttribute(CompatAttributes.MAX_HEALTH);
        if (maxHealthAttr == null) return;

        boolean naturalRegen = Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRule.NATURAL_REGENERATION));
        boolean isHurt = player.getHealth() < maxHealthAttr.getValue();
        boolean isPlayerHealingWithSaturation = naturalRegen && isHurt && player.getSaturation() > 0;

        if (!isPlayerHealingWithSaturation) {
            player.setExhaustion(0);
        }
    }

    private static void tickComfort(Player player, int durationTicks) {
        if (player.isDead() || player.hasPotionEffect(PotionEffectType.REGENERATION)) return;
        if (player.getSaturation() > 0.0F) return;
        int healIntervalTicks = getComfortHealIntervalTicks();
        if (healIntervalTicks <= 0 || durationTicks % healIntervalTicks != 0) return;

        var maxHealthAttr = player.getAttribute(CompatAttributes.MAX_HEALTH);
        if (maxHealthAttr == null) return;
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
