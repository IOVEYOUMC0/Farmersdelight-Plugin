package com.huidu.farmersdelight.recipe;

import java.util.Set;

/**
 * The one place that decides what a recipe id typed into a command means and how one is shown back.
 *
 *
 * Recipe ids are stored exactly as their source spells them: the plugin's own files and every pack section
 * contribute the YAML key the file carries, for example beef_stew, while a station type that derives its ids
 * from item ids stores a namespaced one, for example barbequesdelight:raw_beef_skewer. A command therefore
 * accepts either spelling and resolves it to the stored form, which is what persisted state and the api keep
 * using, and displays the namespaced form so both sides of the same list read alike.
 */
public final class RecipeIds {

    private static final String DEFAULT_NAMESPACE = "farmersdelight";

    private RecipeIds() {
    }

    /**
     * The stored id a typed token refers to, or null when that type has no such recipe.
     *
     * The stored spelling is tried first, so state written by an earlier build and every script that used the
     * bare key keeps resolving. The token is then tried with the type's own namespace, and finally a
     * namespaced token is stripped back to its bare key, which is how the plugin's own recipes are stored.
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
