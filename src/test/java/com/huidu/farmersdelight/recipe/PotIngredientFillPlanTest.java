package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotIngredientFillPlanTest {

    private static PotIngredientFillPlan.PlayerSlot slot(int index, String id, int amount) {
        return new PotIngredientFillPlan.PlayerSlot(index, id, amount);
    }

    // Arrays.asList, not List.of: an empty pot slot is represented by null.
    private static List<String> pot(String... ids) {
        return Arrays.asList(ids);
    }

    @Test
    void fillsTheRecordedIngredientsIntoEmptySlots() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of("minecraft:beef", "farmersdelight:tomato"),
                List.of(slot(3, "minecraft:beef", 2), slot(7, "farmersdelight:tomato", 1)),
                pot(null, null));

        assertEquals(PotIngredientFillPlan.Result.FILLED, plan.result());
        assertEquals(List.of(new PotIngredientFillPlan.Move(0, 0), new PotIngredientFillPlan.Move(1, 1)),
                plan.moves());
    }

    @Test
    void keepsIngredientsAlreadyInThePotAndOnlyMovesTheMissingOne() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of("minecraft:beef", "farmersdelight:tomato"),
                List.of(slot(0, "farmersdelight:tomato", 1)),
                pot("minecraft:beef", null));

        assertEquals(PotIngredientFillPlan.Result.FILLED, plan.result());
        assertEquals(List.of(new PotIngredientFillPlan.Move(0, 1)), plan.moves());
    }

    @Test
    void reportsMissingIngredientsWithoutMoves() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of("minecraft:beef", "farmersdelight:tomato"),
                List.of(slot(0, "minecraft:beef", 1)),
                pot(null, null));

        assertEquals(PotIngredientFillPlan.Result.MISSING_ITEMS, plan.result());
        assertTrue(plan.moves().isEmpty());
    }

    @Test
    void refusesAPotThatAlreadyHoldsSomethingElse() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of("minecraft:beef"),
                List.of(slot(0, "minecraft:beef", 1)),
                pot(null, "farmersdelight:onion"));

        assertEquals(PotIngredientFillPlan.Result.OTHER_INGREDIENTS, plan.result());
        assertTrue(plan.moves().isEmpty());
    }

    @Test
    void reportsAnAlreadyFilledPot() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of("minecraft:beef"),
                List.of(slot(0, "minecraft:beef", 1)),
                pot("minecraft:beef"));

        assertEquals(PotIngredientFillPlan.Result.ALREADY_FILLED, plan.result());
    }

    @Test
    void reportsNoSpaceWhenTheRecipeHasMoreIngredientsThanSlots() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of("minecraft:beef", "farmersdelight:tomato"),
                List.of(slot(0, "minecraft:beef", 1), slot(1, "farmersdelight:tomato", 1)),
                pot((String) null));

        assertEquals(PotIngredientFillPlan.Result.NO_SPACE, plan.result());
    }

    @Test
    void aDuplicatedIngredientCanComeFromOneStack() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of("minecraft:beef", "minecraft:beef"),
                List.of(slot(2, "minecraft:beef", 2)),
                pot(null, null));

        assertEquals(PotIngredientFillPlan.Result.FILLED, plan.result());
        assertEquals(List.of(new PotIngredientFillPlan.Move(0, 0), new PotIngredientFillPlan.Move(0, 1)),
                plan.moves());
    }

    @Test
    void aDuplicatedIngredientNeedsEnoughInTheStack() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of("minecraft:beef", "minecraft:beef"),
                List.of(slot(2, "minecraft:beef", 1)),
                pot(null, null));

        assertEquals(PotIngredientFillPlan.Result.MISSING_ITEMS, plan.result());
    }

    @Test
    void anEmptyRecordingIsInvalid() {
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(
                List.of(), List.of(slot(0, "minecraft:beef", 1)), pot((String) null));

        assertEquals(PotIngredientFillPlan.Result.INVALID_RECIPE, plan.result());
    }
}
