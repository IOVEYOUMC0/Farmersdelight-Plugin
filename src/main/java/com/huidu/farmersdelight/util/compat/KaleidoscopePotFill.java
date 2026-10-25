package com.huidu.farmersdelight.util.compat;

import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.recipe.PotIngredientFillPlan;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a recorded recipe item's ingredients to a cooking pot. The decision is made by
 * `PotIngredientFillPlan` before anything is written, and the pot is written before the player inventory so
 * a disappearing stack can never leave a half-filled pot.
 */
public final class KaleidoscopePotFill {

    private static final int OFFHAND = -1;

    private KaleidoscopePotFill() {
    }

    /**
     * Fills the pot when the plan allows it, otherwise reports why. Returns null when the caller should fall
     * through to the normal interaction (the player is not on the region that owns their inventory).
     */
    public static PotIngredientFillPlan.Result fill(Player player, ItemStack recipeItem, CookingPotBlockEntity pot) {
        if (player == null || pot == null || !Bukkit.isOwnedByCurrentRegion(player)) {
            return null;
        }
        List<String> requested = KaleidoscopeCompat.ingredients(recipeItem);
        if (requested.isEmpty()) {
            return PotIngredientFillPlan.Result.INVALID_RECIPE;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        List<PotIngredientFillPlan.PlayerSlot> playerSlots = new ArrayList<>(storage.length + 1);
        for (int i = 0; i < storage.length; i++) {
            ItemStack item = storage[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            playerSlots.add(new PotIngredientFillPlan.PlayerSlot(
                    i, ItemUtils.resolveItemId(item), item.getAmount()));
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            playerSlots.add(new PotIngredientFillPlan.PlayerSlot(
                    OFFHAND, ItemUtils.resolveItemId(offhand), offhand.getAmount()));
        }

        int[] inputSlots = pot.getLayout().inputSlots();
        List<String> potIds = new ArrayList<>(inputSlots.length);
        for (int slot : inputSlots) {
            potIds.add(ItemUtils.resolveItemId(pot.getInventorySlot(slot)));
        }
        PotIngredientFillPlan.Plan plan = PotIngredientFillPlan.prepare(requested, playerSlots, potIds);
        if (plan.result() != PotIngredientFillPlan.Result.FILLED) {
            return plan.result();
        }

        List<ItemStack> units = new ArrayList<>(plan.moves().size());
        for (PotIngredientFillPlan.Move move : plan.moves()) {
            ItemStack current = itemAt(inventory, playerSlots.get(move.playerIndex()).slot());
            if (current == null || current.getType().isAir()) {
                // The inventory changed between planning and applying; report missing instead of writing a
                // partially filled pot.
                return PotIngredientFillPlan.Result.MISSING_ITEMS;
            }
            ItemStack unit = current.clone();
            unit.setAmount(1);
            units.add(unit);
        }
        for (int i = 0; i < plan.moves().size(); i++) {
            pot.setInventorySlot(inputSlots[plan.moves().get(i).potIndex()], units.get(i));
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            Map<Integer, Integer> debits = new HashMap<>();
            for (PotIngredientFillPlan.Move move : plan.moves()) {
                debits.merge(playerSlots.get(move.playerIndex()).slot(), 1, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> entry : debits.entrySet()) {
                ItemStack current = itemAt(inventory, entry.getKey());
                if (current == null || current.getType().isAir()) {
                    continue;
                }
                int left = current.getAmount() - entry.getValue();
                if (left <= 0) {
                    setItemAt(inventory, entry.getKey(), null);
                } else {
                    ItemStack updated = current.clone();
                    updated.setAmount(left);
                    setItemAt(inventory, entry.getKey(), updated);
                }
            }
        }
        return PotIngredientFillPlan.Result.FILLED;
    }

    /** The message key for a failed or finished fill; the keys live in the two plugin locale files. */
    public static String messageKey(PotIngredientFillPlan.Result result) {
        return switch (result) {
            case FILLED -> "compat.kaleidoscope.auto_fill.filled";
            case ALREADY_FILLED -> "compat.kaleidoscope.auto_fill.already_filled";
            case MISSING_ITEMS -> "compat.kaleidoscope.auto_fill.missing_items";
            case NO_SPACE -> "compat.kaleidoscope.auto_fill.no_space";
            case OTHER_INGREDIENTS -> "compat.kaleidoscope.auto_fill.other_ingredients";
            case INVALID_RECIPE -> "compat.kaleidoscope.auto_fill.invalid_recipe";
        };
    }

    private static ItemStack itemAt(PlayerInventory inventory, int slot) {
        return slot == OFFHAND ? inventory.getItemInOffHand() : inventory.getItem(slot);
    }

    private static void setItemAt(PlayerInventory inventory, int slot, ItemStack item) {
        if (slot == OFFHAND) {
            inventory.setItemInOffHand(item);
        } else {
            inventory.setItem(slot, item);
        }
    }
}
