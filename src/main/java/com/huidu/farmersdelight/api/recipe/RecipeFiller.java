package com.huidu.farmersdelight.api.recipe;

import org.bukkit.entity.Player;

/**
 * Supplied when a recipe book is opened from a station GUI (e.g. a keg). It moves a recipe's ingredients
 * from the player's inventory into that station, like the cooking pot's "fill" button. The recipe book
 * only shows a Fill button when a filler is present; the standalone book has none.
 *
 * Implementations must move items dupe-safely (remove from the player exactly what is placed).
 */
public interface RecipeFiller {

    /**
     * Fills the station this filler targets with recipe's ingredients from player's
     * inventory. Returns true if anything was filled (the filler may reopen its station GUI).
     */
    boolean fill(Player player, ViewableRecipe recipe);

    /**
     * Called when the player clicks "back" on the top-level page of a book opened from this station.
     * Reopen the originating station GUI and return true to go back there; return false (the default) to
     * let the book simply close. Lets a keg/station's recipe book return to the station, not the desktop.
     */
    default boolean onBack(Player player) {
        return false;
    }
}
