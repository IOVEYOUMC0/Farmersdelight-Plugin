package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;

import java.util.Set;

public enum RecipeStationType {
    COOKING_POT(RecipeDiscoveryManager.TYPE_COOKING_POT, "pot", "cooking_pot", "cookingpot"),
    CUTTING_BOARD(RecipeDiscoveryManager.TYPE_CUTTING_BOARD, "board", "cutting_board", "cuttingboard");

    private final String discoveryTypeId;
    private final String[] aliases;

    RecipeStationType(String discoveryTypeId, String... aliases) {
        this.discoveryTypeId = discoveryTypeId;
        this.aliases = aliases;
    }

    public String discoveryTypeId() { return discoveryTypeId; }

    public static String resolveTypeId(String token, Set<String> knownTypes) {
        for (RecipeStationType t : values()) {
            for (String alias : t.aliases) {
                if (alias.equals(token)) return t.discoveryTypeId;
            }
        }
        if (knownTypes.contains(token)) return token;
        return null;
    }

    public static boolean isCookingPot(String token) {
        return COOKING_POT.matches(token);
    }

    public static boolean isCuttingBoard(String token) {
        return CUTTING_BOARD.matches(token);
    }

    public boolean matches(String token) {
        for (String alias : aliases) {
            if (alias.equals(token)) return true;
        }
        return false;
    }
}
