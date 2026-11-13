package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Paper-side Nourishment always-eat path lowers the food level for one interaction tick. These tests pin the
 * decision matrix and the restore contract without a live server.
 */
class NourishmentEatRulesTest {

    @Test
    void lowersOnlyAtFullHungerForOrdinaryFoodUnderNourishment() {
        assertTrue(NourishmentEatRules.shouldLower(true, false, true, 20, true, false));
        assertFalse(NourishmentEatRules.shouldLower(true, false, true, 19, true, false),
                "below full hunger vanilla already eats");
        assertFalse(NourishmentEatRules.shouldLower(true, false, false, 20, true, false),
                "without Nourishment full hunger stays inedible");
        assertFalse(NourishmentEatRules.shouldLower(false, false, true, 20, true, false),
                "the config switch has to be able to turn it off");
        assertFalse(NourishmentEatRules.shouldLower(true, true, true, 20, true, false),
                "a denied item-use slot must not change the food level");
    }

    @Test
    void leavesAlwaysEdibleAndNonFoodItemsAlone() {
        assertFalse(NourishmentEatRules.shouldLower(true, false, true, 20, true, true),
                "always-edible food already consumes at full hunger");
        assertFalse(NourishmentEatRules.shouldLower(true, false, true, 20, false, false),
                "only food triggers the path");
    }

    @Test
    void lowersExactlyOneLevel() {
        assertEquals(19, NourishmentEatRules.loweredFoodLevel(20));
        assertEquals(0, NourishmentEatRules.loweredFoodLevel(0));
    }

    @Test
    void restoresOnlyItsOwnTemporaryDrop() {
        assertEquals(20, NourishmentEatRules.restoredFoodLevel(19, 20));
        assertEquals(20, NourishmentEatRules.restoredFoodLevel(20, 20));
        assertEquals(18, NourishmentEatRules.restoredFoodLevel(18, 20),
                "a different level is not ours to overwrite");
        assertEquals(21, NourishmentEatRules.restoredFoodLevel(21, 20));
    }

    @Test
    void listenerRestoresOnTheNextTickAndStaysRegistered() throws IOException {
        String listener = read("src/main/java/com/huidu/farmersdelight/listener/NourishmentEatListener.java");
        assertTrue(listener.contains("RESTORE_DELAY_TICKS = 1L"), "the temporary drop lasts one tick");
        assertTrue(listener.contains("runLaterForEntity"), "the restore has to run on the player's scheduler");
        assertTrue(listener.contains("buff.nourishment-always-eat"), "the config switch is the documented path");
        assertTrue(listener.contains("PlayerQuitEvent"), "a quit inside the one-tick window is repaired");
        assertFalse(listener.contains("isCancelled()"),
                "PlayerInteractEvent#isCancelled is deprecated; the handler reads useItemInHand instead");
        assertTrue(listener.contains("useItemInHand() == Event.Result.DENY"));
        assertTrue(listener.indexOf("EffectManager.hasNourishment(player)")
                        < listener.indexOf("FoodProperties food = foodProperties(item)"),
                "cheap Nourishment gate must run before item component inspection");
        assertTrue(listener.indexOf("player.getFoodLevel() < NourishmentEatRules.FULL_FOOD_LEVEL")
                        < listener.indexOf("FoodProperties food = foodProperties(item)"),
                "cheap food-level gate must run before item component inspection");

        String registry = read("src/main/java/com/huidu/farmersdelight/ListenerRegistry.java");
        assertTrue(registry.contains("new NourishmentEatListener(plugin)"), "the handler is registered");

        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("nourishment-always-eat:"), "the shipped config documents the switch");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
