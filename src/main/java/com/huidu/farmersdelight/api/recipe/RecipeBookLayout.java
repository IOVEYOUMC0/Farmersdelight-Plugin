package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApiStatus.OverrideOnly
public interface RecipeBookLayout {

    Component title();

    int rows();

    List<String> layout();

    Map<Character, String> legend();

    default Map<String, ItemStack> decorations() {
        return Map.of();
    }

    default int size() {
        return rows() * 9;
    }

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

    default int firstSlotByType(String role) {
        List<Integer> slots = slotsByType(role);
        return slots.isEmpty() ? -1 : slots.getFirst();
    }
}
