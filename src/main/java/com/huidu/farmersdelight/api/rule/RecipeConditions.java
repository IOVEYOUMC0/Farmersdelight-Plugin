package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;
import org.jetbrains.annotations.ApiStatus;

import java.util.Optional;
import java.util.Set;

/**
 * Registry for composable recipe conditions. Conditions are evaluated in registration order and
 * fail closed: a broken condition rejects the recipe instead of silently allowing it.
 *
 * <p>This is the first-phase contract: FD exposes the registry and evaluates conditions on its
 * built-in recipe paths. It does not replace CE recipe matchers or third-party station execution.</p>
 */
@ApiStatus.Experimental
public final class RecipeConditions {

    private static final RuleStore<RecipeCondition> STORE = new RuleStore<>();

    private RecipeConditions() {
    }

    /** Registers a condition; returns false for null conditions, null ids or a duplicate id. */
    public static boolean register(RecipeCondition condition) {
        return condition != null && STORE.register(condition.id(), condition);
    }

    public static boolean unregister(ContentId id) {
        return STORE.unregister(id);
    }

    public static Optional<RecipeCondition> find(ContentId id) {
        return STORE.find(id);
    }

    public static Set<ContentId> ids() {
        return STORE.ids();
    }

    /** True when at least one condition is registered; callers use this to skip context construction. */
    public static boolean hasConditions() {
        return STORE.size() > 0;
    }

    /** True when every registered condition accepts the context; no conditions means true. */
    public static boolean allMatch(RecipeContext context) {
        if (STORE.size() == 0) {
            return true;
        }
        if (context == null) {
            return false;
        }
        for (ContentId id : STORE.ids()) {
            RecipeCondition condition = STORE.find(id).orElse(null);
            if (condition == null) {
                continue;
            }
            try {
                if (!condition.appliesTo(context.recipeType(), context.recipeId())) {
                    continue;
                }
                if (!condition.test(context)) {
                    return false;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Fail closed: an addon condition that throws must not approve a recipe.
                return false;
            }
        }
        return true;
    }

    /** Test hook used by the API contract tests; not part of the addon-facing contract. */
    static void clearForTests() {
        STORE.clearForTests();
    }

    static int size() {
        return STORE.size();
    }
}
