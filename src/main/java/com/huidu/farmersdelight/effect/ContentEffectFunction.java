package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.advancement.FarmersDelightAdvancements;
import com.huidu.farmersdelight.api.buff.CustomBuff;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.function.AbstractConditionalFunction;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.plugin.context.number.ConstantNumberProvider;
import net.momirealms.craftengine.core.plugin.context.number.NumberProvider;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Content-pack functions that change the player's active effects, so a pack can declare what a food does
 * instead of the plugin hard-coding each dish. Every mutation runs on the player's own region: the provider
 * values are evaluated inside that callback, not on the caller's thread.
 *
 * Actions: remove one effect, remove one random effect (optionally harmful only), raise an effect's amplifier
 * up to a cap, or grant an advancement.
 */
public final class ContentEffectFunction extends AbstractConditionalFunction<Context> {

    public enum Action {
        REMOVE,
        REMOVE_RANDOM,
        UPGRADE,
        ADVANCEMENT
    }

    private final Action action;
    private final String effect;
    private final NumberProvider increment;
    private final NumberProvider maximum;
    private final NumberProvider duration;
    private final boolean harmfulOnly;
    private final FarmersDelightPlugin plugin;

    private ContentEffectFunction(FarmersDelightPlugin plugin, ConfigSection config,
                                  List<Condition<Context>> predicates, Action action, boolean harmfulOnlyDefault) {
        super(predicates);
        this.plugin = plugin;
        this.action = action;
        this.effect = config.getString(action == Action.ADVANCEMENT ? "advancement" : "effect", "");
        this.increment = config.getNumber("increment", ConfigConstants.CONSTANT_ONE);
        this.maximum = config.getNumber("max_amplifier", ConstantNumberProvider.constant(4));
        this.duration = config.getNumber("duration", ConfigConstants.CONSTANT_ZERO);
        this.harmfulOnly = config.getBoolean("harmful_only", harmfulOnlyDefault);
    }

    public static Map<String, FunctionFactory<Context, ContentEffectFunction>> factories() {
        return factories(null);
    }

    public static Map<String, FunctionFactory<Context, ContentEffectFunction>> factories(FarmersDelightPlugin plugin) {
        Map<String, FunctionFactory<Context, ContentEffectFunction>> result = new LinkedHashMap<>();
        result.put("remove_effect", factory(plugin, Action.REMOVE));
        result.put("remove_random_effect", factory(plugin, Action.REMOVE_RANDOM));
        result.put("upgrade_effect", factory(plugin, Action.UPGRADE));
        result.put("grant_advancement", factory(plugin, Action.ADVANCEMENT));
        // Legacy alias: an items.yml that predates the generic action still names the dish-specific function, and
        // an unknown type makes CraftEngine reject the whole item definition. The alias keeps such a server
        // loading until the bundled resource migration rewrites the file.
        result.put("hot_cocoa", factory(plugin, Action.REMOVE_RANDOM, true));
        return Map.copyOf(result);
    }

    public static FunctionFactory<Context, ContentEffectFunction> factory(Action action) {
        return factory(null, action);
    }

    public static FunctionFactory<Context, ContentEffectFunction> factory(FarmersDelightPlugin plugin, Action action) {
        return factory(plugin, action, false);
    }

    private static FunctionFactory<Context, ContentEffectFunction> factory(FarmersDelightPlugin plugin, Action action,
                                                                          boolean harmfulOnlyDefault) {
        return new AbstractFactory<>(CommonConditions::fromConfig) {
            @Override
            public ContentEffectFunction create(ConfigSection config) {
                return new ContentEffectFunction(plugin, config, getPredicates(config), action, harmfulOnlyDefault);
            }
        };
    }

    @Override
    protected void runInternal(Context context) {
        Object handle = context.getOptionalParameter(DirectContextParameters.PLAYER)
                .map(cePlayer -> cePlayer.platformPlayer())
                .orElse(null);
        if (!(handle instanceof Player player)) {
            return;
        }
        runOnPlayerRegion(plugin, player, () -> {
            int inc = increment.getInt(context);
            int max = maximum.getInt(context);
            int ticks = duration.getInt(context);
            switch (action) {
                case ADVANCEMENT -> {
                    String id = effect;
                    int separator = id.indexOf(':');
                    if (separator >= 0) {
                        id = id.substring(separator + 1);
                    }
                    FarmersDelightAdvancements.award(player, id);
                }
                case REMOVE, REMOVE_RANDOM, UPGRADE -> mutateEffects(player, action, effect, inc, max, ticks, harmfulOnly);
            }
        });
    }

    /**
     * Runs the mutation where the player lives, or not at all. A Folia region cannot touch another region's
     * player state, so a call from a foreign thread is deferred through the entity scheduler and re-checked
     * there; the player may have gone offline or the plugin may have disabled in between.
     */
    private static void runOnPlayerRegion(FarmersDelightPlugin plugin, Player player, Runnable action) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        SchedulerAdapter scheduler = plugin.scheduler();
        if (scheduler == null) {
            action.run();
            return;
        }
        if (scheduler.isOwnedByCurrentRegion(player)) {
            if (player.isOnline() && !player.isDead()) {
                action.run();
            }
            return;
        }
        scheduler.runForEntity(player, () -> {
            if (plugin.isEnabled() && player.isOnline() && !player.isDead()
                    && scheduler.isOwnedByCurrentRegion(player)) {
                action.run();
            }
        });
    }

    /** Applies one effect mutation; the caller already runs on the player's owner. */
    static void mutateEffects(Player player, Action action, String effect, int increment, int maximum,
                              int ticks, boolean harmfulOnly) {
        if (action == Action.REMOVE_RANDOM) {
            removeOneRandom(player, harmfulOnly);
            return;
        }
        String id = canonicalEffectId(effect);
        CustomBuff custom = CustomBuffRegistry.byId(id);
        boolean builtin = "farmersdelight:comfort".equals(id) || "farmersdelight:nourishment".equals(id);
        if (builtin || custom != null) {
            mutateBuff(player, action, id, builtin, custom, increment, maximum, ticks);
            return;
        }
        NamespacedKey parsed = NamespacedKey.fromString(id);
        PotionEffectType type = parsed == null ? null : Registry.EFFECT.get(parsed);
        if (type == null) {
            return;
        }
        if (action == Action.REMOVE) {
            player.removePotionEffect(type);
        } else if (action == Action.UPGRADE) {
            PotionEffect old = player.getPotionEffect(type);
            if (old != null) {
                player.addPotionEffect(new PotionEffect(type, ticks > 0 ? ticks : old.getDuration(),
                        upgradedAmplifier(old.getAmplifier(), increment, maximum),
                        old.isAmbient(), old.hasParticles(), old.hasIcon()));
            }
        }
    }

    /**
     * Removes one random active effect. Harmful-only keeps the choice inside the harmful set and also skips a
     * registered buff whose owner did not declare itself harmful; a throwing reporter is skipped so one broken
     * addon cannot make the whole action fail.
     */
    private static void removeOneRandom(Player player, boolean harmfulOnly) {
        List<Runnable> ordinary = new ArrayList<>();
        List<Runnable> fallback = new ArrayList<>();
        for (PotionEffect present : player.getActivePotionEffects()) {
            if (!harmfulOnly || isHarmful(present.getType().getKey().getKey())) {
                ordinary.add(() -> player.removePotionEffect(present.getType()));
            }
        }
        for (CustomBuff buff : CustomBuffRegistry.activeBuffs(player)) {
            try {
                if (harmfulOnly && !buff.isHarmful()) {
                    continue;
                }
                (buff.isLowPriority() ? fallback : ordinary).add(() -> CustomBuffRegistry.clear(player, buff.id()));
            } catch (RuntimeException brokenAddon) {
                // Other registered effects stay selectable.
            }
        }
        List<Runnable> choices = ordinary.isEmpty() ? fallback : ordinary;
        if (!choices.isEmpty()) {
            choices.get(ThreadLocalRandom.current().nextInt(choices.size())).run();
        }
    }

    private static void mutateBuff(Player player, Action action, String id, boolean builtin, CustomBuff custom,
                                   int increment, int maximum, int ticks) {
        if (action == Action.REMOVE) {
            if (custom != null) {
                CustomBuffRegistry.clear(player, id);
            } else if ("farmersdelight:comfort".equals(id)) {
                EffectManager.removeComfort(player);
            } else {
                EffectManager.removeNourishment(player);
            }
            return;
        }
        if (action != Action.UPGRADE) {
            return;
        }
        try {
            int level = builtin
                    ? ("farmersdelight:comfort".equals(id)
                        ? EffectManager.comfortLevel(player)
                        : EffectManager.nourishmentLevel(player))
                    : custom.level(player);
            if (level <= 0) {
                return;
            }
            int next = (int) Math.min(Integer.MAX_VALUE,
                    (long) upgradedAmplifier(level - 1, increment, maximum) + 1L);
            if (builtin) {
                int seconds = ticks > 0 ? ticksToSeconds(ticks) : builtinRemainingSeconds(player, id);
                boolean comfort = "farmersdelight:comfort".equals(id);
                if (seconds > 0) {
                    if (comfort) {
                        EffectManager.applyComfort(player, seconds, next);
                    } else {
                        EffectManager.applyNourishment(player, seconds, next);
                    }
                    CustomBuffRegistry.syncState(player, id);
                }
            } else {
                int seconds = ticks > 0 ? ticksToSeconds(ticks) : custom.remainingSeconds(player);
                if (seconds > 0) {
                    CustomBuffRegistry.apply(player, id, next, seconds);
                }
            }
        } catch (RuntimeException brokenAddon) {
            // A failed reporter does not mutate unrelated effects.
        }
    }

    private static int builtinRemainingSeconds(Player player, String id) {
        return "farmersdelight:comfort".equals(id)
                ? EffectManager.comfortRemainingSeconds(player)
                : EffectManager.nourishmentRemainingSeconds(player);
    }

    static int ticksToSeconds(int ticks) {
        return (int) Math.max(1L, ((long) ticks + 19L) / 20L);
    }

    /** Accepts the short name, the id with or without a namespace, and the legacy _effect suffix. */
    static String canonicalEffectId(String id) {
        return switch (id) {
            case "nourishment", "nourishment_effect", "farmersdelight:nourishment",
                 "farmersdelight:nourishment_effect" -> "farmersdelight:nourishment";
            case "comfort", "comfort_effect", "farmersdelight:comfort",
                 "farmersdelight:comfort_effect" -> "farmersdelight:comfort";
            default -> id.indexOf(':') < 0 ? "minecraft:" + id : id;
        };
    }

    static int upgradedAmplifier(int current, int increment, int max) {
        return (int) Math.max(0, Math.min(Math.max(0, max), (long) current + increment));
    }

    static boolean isHarmful(String key) {
        return switch (key) {
            case "blindness", "darkness", "hunger", "nausea", "poison", "slowness", "mining_fatigue",
                 "weakness", "wither", "levitation", "bad_omen", "infested", "oozing", "weaving",
                 "wind_charged" -> true;
            default -> false;
        };
    }
}
