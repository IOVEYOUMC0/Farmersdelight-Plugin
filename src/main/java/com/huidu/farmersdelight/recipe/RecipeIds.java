package com.huidu.farmersdelight.recipe;

import java.util.Set;

/**
 * The one place that decides what a recipe id typed into a command means and how one is shown back.
 */
public final class RecipeIds {

    private static final String DEFAULT_NAMESPACE = "farmersdelight";

    private RecipeIds() {
    }

    /**
     * The stored id a typed token refers to, or null when that type has no such recipe.
     */
    public static String canonical(String typeId, String token, Set<String> knownIds) {
        if (token == null || token.isBlank() || knownIds == null || knownIds.isEmpty()) {
            return null;
        }
        if (knownIds.contains(token)) {
            return token;
        }
        String namespaced = namespaceOf(typeId) + ":" + token;
        if (knownIds.contains(namespaced)) {
            return namespaced;
        }
        int colon = token.indexOf(':');
        if (colon > 0 && colon < token.length() - 1 && knownIds.contains(token.substring(colon + 1))) {
            return token.substring(colon + 1);
        }
        return null;
    }

    /** The form shown to a player: namespaced, whatever the stored spelling is. */
    public static String displayId(String typeId, String storedId) {
        if (storedId == null || storedId.isBlank() || storedId.indexOf(':') >= 0) {
            return storedId;
        }
        return namespaceOf(typeId) + ":" + storedId;
    }

    /** The namespace a bare id of this type is displayed and completed with: the type id's own namespace. */
    public static String namespaceOf(String typeId) {
        if (typeId == null) {
            return DEFAULT_NAMESPACE;
        }
        int colon = typeId.indexOf(':');
        return colon <= 0 ? DEFAULT_NAMESPACE : typeId.substring(0, colon);
    }
}
