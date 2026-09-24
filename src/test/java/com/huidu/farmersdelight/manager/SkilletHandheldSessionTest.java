package com.huidu.farmersdelight.manager;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SkilletHandheldSessionTest {
    @Test
    void staticDisplaySkipsRefreshButSettingAndModelChangesStillSync() {
        var session = session(EquipmentSlot.HAND, 2, 40, new Stack("pan", 1, 17), new Stack("food", 12, 0));
        var model = NamespacedKey.fromString("custom:cooking");
        assertTrue(session.needsDisplayUpdate(null, false), "The initial display must always be sent");
        session.displayedProgress = false;
        assertFalse(session.needsDisplayUpdate(null, false));
        assertTrue(session.needsDisplayUpdate(model, false));
        session.displayedModel = model;
        assertFalse(session.needsDisplayUpdate(model, false));
        assertTrue(session.needsDisplayUpdate(model, true), "Enabling progress during cooking must update");
        session.displayedProgress = true;
        assertTrue(session.needsDisplayUpdate(model, false), "Disabling progress must restore real damage once");
    }

    @Test
    void placedCookingUsesTheSameGameTickDurationAsHandheldCooking() {
        for (int duration : new int[]{60, 120, 121}) {
            var placed = new SkilletData(null, duration);
            int elapsed = 0;
            while (placed.cookingProgress < duration) {
                placed.advanceCookingProgress(SkilletManager.PLACED_TICK_INTERVAL, true, 2);
                elapsed += SkilletManager.PLACED_TICK_INTERVAL;
            }
            assertTrue(elapsed >= duration && elapsed - duration < SkilletManager.PLACED_TICK_INTERVAL,
                    "Placed cooking may round to its polling interval, but must not multiply the recipe time");
            assertEquals(duration, placed.cookingProgress);
            placed.advanceCookingProgress(SkilletManager.PLACED_TICK_INTERVAL, false, 2);
            assertEquals(duration - 2 * SkilletManager.PLACED_TICK_INTERVAL, placed.cookingProgress);
            placed.advanceCookingProgress(SkilletManager.PLACED_TICK_INTERVAL, false, Integer.MAX_VALUE);
            assertEquals(0, placed.cookingProgress, "Large configured cooling rates must not overflow");
        }
    }

    @Test
    void aSkilletStarvedByTheTickBudgetStillCooksInRealTime() {
        var skillet = new SkilletData(null, 600);
        // A fresh entry has no previous stamp, so the first credit is one interval: time spent unloaded is
        // never credited.
        assertEquals(SkilletManager.PLACED_TICK_INTERVAL,
                skillet.elapsedSinceLastCredit(1000L, SkilletManager.PLACED_TICK_INTERVAL, 1200));
        // Starved visits are further apart; the real gap is credited so progress tracks wall-clock ticks.
        assertEquals(16, skillet.elapsedSinceLastCredit(1016L, SkilletManager.PLACED_TICK_INTERVAL, 1200));
        assertEquals(16, skillet.elapsedSinceLastCredit(1032L, SkilletManager.PLACED_TICK_INTERVAL, 1200));
        // A long stall is clamped rather than credited as one implausible batch.
        assertEquals(1200, skillet.elapsedSinceLastCredit(100_000L, SkilletManager.PLACED_TICK_INTERVAL, 1200));
        // A non-advancing clock falls back to a single interval instead of crediting nothing or going negative.
        assertEquals(SkilletManager.PLACED_TICK_INTERVAL,
                skillet.elapsedSinceLastCredit(100_000L, SkilletManager.PLACED_TICK_INTERVAL, 1200));
    }

    @Test
    void eatingInTheOtherHandOverridesFreshCookingInputForBothHands() {
        AtomicReference<EquipmentSlot> usingHand = new AtomicReference<>();
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isHandRaised" -> usingHand.get() != null;
                    case "getHandRaised" -> usingHand.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        for (EquipmentSlot hand : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            int slot = hand == EquipmentSlot.HAND ? 2 : 40;
            int foodSlot = hand == EquipmentSlot.HAND ? 40 : 2;
            Stack pan = new Stack("pan", 1, 17);
            Stack food = new Stack("food", 12, 0);
            var session = session(hand, slot, foodSlot, pan, food);
            session.lastInput = System.currentTimeMillis();
            usingHand.set(hand == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
            assertFalse(SkilletManager.isHandheldInputActive(player, session), "Eating must stop even a nearly finished cook");
            assertFalse(session.consumed);
            assertEquals(12, food.getAmount());
            usingHand.set(null);
            session.lastInput = System.currentTimeMillis();
            assertTrue(SkilletManager.isHandheldInputActive(player, session));
            session.lastInput = System.currentTimeMillis() - 1_000;
            assertFalse(SkilletManager.isHandheldInputActive(player, session));
            usingHand.set(hand);
            assertTrue(SkilletManager.isHandheldInputActive(player, session), "Using the cooking tool itself remains supported");
        }
    }

    @Test
    void progressChangesOnlyThePacketCopyIncludingAnyConfiguredModel() {
        Stack pan = new Stack("pan", 1, 57);
        pan.model = NamespacedKey.fromString("custom:normal");
        var model = NamespacedKey.fromString("custom:cooking");
        Stack display = (Stack) SkilletManager.createHandheldDisplay(pan, model, 20, 100, true);
        assertEquals(57, pan.damage);
        assertEquals(NamespacedKey.fromString("custom:normal"), pan.model);
        assertEquals(200, display.damage);
        assertEquals(model, display.model);
        pan.damage += 2;
        display = (Stack) SkilletManager.createHandheldDisplay(pan, null, 30, 100, false);
        assertEquals(59, display.damage);
        assertEquals(59, pan.damage);
        assertEquals(pan.model, display.model);
    }

    @Test
    void foodIsDebitedExactlyOnceAtCompletionForEitherHand() {
        for (EquipmentSlot hand : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            int slot = hand == EquipmentSlot.HAND ? 2 : 40;
            int foodSlot = hand == EquipmentSlot.HAND ? 40 : 2;
            Stack pan = new Stack("pan", 1, 17);
            Stack food = new Stack("food", 12, 0);
            PlayerInventory inventory = inventory(new HashMap<>(Map.of(slot, pan, foodSlot, food)));
            var session = session(hand, slot, foodSlot, pan, food);
            assertEquals(12, food.getAmount(), "Starting/cancelling must not remove ingredients");
            assertTrue(session.consumeIngredient(inventory, false));
            assertEquals(11, food.getAmount());
            assertFalse(session.consumeIngredient(inventory, false));
            assertEquals(11, food.getAmount());
            assertEquals(17, pan.damage);
        }
    }

    @Test
    void droppingBreakingReplacingOrDamagingThePanCannotCompleteOrRefundFood() {
        for (int change = 0; change < 4; change++) {
            Stack pan = new Stack("pan", 1, 249);
            Stack food = new Stack("food", 3, 0);
            Map<Integer, ItemStack> slots = new HashMap<>(Map.of(2, pan, 40, food));
            var session = session(EquipmentSlot.HAND, 2, 40, pan, food);
            if (change == 0) slots.remove(2);
            if (change == 1) pan.setAmount(0);
            if (change == 2) slots.put(2, new Stack("replacement", 1, 0));
            if (change == 3) pan.damage++;
            assertFalse(session.consumeIngredient(inventory(slots), false));
            assertEquals(3, food.getAmount());
            assertFalse(session.consumeIngredient(inventory(slots), false));
        }
    }

    @Test
    void missingIngredientsNeverProduceAResultAndCreativeCommitsOnlyOnce() {
        Stack pan = new Stack("pan", 1, 0);
        Stack food = new Stack("food", 1, 0);
        Map<Integer, ItemStack> slots = new HashMap<>(Map.of(2, pan, 40, food));
        var session = session(EquipmentSlot.HAND, 2, 40, pan, food);
        food.setAmount(0);
        assertFalse(session.consumeIngredient(inventory(slots), false));
        food.setAmount(1);
        assertTrue(session.consumeIngredient(inventory(slots), true));
        assertFalse(session.consumeIngredient(inventory(slots), true));
        assertEquals(1, food.getAmount());
    }

    private SkilletManager.HandheldSession session(EquipmentSlot hand, int slot, int foodSlot, Stack pan, Stack food) {
        return new SkilletManager.HandheldSession(hand, slot, foodSlot, pan.clone(), food.clone(), null, 100, null);
    }

    private PlayerInventory inventory(Map<Integer, ItemStack> slots) {
        return (PlayerInventory) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeldItemSlot" -> 2;
                    case "getItem" -> slots.get((Integer) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Stack extends ItemStack {
        final String kind;
        int amount;
        int damage;
        NamespacedKey model;

        Stack(String kind, int amount, int damage) {
            super();
            this.kind = kind;
            this.amount = amount;
            this.damage = damage;
        }

        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int value) { amount = value; }
        @Override public Material getType() { return Material.STONE; }
        @Override public boolean isSimilar(ItemStack other) {
            return other instanceof Stack stack && kind.equals(stack.kind) && damage == stack.damage && Objects.equals(model, stack.model);
        }
        @Override public Stack clone() {
            Stack copy = new Stack(kind, amount, damage);
            copy.model = model;
            return copy;
        }
        @Override public ItemMeta getItemMeta() {
            Stack state = clone();
            return (ItemMeta) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Damageable.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasMaxDamage" -> true;
                        case "getMaxDamage" -> 250;
                        case "getDamage" -> state.damage;
                        case "setDamage" -> { state.damage = (int) args[0]; yield null; }
                        case "getItemModel" -> state.model;
                        case "setItemModel" -> { state.model = (NamespacedKey) args[0]; yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
        @Override public boolean setItemMeta(ItemMeta meta) {
            damage = ((Damageable) meta).getDamage();
            model = meta.getItemModel();
            return true;
        }
    }
}
