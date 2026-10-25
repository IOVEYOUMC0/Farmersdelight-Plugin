package com.huidu.farmersdelight.api.recipe;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CompletableFuture;

/**
 * A recipe editor whose persistence completes outside the player's owning thread. Addon recipe types that write
 * files asynchronously implement this instead of the callback defaults in `RecipeEditor`; the editor view
 * keeps the GUI locked until the returned future completes.
 */
@ApiStatus.OverrideOnly
public interface AsyncRecipeEditor extends RecipeEditor {

    /** Saves the draft; the future completes true only after the write and reload succeeded. */
    CompletableFuture<Boolean> saveAsync(EditableRecipe draft);

    /** Deletes the recipe; the future completes true only after the write and reload succeeded. */
    CompletableFuture<Boolean> deleteAsync(String id);
}
