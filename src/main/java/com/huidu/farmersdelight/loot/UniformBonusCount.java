package com.huidu.farmersdelight.loot;

import net.momirealms.craftengine.core.loot.function.formula.Formula;
import net.momirealms.craftengine.core.loot.function.formula.FormulaFactory;

import java.util.concurrent.ThreadLocalRandom;

public final class UniformBonusCount implements Formula {

    public static final FormulaFactory<UniformBonusCount> FACTORY = section -> new UniformBonusCount();

    @Override
    public int apply(int initialCount, int enchantmentLevel) {
        return initialCount + ThreadLocalRandom.current().nextInt(2 * enchantmentLevel + 1);
    }
}
