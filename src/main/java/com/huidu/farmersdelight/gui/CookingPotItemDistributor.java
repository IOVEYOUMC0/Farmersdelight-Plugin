package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

// Decides where a stack taken from the player inventory should go inside the pot GUI,
// and how much of it the container vs. ingredient slots should absorb. The GUI supplies
// a writer so this stays independent of dirt-tracking and screen wiring.
public class CookingPotItemDistributor {

    private final CookingPotBlockEntity blockEntity;
    private final FarmersDelightPlugin plugin;
    private final Inventory inventory;
    private final Map<Integer, Integer> writableSlotMapping;
    private final int[] ingredientSlots;
    private final int[] containerSlots;
    private final BiConsumer<Integer, ItemStack> writer;

    public CookingPotItemDistributor(CookingPotBlockEntity blockEntity, FarmersDelightPlugin plugin,
                                     Inventory inventory, Map<Integer, Integer> writableSlotMapping,
                                     int[] ingredientSlots, int[] containerSlots,
                                     BiConsumer<Integer, ItemStack> writer) {
        this.blockEntity = blockEntity;
        this.plugin = plugin;
        this.inventory = inventory;
        this.writableSlotMapping = writableSlotMapping;
        this.ingredientSlots = ingredientSlots;
        this.containerSlots = containerSlots;
        this.writer = writer;
    }

    public void smartMoveFromPlayerInventory(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        if (shouldPrioritizeContainer(item)) {
            moveToContainerSlot(item);
            moveToIngredientSlots(item);
        } else {
            moveToIngredientSlots(item);
            moveToContainerSlot(item);
        }
    }

    private int[] writableGuiSlots(int[] slots) {
        if (slots == null || slots.length == 0) {
            return new int[0];
        }
        List<Integer> writableSlots = new ArrayList<>();
        for (int slot : slots) {
            if (writableSlotMapping.containsKey(slot)) {
                writableSlots.add(slot);
            }
        }
        return writableSlots.stream().mapToInt(Integer::intValue).toArray();
    }

    private boolean shouldPrioritizeContainer(ItemStack item) {
        int[] writableContainerSlots = writableGuiSlots(containerSlots);
        if (writableContainerSlots.length == 0) {
            return false;
        }

        for (int slot : writableContainerSlots) {
            ItemStack containerItem = inventory.getItem(slot);
            if (containerItem != null && !containerItem.getType().isAir()) {
                return isContainerCandidate(item) && containerItem.isSimilar(item);
            }
        }

        return blockEntity.doesMealHaveContainer() && isContainerCandidate(item);
    }

    private void moveToIngredientSlots(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        int[] writableIngredientSlots = writableGuiSlots(ingredientSlots);
        Set<Integer> orderedSlots = new LinkedHashSet<>();
        for (int slot : writableIngredientSlots) {
            ItemStack target = inventory.getItem(slot);
            if (target != null && !target.getType().isAir() && target.isSimilar(item)) {
                orderedSlots.add(slot);
            }
        }
        for (int slot : writableIngredientSlots) {
            ItemStack target = inventory.getItem(slot);
            if (target == null || target.getType().isAir()) {
                orderedSlots.add(slot);
            }
        }

        for (int slot : orderedSlots) {
            if (item.getAmount() <= 0) {
                return;
            }

            ItemStack target = inventory.getItem(slot);
            if (target == null || target.getType().isAir()) {
                ItemStack placed = item.clone();
                placed.setAmount(Math.min(item.getAmount(),
                        Math.min(item.getMaxStackSize(), inventory.getMaxStackSize())));
                writer.accept(slot, placed);
                item.setAmount(item.getAmount() - placed.getAmount());
                if (item.getAmount() <= 0) return;
                continue;
            }

            if (!target.isSimilar(item)) {
                continue;
            }

            int space = Math.min(target.getMaxStackSize(), inventory.getMaxStackSize()) - target.getAmount();
            if (space <= 0) {
                continue;
            }

            int toMove = Math.min(space, item.getAmount());
            target.setAmount(target.getAmount() + toMove);
            item.setAmount(item.getAmount() - toMove);
            writer.accept(slot, target);
        }
    }

    private void moveToContainerSlot(ItemStack item) {
        int[] writableContainerSlots = writableGuiSlots(containerSlots);
        if (writableContainerSlots.length == 0 || item == null || item.getType().isAir()) {
            return;
        }

        if (!isContainerCandidate(item)) {
            return;
        }

        for (int slot : writableContainerSlots) {
            ItemStack target = inventory.getItem(slot);
            if (target == null || target.getType().isAir() || !target.isSimilar(item)) {
                continue;
            }

            int space = Math.min(target.getMaxStackSize(), inventory.getMaxStackSize()) - target.getAmount();
            if (space <= 0) {
                continue;
            }

            int toMove = Math.min(space, item.getAmount());
            target.setAmount(target.getAmount() + toMove);
            item.setAmount(item.getAmount() - toMove);
            writer.accept(slot, target);
            if (item.getAmount() <= 0) {
                return;
            }
        }

        for (int slot : writableContainerSlots) {
            ItemStack target = inventory.getItem(slot);
            if (target != null && !target.getType().isAir()) {
                continue;
            }
            ItemStack placed = item.clone();
            placed.setAmount(Math.min(item.getAmount(),
                    Math.min(item.getMaxStackSize(), inventory.getMaxStackSize())));
            writer.accept(slot, placed);
            item.setAmount(item.getAmount() - placed.getAmount());
            if (item.getAmount() <= 0) return;
        }
    }

    private boolean isContainerCandidate(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        if (blockEntity.doesMealHaveContainer() && blockEntity.isContainerValid(item)) {
            return true;
        }

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null && plugin.getCookingPotRecipes().getValidContainerKeys().contains(customId)) {
            return true;
        }
        String materialKey = "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
        return plugin.getCookingPotRecipes().getValidContainerKeys().contains(materialKey);
    }
}
