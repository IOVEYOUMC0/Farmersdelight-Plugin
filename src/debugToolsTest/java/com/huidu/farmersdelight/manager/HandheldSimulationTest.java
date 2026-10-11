package com.huidu.farmersdelight.manager;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HandheldSimulationTest {
    @Test
    void countScalesWorkAndRepeatedCompletionsKeepInputsUntouched() {
        Stack pan = new Stack(Material.IRON_SWORD, 3);
        Stack food = new Stack(Material.BEEF, 12);
        AtomicInteger lookups = new AtomicInteger();
        var simulation = new HandheldSimulation(32, pan, food, 8, null, true,
                item -> { lookups.incrementAndGet(); return true; }, () -> new Stack(Material.COOKED_BEEF, 1));
        for (int i = 0; i < 16; i++) simulation.tick();
        var result = simulation.snapshot();
        assertEquals(32, result.sessions());
        assertEquals(16, result.ticks());
        assertEquals(512, result.calls());
        assertEquals(64, result.completions());
        assertEquals(64, result.displayCalls());
        assertEquals(64, lookups.get());
        assertEquals(3, pan.getAmount());
        assertEquals(12, food.getAmount());
        assertEquals(0, pan.damage);
    }

    @Test
    void hiddenProgressUsesTheRealStaticDisplaySuppression() {
        Stack pan = new Stack(Material.IRON_SWORD, 1);
        Stack food = new Stack(Material.BEEF, 1);
        var simulation = new HandheldSimulation(4, pan, food, 8, null, false, item -> true, () -> food);
        for (int i = 0; i < 16; i++) simulation.tick();
        assertEquals(4, simulation.snapshot().displayCalls());
        assertEquals(8, simulation.snapshot().completions());
    }

    @Test
    void changedRecipesFailTheSampleInsteadOfReportingSuccessfulCompletions() {
        Stack food = new Stack(Material.BEEF, 1);
        var simulation = new HandheldSimulation(1, new Stack(Material.IRON_SWORD, 1), food,
                8, null, true, item -> false, () -> food);
        for (int i = 0; i < 7; i++) simulation.tick();
        assertThrows(IllegalStateException.class, simulation::tick);
        assertEquals(0, simulation.snapshot().completions());
        assertThrows(IllegalArgumentException.class, () -> new HandheldSimulation(4097,
                food, food, 8, null, false, item -> true, () -> food));
    }

    private static final class Stack extends ItemStack {
        final Material material;
        int count;
        int damage;
        NamespacedKey model;

        Stack(Material material, int count) { super(); this.material = material; this.count = count; }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return count; }
        @Override public void setAmount(int count) { this.count = count; }
        @Override public boolean isSimilar(ItemStack other) {
            return other instanceof Stack stack && material == stack.material && damage == stack.damage;
        }
        @Override public Stack clone() {
            Stack stack = new Stack(material, count); stack.damage = damage; stack.model = model; return stack;
        }
        @Override public ItemMeta getItemMeta() {
            Stack copy = clone();
            return (ItemMeta) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Damageable.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasMaxDamage" -> true;
                        case "getMaxDamage" -> 250;
                        case "getDamage" -> copy.damage;
                        case "setDamage" -> { copy.damage = (int) args[0]; yield null; }
                        case "setItemModel" -> { copy.model = (NamespacedKey) args[0]; yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
        @Override public boolean setItemMeta(ItemMeta meta) {
            damage = ((Damageable) meta).getDamage(); return true;
        }
    }
}
