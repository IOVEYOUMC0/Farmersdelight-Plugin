package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A composable condition for FarmersDelight recipe matching. The condition is a read-only predicate:
 * it may inspect the player, station location and input stacks, but must not mutate any of them.
 *
 * <p>The callback runs on the region thread owning the station. Exceptions are isolated by the
 * registry and treated as "condition not satisfied".</p>
 */
@ApiStatus.Experimental
public interface RecipeCondition {

    ContentId id();

    boolean test(RecipeContext context);

    /**
     * Optional scope. Return false to leave unrelated recipes untouched; the default applies the
     * condition to every FD recipe path. Scope by recipe type/id (e.g. only {@code myaddon:*} ids)
     * when the condition is addon-specific.
     */
    default boolean appliesTo(@Nullable String recipeType, @Nullable String recipeId) {
        return true;
    }
}
