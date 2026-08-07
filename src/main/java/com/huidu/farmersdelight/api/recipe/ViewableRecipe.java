package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;

@ApiStatus.OverrideOnly
public interface ViewableRecipe {

    String id();

    List<ItemStack> inputs();

    ItemStack result();

    default List<Component> infoLines(Player viewer) {
        return List.of();
    }

    default Component detailTitle() {
        return null;
    }

    default ItemStack icon() {
        return result();
    }

    default Map<String, List<ItemStack>> displaySlots() {
        return Map.of();
    }

    default Map<String, JumpTarget> jumpTargets() {
        return Map.of();
    }

    default boolean craftableBy(Player player) {
        return true;
    }
}
