package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
            consumeIngredientsInternal(recipe, world, blockLoc);
        }
        entity.syncWorldlyContainer();
    }

    // Caller must hold the entity's inventory monitor (used both by consumeIngredients and by the
    // finish-cooking path that already owns the lock).
    void consumeIngredientsInternal(CookingPotRecipe recipe, World world, Location blockLoc) {
        if (recipe == null) return;
        ItemStack[] inventory = entity.getInventoryInternal();
        int[] slots = entity.getLayout().inputSlots();

        // For each recipe ingredient, charge ONE unit of consumption to a slot — preferring slots not yet used
        // in this cook cycle (Forge's "shrink each filled slot by 1" behavior for the exact case), falling back
        // to re-using a slot with remaining stacked amount (covers the consolidated case: one slot with 2 cocoa
        // satisfying a 2-cocoa recipe). Without this, a recipe with the same ingredient listed twice
        // (e.g. 2x cocoa) would greedily pull both units from the first matching slot, leaving the second
        // identical slot untouched: e.g. [4 cocoa, 4 cocoa, ...] -> after 2 cooks: [empty, 4 cocoa, ...].
        int[] consume = new int[slots.length];
        List<ItemStack> remainders = new ArrayList<>();

        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            int idx = pickConsumptionSlot(inventory, slots, consume, ingredient, true);
            if (idx < 0) {
                idx = pickConsumptionSlot(inventory, slots, consume, ingredient, false);
            }
            if (idx < 0) {
                // canCook should have prevented this; defensive no-op.
                continue;
            }
            ItemStack slotItem = inventory[slots[idx]];
            ItemStack remainder = getCraftingRemainder(slotItem);
            if (remainder != null && !remainder.getType().isAir()) {
                remainders.add(remainder);
            }
            consume[idx]++;
        }

        for (int idx = 0; idx < slots.length; idx++) {
            if (consume[idx] == 0) continue;
            int i = slots[idx];
            ItemStack slotItem = inventory[i];
            if (slotItem == null) continue;
            int newAmount = slotItem.getAmount() - consume[idx];
            if (newAmount <= 0) {
                entity.setSlot(i, null);
            } else {
                slotItem.setAmount(newAmount);
                entity.bumpInventoryVersion();
            }
        }

        if (world != null && blockLoc != null && !remainders.isEmpty()) {
            ejectRemainders(world, blockLoc, remainders);
        }
    }

    private int pickConsumptionSlot(ItemStack[] inventory, int[] slots, int[] consume, RecipeIngredient ingredient, boolean preferFresh) {
        for (int idx = 0; idx < slots.length; idx++) {
            if (preferFresh && consume[idx] > 0) continue;
            int slot = slots[idx];
            if (entity.isValidSlot(slot)) continue;
            ItemStack slotItem = inventory[slot];
            if (slotItem == null || slotItem.getType().isAir()) continue;
            if (consume[idx] >= slotItem.getAmount()) continue;
            if (!matchesIngredient(slotItem, ingredient)) continue;
            return idx;
        }
        return -1;
    }

    private ItemStack getCraftingRemainder(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        ContainerReturnConfig config = plugin == null ? null : plugin.getContainerReturnConfig();
        if (customId != null && config != null) {
            return config.getReturnItem(customId, 1);
        }

        Material remainderType = item.getType().getCraftingRemainingItem();
        if (remainderType != null && !remainderType.isAir()) {
            return new ItemStack(remainderType, 1);
        }

        // Ingredients whose container vanilla does not expose as a crafting remainder (fish buckets, stews,
        // potions). Consulted only after the real remainder, matching the mod's ordering.
        ItemStack override = CookingPotIngredientRemainders.getRemainder(item, 1);
        if (override != null) {
            return override;
        }

        return switch (item.getType()) {
            case MILK_BUCKET, WATER_BUCKET, LAVA_BUCKET -> new ItemStack(Material.BUCKET, 1);
            case HONEY_BOTTLE -> new ItemStack(Material.GLASS_BOTTLE, 1);
            default -> null;
        };
    }

    private boolean matchesIngredient(ItemStack item, RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            if (!ItemUtils.matchesItemId(item, itemIngredient.key())) {
                return false;
            }
            if (itemIngredient.nbt() == null) {
                return true;
            }
            ItemStack expected = RecipeItemCodec.itemFromBase64(itemIngredient.nbt());
            return expected != null && expected.isSimilar(item);
        }

        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            for (RecipeIngredient option : choiceIngredient.options()) {
                if (matchesIngredient(item, option)) {
                    return true;
                }
            }
            return false;
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            if (tagIngredient.excludedItems().stream().anyMatch(excluded -> ItemUtils.matchesItemId(item, excluded))) {
                return false;
            }

            Set<String> itemTags = ItemUtils.getItemTagIds(item);
            if (itemTags.contains(tagIngredient.key().toString())) {
                boolean blocked = tagIngredient.excludedTags().stream()
                        .map(Key::toString)
                        .anyMatch(itemTags::contains);
                return !blocked;
            }

            Key itemKey = Key.of("minecraft:" + item.getType().name().toLowerCase(java.util.Locale.ROOT));
            String itemKeyId = itemKey.toString();
            // Route the vanilla-tag membership test through the recipe manager's memoized lookup (O(1)
            // contains) instead of streaming CraftEngine's tag list fresh on every probe.
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            var recipes = plugin == null ? null : plugin.getCookingPotRecipes();
            if (recipes == null) return false;
            boolean matchesBase = recipes.getVanillaItemIdsByTag(tagIngredient.key()).contains(itemKeyId)
                    || ItemUtils.matchesVanillaItemTag(item, tagIngredient.key(),
                    tagIngredient.excludedItems(), tagIngredient.excludedTags());
            if (!matchesBase) {
                return false;
            }
            for (Key excludedTag : tagIngredient.excludedTags()) {
                if (recipes.getVanillaItemIdsByTag(excludedTag).contains(itemKeyId)) {
                    return false;
                }
            }
            return true;
        }

        return false;
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
