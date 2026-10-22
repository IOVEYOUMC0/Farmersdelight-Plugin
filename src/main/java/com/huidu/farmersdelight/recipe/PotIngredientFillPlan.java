package com.huidu.farmersdelight.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detached plan for filling a cooking pot with the ingredients a recipe item recorded. Nothing mutates here: the
 * caller applies the moves only when the result is {@link Result#FILLED}, so a failed plan never moves an item.
 */
public final class PotIngredientFillPlan {

    public enum Result {
        FILLED, ALREADY_FILLED, MISSING_ITEMS, NO_SPACE, OTHER_INGREDIENTS, INVALID_RECIPE
    }

    /** One player slot that provides an ingredient, with the amount still available in it. */
    public record PlayerSlot(int slot, String id, int amount) {
    }

    /** Player slot index -> pot slot index, both indices into the lists the plan was built from. */
    public record Move(int playerIndex, int potIndex) {
    }

    public record Plan(Result result, List<Move> moves) {

        static Plan of(Result result) {
            return new Plan(result, List.of());
        }
    }

    private PotIngredientFillPlan() {
    }

    /**
     * Plans the transfer without touching either side.
     *
     * @param requested the recorded ingredient ids, in record order (duplicates allowed)
     * @param player    the player's inventory slots, with their resolved item ids and stack amounts
     * @param potIds    the pot's input-slot ids, null for an empty slot
     */
    public static Plan prepare(List<String> requested, List<PlayerSlot> player, List<String> potIds) {
        if (requested == null || requested.isEmpty() || player == null || potIds == null) {
            return Plan.of(Result.INVALID_RECIPE);
        }
        List<String> wanted = new ArrayList<>(requested.size());
        for (String id : requested) {
            String normalized = normalize(id);
            if (normalized == null) {
                return Plan.of(Result.INVALID_RECIPE);
            }
            wanted.add(normalized);
        }
        if (wanted.size() > potIds.size()) {
            return Plan.of(Result.NO_SPACE);
        }

        boolean[] potClaimed = new boolean[potIds.size()];
        List<String> missing = new ArrayList<>();
        for (String id : wanted) {
            int present = -1;
            for (int i = 0; i < potIds.size(); i++) {
                if (!potClaimed[i] && matches(id, potIds.get(i))) {
                    present = i;
                    break;
                }
            }
            if (present >= 0) {
                potClaimed[present] = true;
            } else {
                missing.add(id);
            }
        }
        // A pot that already holds something the recipe did not record would mix two recipes: refuse instead.
        for (int i = 0; i < potIds.size(); i++) {
            if (potIds.get(i) != null && !potClaimed[i]) {
                return Plan.of(Result.OTHER_INGREDIENTS);
            }
        }
        if (missing.isEmpty()) {
            return Plan.of(Result.ALREADY_FILLED);
        }

        int[] available = new int[player.size()];
        for (int i = 0; i < player.size(); i++) {
            available[i] = Math.max(0, player.get(i).amount());
        }
        List<Move> moves = new ArrayList<>(missing.size());
        for (String id : missing) {
            int playerIndex = -1;
            for (int i = 0; i < player.size(); i++) {
                if (available[i] > 0 && matches(id, player.get(i).id())) {
                    playerIndex = i;
                    break;
                }
            }
            if (playerIndex < 0) {
                return Plan.of(Result.MISSING_ITEMS);
            }
            int potIndex = -1;
            for (int i = 0; i < potIds.size(); i++) {
                if (potIds.get(i) == null && !potClaimed[i]) {
                    potIndex = i;
                    break;
                }
            }
            if (potIndex < 0) {
                return Plan.of(Result.NO_SPACE);
            }
            available[playerIndex]--;
            potClaimed[potIndex] = true;
            moves.add(new Move(playerIndex, potIndex));
        }
        return new Plan(Result.FILLED, List.copyOf(moves));
    }

    // Recorded ids are lower-case CraftEngine keys; the comparison is by resolved item identity, so a vanilla
    // ingredient only matches a recorded vanilla id and a custom item only its own id.
    private static boolean matches(String wanted, String actual) {
        return actual != null && wanted.equals(normalize(actual));
    }

    private static String normalize(String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
