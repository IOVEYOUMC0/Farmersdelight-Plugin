package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.api.util.ItemDelivery;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

// Decides how much of a finished meal a player should get from an output slot click and delivers it
// to their cursor or inventory (dropping leftovers). Kept apart from the GUI so the output handoff
// rules stay a single, self-contained concern.
public class CookingPotOutputTaker {

    private final CookingPotBlockEntity blockEntity;
    private final FarmersDelightPlugin plugin;
    private final Map<Integer, Integer> slotMapping;

    public CookingPotOutputTaker(CookingPotBlockEntity blockEntity, FarmersDelightPlugin plugin,
                                 Map<Integer, Integer> slotMapping) {
        this.blockEntity = blockEntity;
        this.plugin = plugin;
        this.slotMapping = slotMapping;
    }

    public int resolveOutputTakeAmount(InventoryClickEvent event, int guiSlot) {
        Integer entitySlot = slotMapping.get(guiSlot);
        if (entitySlot == null) {
            return 0;
        }
        ItemStack currentOutput = blockEntity.getInventorySlot(entitySlot);
        if (currentOutput == null || currentOutput.getType().isAir()) {
            return 0;
        }

        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        boolean rightClick = event.isRightClick();
        boolean shiftClick = event.isShiftClick();
        int guiStackLimit = event.getView().getTopInventory().getMaxStackSize();

        if (shiftClick) {
            return currentOutput.getAmount();
        }

        if (cursorEmpty) {
            return rightClick ? 1 : Math.min(currentOutput.getAmount(),
                    Math.min(currentOutput.getMaxStackSize(), guiStackLimit));
        }

        if (!cursor.isSimilar(currentOutput)) {
            return 0;
        }

        int availableCursorSpace = Math.min(cursor.getMaxStackSize(), guiStackLimit) - cursor.getAmount();
        if (availableCursorSpace <= 0) {
            return 0;
        }

        return Math.min(rightClick ? 1 : currentOutput.getAmount(), availableCursorSpace);
    }

    public ItemStack takeOutputFromSlot(Player player, int guiSlot, int requestedAmount) {
        Integer entitySlot = slotMapping.get(guiSlot);
        if (entitySlot == null) {
            return null;
        }
        return blockEntity.takeOutputSlotPortionForDelivery(player, entitySlot, requestedAmount);
    }

    public void applyOutputExperienceReward(Player player, ItemStack result) {
        blockEntity.awardUsedRecipes(player);
        plugin.callCookingPotExperienceEvent(player, result, 0.0D);
    }

    public void deliverOutputToPlayer(InventoryClickEvent event, Player player, ItemStack meal) {
        if (event.isShiftClick()) {
            ItemDelivery.giveOrDrop(player, meal);
            return;
        }

        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            player.setItemOnCursor(meal);
            return;
        }

        int space = Math.min(cursor.getMaxStackSize(), event.getView().getTopInventory().getMaxStackSize())
                - cursor.getAmount();
        if (cursor.isSimilar(meal) && space > 0) {
            int moved = Math.min(space, meal.getAmount());
            cursor.setAmount(cursor.getAmount() + moved);
            meal.setAmount(meal.getAmount() - moved);
            player.setItemOnCursor(cursor);
            if (meal.getAmount() <= 0) {
                return;
            }
        }

        ItemDelivery.giveOrDrop(player, meal);
    }
}
