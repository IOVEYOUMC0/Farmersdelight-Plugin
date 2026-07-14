package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A self-contained layout for one page (list or detail) of an addon's OWN recipe book, supplied by a
 * RecipeType. When a type provides layouts, FarmersDelight renders that type as an independent book
 * using these instead of the shared recipe-book-gui config — so multiple addons never pile into one
 * menu, and each addon controls its title (image-font textures included), grid, and decoration items.
 *
 * The addon builds this from its own config and pre-resolves any CraftEngine <image:>/<shift:>
 * glyphs in title() itself (it has CraftEngine access); FarmersDelight uses the title as-is.
 *
 * Lives in the name-stable api package; uses only Bukkit / Adventure / java types.
 */
public interface RecipeBookLayout {

    /** Window title (already fully resolved, e.g. with image-font background glyphs). */
    Component title();

    /** Row count (1-6); window size is rows*9. */
    int rows();

    /** One string per row (up to 9 chars each); each char maps to a role via legend(). */
    List<String> layout();

    /**
     * Maps a layout character to a slot role. Roles the book fills dynamically: recipe (list),
     * ingredient/result (detail), any custom role supplied by
     * ViewableRecipe#displaySlots() (e.g. fluid), and the buttons prev_page/
     * next_page/back/fill. Any other role is static decoration filled from
     * decorations().
     */
    Map<Character, String> legend();

    /** Static decoration/button items by role name (e.g. background, back, fill).
     * Items are used as-is, so addons may supply CraftEngine custom items here. */
    default Map<String, ItemStack> decorations() {
        return Map.of();
    }

    default int size() {
        return rows() * 9;
    }

    /** All slot indices whose layout char maps to role. */
    default List<Integer> slotsByType(String role) {
        List<Integer> slots = new ArrayList<>();
        List<String> rows = layout();
        Map<Character, String> legend = legend();
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            for (int col = 0; col < line.length() && col < 9; col++) {
                if (role.equals(legend.get(line.charAt(col)))) {
                    slots.add(row * 9 + col);
                }
            }
        }
        return slots;
    }

    /** The first slot for role, or -1 if none. */
    default int firstSlotByType(String role) {
        List<Integer> slots = slotsByType(role);
        return slots.isEmpty() ? -1 : slots.get(0);
    }
}
