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

import java.util.List;
import java.util.function.Function;

public final class FoodBuffFunction<CTX extends Context> extends AbstractConditionalFunction<CTX> {

    public enum Kind {COMFORT, NOURISHMENT}

    private final Kind kind;
    private final NumberProvider duration; // seconds
    private final NumberProvider level;    // 1-based

    private FoodBuffFunction(List<Condition<CTX>> predicates, Kind kind, NumberProvider duration, NumberProvider level) {
        super(predicates);
        this.kind = kind;
        this.duration = duration;
        this.level = level;
    }

    @Override
    protected void runInternal(CTX ctx) {
        ctx.getOptionalParameter(DirectContextParameters.PLAYER).ifPresent(cePlayer -> {
            if (!(cePlayer.platformPlayer() instanceof org.bukkit.entity.Player bukkitPlayer)) {
                return;
            }
            int seconds = Math.max(1, duration.getInt(ctx));
            int lvl = Math.max(1, level.getInt(ctx));
            if (kind == Kind.COMFORT) {
                FarmersDelightFoodEffects.applyComfort(bukkitPlayer, seconds, lvl);
            } else {
                FarmersDelightFoodEffects.applyNourishment(bukkitPlayer, seconds, lvl);
            }
        });
    }

    public static <CTX extends Context> FunctionFactory<CTX, FoodBuffFunction<CTX>> factory(
            Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory) {
        return new Factory<>(kind, conditionFactory);
    }

    private static final class Factory<CTX extends Context> extends AbstractFactory<CTX, FoodBuffFunction<CTX>> {
        private final Kind kind;

        Factory(Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory) {
            super(conditionFactory);
            this.kind = kind;
        }

        @Override
        public FoodBuffFunction<CTX> create(ConfigSection section) {
            return new FoodBuffFunction<>(
                    getPredicates(section),
                    kind,
                    section.getNumber("duration", ConfigConstants.CONSTANT_NINETY),
                    section.getNumber("level", ConfigConstants.CONSTANT_ONE)
            );
        }
    }
}
