package com.huidu.farmersdelight.manager;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** Exercises the real session and display calculations using private stacks, without player IO. */
public final class HandheldSimulation {
    private final SkilletHandheldCooking.HandheldSession[] sessions;
    private final ItemStack[] pans;
    private final ItemStack[] ingredients;
    private final Predicate<ItemStack> recipeUnchanged;
    private final Supplier<ItemStack> recipeResult;
    private final boolean showProgress;
    private long calls;
    private long displayCalls;
    private long completions;
    private long totalNanos;
    private long maxTickNanos;
    private int ticks;

    public HandheldSimulation(int count, ItemStack pan, ItemStack ingredient, int duration,
                              NamespacedKey model, boolean showProgress,
                              Predicate<ItemStack> recipeUnchanged, Supplier<ItemStack> recipeResult) {
        if (count < 1 || count > 4096) throw new IllegalArgumentException("Session count must be 1..4096");
        this.recipeUnchanged = recipeUnchanged;
        this.recipeResult = recipeResult;
        this.showProgress = showProgress;
        sessions = new SkilletHandheldCooking.HandheldSession[count];
        pans = new ItemStack[count];
        ingredients = new ItemStack[count];
        for (int i = 0; i < count; i++) {
            pans[i] = pan.clone();
            pans[i].setAmount(1);
            ingredients[i] = ingredient.clone();
            ingredients[i].setAmount(1);
            sessions[i] = new SkilletHandheldCooking.HandheldSession(EquipmentSlot.HAND, 0, 40,
                    pans[i].clone(), ingredients[i].clone(), null, duration, model);
        }
    }

    public void tick() {
        long started = System.nanoTime();
        for (int i = 0; i < sessions.length; i++) {
            var session = sessions[i];
            calls++;
            if (!session.matches(0, pans[i], ingredients[i])) {
                throw new IllegalStateException("Simulation stacks changed");
            }
            if (session.advanceProgress()) {
                if (!recipeUnchanged.test(ingredients[i])) {
                    throw new IllegalStateException("Simulation recipe changed; restart after reload");
                }
                recipeResult.get().clone();
                completions++;
                session.progress = 0;
            } else if (session.progress % 4 == 0 && session.needsDisplayUpdate(session.model, showProgress)) {
                SkilletHandheldCooking.createHandheldDisplay(pans[i], session.model,
                        session.progress, session.duration, showProgress);
                session.displayedProgress = showProgress;
                session.displayedModel = session.model;
                displayCalls++;
            }
        }
        long elapsed = System.nanoTime() - started;
        totalNanos += elapsed;
        maxTickNanos = Math.max(maxTickNanos, elapsed);
        ticks++;
    }

    public Snapshot snapshot() {
        return new Snapshot(sessions.length, ticks, calls, displayCalls, completions, totalNanos, maxTickNanos);
    }

    public record Snapshot(int sessions, int ticks, long calls, long displayCalls, long completions,
                           long totalNanos, long maxTickNanos) { }
}
