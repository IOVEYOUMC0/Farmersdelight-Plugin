package com.huidu.farmersdelight.api.recipe;

/**
 * A navigation target in the recipe book: recipe recipeId inside the registered RecipeType typeId. Supplied
 * by ViewableRecipe.jumpTargets() to make a display-role slot clickable, so clicking it opens that recipe's
 * detail (for example the keg's required-fluid icon jumps to the recipe that produces that fluid).
 *
 * Lives in the name-stable api package; uses only java types.
 */
public record JumpTarget(String typeId, String recipeId) {
}
