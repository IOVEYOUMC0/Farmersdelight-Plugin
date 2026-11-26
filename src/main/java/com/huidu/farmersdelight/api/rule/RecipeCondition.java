package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;
import org.jetbrains.annotations.ApiStatus;

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
}
