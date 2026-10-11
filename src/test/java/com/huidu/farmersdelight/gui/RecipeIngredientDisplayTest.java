package com.huidu.farmersdelight.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecipeIngredientDisplayTest {
    @Test
    void unresolvedAndEmptyIngredientsHaveNoRealIconToClone() {
        assertNull(RecipeIngredientDisplay.copyIngredientIcon(null));
        assertNull(RecipeIngredientDisplay.copyIngredientIcon(new Stack(Material.AIR, 0)));
    }

    @Test
    void displayChangesCannotModifyTheResolvedIngredient() {
        ItemStack ingredient = new Stack(Material.BEEF, 3);
        ItemStack icon = RecipeIngredientDisplay.copyIngredientIcon(ingredient);
        assertNotSame(ingredient, icon);
        assertEquals(Material.BEEF, icon.getType());
        icon.setAmount(1);
        assertEquals(3, ingredient.getAmount());
    }

    private static final class Stack extends ItemStack {
        private final Material material;
        private int amount;

        Stack(Material material, int amount) {
            this.material = material;
            this.amount = amount;
        }

        @Override public Material getType() { return material; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int value) { amount = value; }
        @Override public boolean isEmpty() { return material == Material.AIR || amount <= 0; }
        @Override public ItemStack clone() { return new Stack(material, amount); }
    }
}
