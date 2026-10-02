package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.api.effect.FarmersDelightFoodEffects;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.function.AbstractConditionalFunction;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.plugin.context.number.NumberProvider;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Function;

public final class FoodBuffFunction<CTX extends Context> extends AbstractConditionalFunction<CTX> {

    public enum Kind {COMFORT, NOURISHMENT}

    private final Kind kind;
    private final NumberProvider duration; // seconds, or ticks when durationInTicks
    private final NumberProvider level;    // 1-based
    private final boolean durationInTicks;

    private FoodBuffFunction(List<Condition<CTX>> predicates, Kind kind, NumberProvider duration, NumberProvider level,
                             boolean durationInTicks) {
        super(predicates);
        this.kind = kind;
        this.duration = duration;
        this.level = level;
        this.durationInTicks = durationInTicks;
    }

    @Override
    protected void runInternal(CTX ctx) {
        ctx.getOptionalParameter(DirectContextParameters.PLAYER).ifPresent(cePlayer -> {
            if (!(cePlayer.platformPlayer() instanceof Player bukkitPlayer)) {
                return;
            }
            int seconds = durationInTicks
                    ? ticksToSeconds(duration.getInt(ctx))
                    : Math.max(1, duration.getInt(ctx));
            int lvl = Math.max(1, level.getInt(ctx));
            if (kind == Kind.COMFORT) {
                FarmersDelightFoodEffects.applyComfort(bukkitPlayer, seconds, lvl);
            } else {
                FarmersDelightFoodEffects.applyNourishment(bukkitPlayer, seconds, lvl);
            }
        });
    }

    /**
     * Converts a tick duration to the whole seconds the buff API takes, rounding up so a duration below a
     * second still grants the effect for one.
     */
    static int ticksToSeconds(int ticks) {
        return Math.max(1, (Math.max(0, ticks) + 19) / 20);
    }

    public static <CTX extends Context> FunctionFactory<CTX, FoodBuffFunction<CTX>> factory(
            Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory) {
        return new Factory<>(kind, conditionFactory, false);
    }

    /**
     * The same function with duration read in ticks, which is how the PapersDelight packs write it
     * (their *_effect functions); this plugin's own packs write seconds.
     */
    public static <CTX extends Context> FunctionFactory<CTX, FoodBuffFunction<CTX>> ticksFactory(
            Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory) {
        return new Factory<>(kind, conditionFactory, true);
    }

    private static final class Factory<CTX extends Context> extends AbstractFactory<CTX, FoodBuffFunction<CTX>> {
        private final Kind kind;
        private final boolean durationInTicks;

        Factory(Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory, boolean durationInTicks) {
            super(conditionFactory);
            this.kind = kind;
            this.durationInTicks = durationInTicks;
        }

        @Override
        public FoodBuffFunction<CTX> create(ConfigSection section) {
            return new FoodBuffFunction<>(
                    getPredicates(section),
                    kind,
                    section.getNumber("duration", ConfigConstants.CONSTANT_NINETY),
                    section.getNumber("level", ConfigConstants.CONSTANT_ONE),
                    durationInTicks
            );
        }
    }
}
