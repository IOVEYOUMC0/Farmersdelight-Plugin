package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class CookingPotCraftingHandler {

    private final CookingPotBlockEntity entity;

    public CookingPotCraftingHandler(CookingPotBlockEntity entity) {
        this.entity = entity;
    }

    // Lock-safe entry: takes the entity's inventory monitor, consumes the recipe's ingredients, then
    // flags the container dirty outside the monitor.
    public void consumeIngredients(CookingPotRecipe recipe, World world, Location blockLoc) {
        if (recipe == null) return;
        synchronized (entity.getLock()) {
            Consumption consumption = prepareConsumption(recipe);
            if (consumption == null) return;
            consumeIngredientsInternal(consumption, world, blockLoc);
        }
        entity.syncWorldlyContainer();
    }

    // Caller must hold the entity's inventory monitor (used both by consumeIngredients and by the
    // finish-cooking path that already owns the lock).
    Consumption prepareConsumption(CookingPotRecipe recipe) {
        if (recipe == null) return null;
        ItemStack[] inventory = entity.getInventoryInternal();
        int[] slots = entity.getLayout().inputSlots();

        List<ItemStack> available = new ArrayList<>(slots.length);
        for (int slot : slots) {
            available.add(inventory[slot]);
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        var recipes = plugin == null ? null : plugin.getCookingPotRecipes();
        if (recipes == null) return null;
        // Use each filled slot once when possible, then allow stacked units for overlapping ingredients.
        int[] assignment = IngredientMatching.assignIngredients(
                recipe.getIngredients(), available, recipes::matchesIngredient,
                item -> item == null || item.getType().isAir() || item.getAmount() <= 0 ? 0 : 1);
        if (assignment == null) {
            assignment = IngredientMatching.assignIngredients(
                    recipe.getIngredients(), available, recipes::matchesIngredient,
                    item -> item == null || item.getType().isAir() ? 0 : item.getAmount());
        }
        if (assignment == null) return null;

        int[] consume = new int[slots.length];
        List<ItemStack> remainders = new ArrayList<>();

        for (int idx : assignment) {
            ItemStack slotItem = inventory[slots[idx]];
            ItemStack remainder = getCraftingRemainder(slotItem, recipe);
            if (remainder != null && !remainder.getType().isAir()) {
                remainders.add(remainder);
            }
            consume[idx]++;
        }
        return new Consumption(consume, remainders);
    }

    record Consumption(int[] amounts, List<ItemStack> remainders) {}

    // Preparation and application must share the inventory lock with result insertion.
    void consumeIngredientsInternal(Consumption consumption, World world, Location blockLoc) {
        ItemStack[] inventory = entity.getInventoryInternal();
        int[] slots = entity.getLayout().inputSlots();
        int[] consume = consumption.amounts();
        for (int idx = 0; idx < slots.length; idx++) {
            if (consume[idx] == 0) continue;
            int i = slots[idx];
            ItemStack slotItem = inventory[i];
            int newAmount = slotItem.getAmount() - consume[idx];
            if (newAmount <= 0) {
                entity.setSlot(i, null);
            } else {
                slotItem.setAmount(newAmount);
                entity.bumpInventoryVersion();
            }
        }

        if (world != null && blockLoc != null && !consumption.remainders().isEmpty()) {
            ejectRemainders(world, blockLoc, consumption.remainders());
        }
    }

    /**
     * What the consumed ingredient leaves behind when cooking finishes. The shared resolver (ItemUtils) covers,
     * in order: the container-returns map, the item's own CE craft-remainder for this recipe, its use-remainder
     * component, the vanilla crafting remainder of its material and the bucket/bottle fallback; a CE item is no
     * longer dropped just because it is absent from container-returns. The ingredient table below only adds the
     * cooking-pot specific entries vanilla declares no remainder for (fish buckets, stews, potions).
     */
    private ItemStack getCraftingRemainder(ItemStack item, CookingPotRecipe recipe) {
        ItemStack remainder = ItemUtils.craftingRemainderOf(item, recipe == null ? null : recipe.getId());
        if (remainder != null && !remainder.getType().isAir()) {
            return remainder;
        }
        return CookingPotIngredientRemainders.getRemainder(item, 1);
    }

    private void ejectRemainders(World world, Location blockLoc, List<ItemStack> remainders) {
        BlockFace facing = CustomBlockUtils.getFacing(blockLoc.getBlock());
        BlockFace eject = counterClockwise(facing);
        if (eject == null) {
            Location dropLoc = blockLoc.clone().add(0.5, 0.7, 0.5);
            for (ItemStack r : remainders) {
                world.dropItemNaturally(dropLoc, r);
            }
            return;
        }
        double dx = eject.getModX();
        double dz = eject.getModZ();
        Location ejectLoc = blockLoc.clone().add(0.5 + dx * 0.25, 0.7, 0.5 + dz * 0.25);
        Vector velocity = new Vector(dx * 0.08, 0.25, dz * 0.08);
        for (ItemStack r : remainders) {
            Item entity = world.dropItem(ejectLoc, r);
            entity.setVelocity(velocity);
        }
    }

    private static BlockFace counterClockwise(BlockFace facing) {
        if (facing == null) return null;
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST  -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST  -> BlockFace.NORTH;
            default    -> null;
        };
    }
}
