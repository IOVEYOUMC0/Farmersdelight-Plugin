package com.huidu.farmersdelight.handheld;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The raw-to-cooked mapping used by handheld skewer cooking.
 *
 * <p><b>Why this exists instead of reading the campfire recipe.</b> Farmer's Delight 1.4 asks the campfire
 * recipe registry what the held skewer cooks into. CraftEngine's recipe layer only exposes that through
 * bindings typed as {@code Object} / NMS ({@code BukkitRecipeManager.minecraftRecipeManager()},
 * {@code getIngredientLooks(..) -> List<Object>}, and the {@code RecipeTypeProxy.CAMPFIRE_COOKING} route),
 * and this plugin does not use reflection or NMS proxies. So the mapping is declared explicitly in
 * {@code config.yml} under {@code handheld-skewer.results} instead — visible to admins, offline testable, and
 * deliberately <b>independent of the pack's campfire recipes</b>: editing the campfire recipe in the pack does
 * not change what handheld cooking produces (the campfire/furnace/smoker paths themselves are untouched).
 *
 * <p>A skewer whose raw id has no entry never starts cooking, and a missing or empty table simply means the
 * handheld path has nothing to cook — it never falls back to guessing a product.
 */
public final class SkewerResultTable {

    private final Map<String, String> results;

    public SkewerResultTable(@Nullable Map<String, String> configured) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (configured != null) {
            for (Map.Entry<String, String> entry : configured.entrySet()) {
                String raw = normalize(entry.getKey());
                String cooked = normalize(entry.getValue());
                if (raw == null || cooked == null) {
                    continue;
                }
                copy.putIfAbsent(raw, cooked);
            }
        }
        this.results = copy;
    }

    /** The cooked id for this raw id, or null when this table does not cook it. */
    @Nullable
    public String resolve(@Nullable String rawId) {
        String raw = normalize(rawId);
        return raw == null ? null : results.get(raw);
    }

    /** Whether a raw id can be cooked by this path at all. */
    public boolean cooks(@Nullable String rawId) {
        return resolve(rawId) != null;
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }

    public int size() {
        return results.size();
    }

    @Nullable
    private static String normalize(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
