package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;

/**
 * A type-agnostic, displayable recipe shown in the generic recipe book. Addons adapt their own recipes
 * to this so FarmersDelight's recipe book can render them without knowing the addon's internal types.
 *
 * Lives in the name-stable api package; uses only Bukkit / Adventure / java types.
 */
@ApiStatus.OverrideOnly
public interface ViewableRecipe {

    /** Unique id within its RecipeType (used for detail/editor lookup). */
    String id();

    /** Input/ingredient items to display (already resolved to concrete stacks). */
    List<ItemStack> inputs();

    /** The produced item. */
    ItemStack result();

    /** Extra info lines shown in the detail view (e.g. time, experience). Defaults to none. */
    default List<Component> infoLines(Player viewer) {
        return List.of();
    }

    /**
     * Per-recipe detail-window title (used verbatim), or null to use the type's static detail-layout title.
     * Lets recipes differing in a headline attribute (e.g. the keg's fermenting temperature) show it in the
     * title bar. Defaults to none.
     */
    default Component detailTitle() {
        return null;
    }

    /** Icon shown in the recipe list; defaults to the result item. */
    default ItemStack icon() {
        return result();
    }

    /**
     * Extra display items keyed by a custom role name, for a type that supplies its own detail layout
     * (see RecipeType#detailLayout()). The detail view places each role's items into the layout
     * slots whose legend maps to that role — e.g. {"fluid": [...], "return": [...]} for the keg.
     * Roles ingredient/result are handled by #inputs()/#result() and need
     * not be repeated here. Defaults to none.
     */
    default Map<String, List<ItemStack>> displaySlots() {
        return Map.of();
    }

    /**
     * Optional navigation: makes the detail-view slot(s) for a display role clickable, jumping to another
     * recipe's detail (possibly a different RecipeType). Keyed by the same role name used in displaySlots (for
     * example "fluid"); clicking any slot that role occupies opens the target. A role absent here (or mapped
     * to null) is not clickable. The recipe book resolves the typeId to a registered RecipeType and opens the
     * recipeId's detail; it interprets neither the role nor the ids, so this stays type-agnostic. Defaults to
     * none.
     */
    default Map<String, JumpTarget> jumpTargets() {
        return Map.of();
    }

    /**
     * Whether player can currently make this recipe (has the required inputs). Used by the recipe
     * book's optional "craftable only" filter. Defaults to true (always shown) for types that don't
     * implement an inventory check.
     */
    default boolean craftableBy(Player player) {
        return true;
    }
}
