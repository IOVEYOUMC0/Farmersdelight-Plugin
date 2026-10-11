package com.huidu.farmersdelight.api.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

@ApiStatus.OverrideOnly
public interface DebugToolExtension {

    String name();

    /** Placement and activation callbacks must stay on the supplied position's owning region. */
    default boolean supportsPlacement() {
        return false;
    }

    default boolean supportsActivation() {
        return false;
    }

    default List<String> recipeIds() {
        return List.of();
    }

    /** Resolves all inputs before placement; the resolver accepts an item id or a #tag. */
    default RecipeSetup recipe(String id, Function<String, ItemStack> resolver) {
        return null;
    }

    record RecipeSetup(List<ItemStack> ingredients, BiPredicate<Player, Location> fill, String hint) {
        public RecipeSetup {
            ingredients = List.copyOf(ingredients);
            Objects.requireNonNull(fill);
            Objects.requireNonNull(hint);
        }
    }

    default int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo) {
        return 0;
    }

    @FunctionalInterface
    interface UndoSink {
        void capture(Location loc);
    }

    /** Legacy whole-world callbacks are not invoked by the debug command. */
    @Deprecated
    default int activate(Player player) {
        return 0;
    }

    /** Receives one operation-owned, protection-checked position on its region thread. */
    default int activateAt(Player player, Location location) {
        return 0;
    }

    default int activateAt(Player player, Location location, Function<String, ItemStack> resolver) {
        return activateAt(player, location);
    }

    default List<String> status(Player player) {
        return List.of();
    }

    default void cleanupBeforeUndo(Location location) {
    }
}
