package com.huidu.farmersdelight.effect;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.function.AbstractConditionalFunction;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public final class HotCocoaFunction<CTX extends Context> extends AbstractConditionalFunction<CTX> {

    private HotCocoaFunction(List<Condition<CTX>> predicates) {
        super(predicates);
    }

    @Override
    protected void runInternal(CTX ctx) {
        ctx.getOptionalParameter(DirectContextParameters.PLAYER).ifPresent(cePlayer -> {
            if (cePlayer.platformPlayer() instanceof Player player) {
                removeOne(player);
            }
        });
    }

    static void removeOne(Player player) {
        PotionEffectType selected = null;
        int candidates = 0;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            PotionEffectType type = effect.getType();
            if (type.getEffectCategory() == PotionEffectType.Category.HARMFUL
                    && ThreadLocalRandom.current().nextInt(++candidates) == 0) {
                selected = type;
            }
        }
        if (selected != null) {
            player.removePotionEffect(selected);
        }
    }

    public static <CTX extends Context> FunctionFactory<CTX, HotCocoaFunction<CTX>> factory(
            Function<ConfigSection, Condition<CTX>> conditionFactory) {
        return new AbstractFactory<>(conditionFactory) {
            @Override
            public HotCocoaFunction<CTX> create(ConfigSection section) {
                return new HotCocoaFunction<>(getPredicates(section));
            }
        };
    }
}
