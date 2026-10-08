package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.api.gui.GuiTakeAmounts;
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
        int containerMaxStack = event.getView().getTopInventory().getMaxStackSize();
        return resolveTake(event.isShiftClick(), event.isRightClick(), currentOutput.getAmount(),
                cursorEmpty ? 0 : cursor.getAmount(), cursorEmpty,
                !cursorEmpty && cursor.isSimilar(currentOutput),
                currentOutput.getMaxStackSize(), containerMaxStack);
    }

    /**
     * How much this click takes, from the click shape and the amounts alone, so the answer can be asserted
     * without a server. The limit is the smaller of the item's own stack size and the container's, which is
     * what this pot has always clamped a take to.
     *
     * Similar stacks share one maximum stack size, so the caller passes the stored item's and the cursor's own
     * is never read: the branches below used to read the cursor's, and for items that are similar by definition
     * the two numbers are the same.
     */
    static int resolveTake(boolean shiftClick, boolean rightClick, int stored, int cursorAmount,
                           boolean cursorEmpty, boolean cursorSimilar, int itemMaxStack, int containerMaxStack) {
        if (stored <= 0) {
            return 0;
        }
        int limit = Math.min(itemMaxStack, containerMaxStack);
        GuiTakeAmounts.Click click = shiftClick ? GuiTakeAmounts.Click.SHIFT
                : rightClick ? GuiTakeAmounts.Click.RIGHT : GuiTakeAmounts.Click.LEFT;
        return GuiTakeAmounts.take(click, stored, cursorAmount, cursorEmpty, cursorSimilar, limit);
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
