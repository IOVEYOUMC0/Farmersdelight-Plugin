package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Owns the "fill this cooking pot straight from the recipe detail GUI" flow. Pure gameplay logic with no
// inventory rendering: it is invoked from a single onClick path, so it was a safe standalone block to pull
// out of RecipeViewGui. Only depends on the plugin, the pot location and the resolved recipe group id --
// no back-reference to the GUI -- which keeps it easily testable. The player owning the inventory is passed
// in as a parameter because no GUI state is needed here.
final class CookingPotFiller {

    private final FarmersDelightPlugin plugin;
    private final Location cookingPotLocation;
    private final String recipeGroupId;

    CookingPotFiller(FarmersDelightPlugin plugin, Location cookingPotLocation, String recipeGroupId) {
        this.plugin = plugin;
        this.cookingPotLocation = cookingPotLocation;
        this.recipeGroupId = recipeGroupId;
    }

    FillResult fillFromInventory(Player player, boolean fillAll, String selectedRecipeId) {
        if (cookingPotLocation == null || selectedRecipeId == null) {
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }
        // This runs on the player's Folia region thread (inventory click). All the pot-side work below
        // (getBlockEntity, hopper inserts, markActive/checkHeatSource/saveBlockEntityData) touches the
        // cooking-pot block entity, which must happen on the pot's own region. If the pot is in a
        // different region (player teleported while the GUI stayed open), touching it here is a
        // cross-region access -- skip safely rather than throw. Always owned on Paper.
        if (!plugin.scheduler().isOwnedByCurrentRegion(cookingPotLocation)) {
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }
        CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(recipeGroupId, selectedRecipeId);
        var entity = CookingPotBlockBehavior.getBlockEntity(cookingPotLocation);
        if (recipe == null || entity == null) {
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }

        debug("start recipe=" + recipe.getId() + ", fillAll=" + fillAll
                + ", before=" + summarizePot(entity));

        List<InventorySelection> selected = selectRecipeItemsFromInventory(player, recipe, entity, fillAll);
        boolean hasRequiredContainer = !recipe.needsContainer() || sameRecipeItem(recipe.getContainer(), entity.getContainerItem());
        InventorySelection selectedContainer = !hasRequiredContainer && entity.getLayout().containerSlots().length > 0
                ? selectRecipeContainerFromInventory(player, recipe, selected)
                : null;

        if (selected.isEmpty() && selectedContainer == null) {
            if (missingIngredients(recipe, getCurrentInputItems(entity)).isEmpty() && hasRequiredContainer) {
                activateAfterFill(entity);
                debug("already matched recipe=" + recipe.getId() + ", after=" + summarizePot(entity));
                return FillResult.returnToPot(FillButtonState.FILLED);
            }
            debug("missing recipe=" + recipe.getId() + ", selected=0, hasContainer=" + hasRequiredContainer
                    + ", current=" + summarizePot(entity));
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }

        int movedCount = 0;

        for (InventorySelection selection : selected) {
            ItemStack source = player.getInventory().getItem(selection.slot());
            if (source == null || source.getType().isAir()) {
                continue;
            }
            ItemStack moved = source.clone();
            moved.setAmount(1);
            ItemStack leftover = CookingPotBlockBehavior.insertIngredientSpreadLikeHopper(cookingPotLocation, moved);
            if (leftover != null && !leftover.getType().isAir()) {
                debug("rollback ingredient leftover=" + summarizeItem(leftover)
                        + ", recipe=" + recipe.getId() + ", after=" + summarizePot(entity));
                player.updateInventory();
                return FillResult.stay(FillButtonState.INVENTORY_FULL);
            }
            debitInventorySlot(player, selection.slot());
            movedCount++;
        }
        if (selectedContainer != null) {
            ItemStack source = player.getInventory().getItem(selectedContainer.slot());
            if (source == null || source.getType().isAir()) {
                debug("rollback missing container source recipe=" + recipe.getId()
                        + ", after=" + summarizePot(entity));
                player.updateInventory();
                return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
            }
            ItemStack container = source.clone();
            container.setAmount(1);
            ItemStack leftover = CookingPotBlockBehavior.insertContainerLikeHopper(cookingPotLocation, container);
            if (leftover != null && !leftover.getType().isAir()) {
                debug("rollback container leftover=" + summarizeItem(leftover)
                        + ", recipe=" + recipe.getId() + ", after=" + summarizePot(entity));
                player.updateInventory();
                return FillResult.stay(FillButtonState.INVENTORY_FULL);
            }
            debitInventorySlot(player, selectedContainer.slot());
            movedCount++;
        }
        boolean complete = missingIngredients(recipe, getCurrentInputItems(entity)).isEmpty()
                && (!recipe.needsContainer() || sameRecipeItem(recipe.getContainer(), entity.getContainerItem()));
        if (!complete) {
            debug("partial recipe=" + recipe.getId() + ", moved=" + movedCount
                    + ", after=" + summarizePot(entity));
            player.updateInventory();
            return FillResult.stay(FillButtonState.MISSING_INGREDIENTS);
        }
        activateAfterFill(entity);
        debug("filled recipe=" + recipe.getId() + ", moved=" + movedCount
                + ", after=" + summarizePot(entity));
        player.updateInventory();
        return FillResult.returnToPot(FillButtonState.FILLED);
    }

    private void debitInventorySlot(Player player, int slot) {
        ItemStack source = player.getInventory().getItem(slot);
        if (source == null || source.getType().isAir()) {
            return;
        }
        int remaining = source.getAmount() - 1;
        if (remaining <= 0) {
            player.getInventory().setItem(slot, null);
            return;
        }
        source.setAmount(remaining);
        player.getInventory().setItem(slot, source);
    }

    private void activateAfterFill(CookingPotBlockEntity entity) {
        if (entity == null || cookingPotLocation == null || cookingPotLocation.getWorld() == null) {
            return;
        }
        World world = cookingPotLocation.getWorld();
        TickManager tickManager = plugin.getTickManager();
        if (tickManager != null && entity.hasStoredContents()) {
            tickManager.markActive(world, entity.getPosKey(), TickManager.BlockType.COOKING_POT);
        }
        CookingPotBlockBehavior behavior = CookingPotBlockBehavior.getBlockBehavior(cookingPotLocation);
        if (behavior != null) {
            entity.setHasHeatSource(behavior.checkHeatSource(entity.getPos(), world));
        }
        CookingPotBlockBehavior.saveBlockEntityData(world, entity.getPosKey());
    }

    private void debug(String message) {
        if (plugin.isDebugEnabled("cooking_pot")) {
            plugin.getLogger().info(I18n.formatConsole("debug.cooking_pot_fill", "message", message));
        }
    }

    private String summarizePot(CookingPotBlockEntity entity) {
        if (entity == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>();
        for (int slot : entity.getLayout().inputSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                parts.add("i" + slot + "=" + summarizeItem(item));
            }
        }
        for (int slot : entity.getLayout().containerSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                parts.add("c" + slot + "=" + summarizeItem(item));
            }
        }
        for (int slot : entity.getLayout().pendingOutputSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                parts.add("p" + slot + "=" + summarizeItem(item));
            }
        }
        for (int slot : entity.getLayout().outputSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                parts.add("o" + slot + "=" + summarizeItem(item));
            }
        }
        return parts.isEmpty() ? "empty" : String.join(",", parts);
    }

    private String summarizeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "empty";
        }
        String customId = ItemUtils.getCustomItemId(item);
        String id = customId != null ? customId : item.getType().name();
        return id + "x" + item.getAmount();
    }

    private List<ItemStack> getCurrentInputItems(CookingPotBlockEntity entity) {
        if (entity == null) {
            return List.of();
        }
        List<ItemStack> items = new ArrayList<>();
        for (int slot : entity.getLayout().inputSlots()) {
            ItemStack item = entity.getInventorySlot(slot);
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        return items;
    }

    private List<RecipeIngredient> missingIngredients(CookingPotRecipe recipe, List<ItemStack> currentItems) {
        if (recipe == null) {
            return List.of();
        }
        List<ItemStack> available = new ArrayList<>();
        if (currentItems != null) {
            for (ItemStack item : currentItems) {
                if (item != null && !item.getType().isAir()) {
                    available.add(item.clone());
                }
            }
        }

        List<RecipeIngredient> missing = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            int matchIndex = findMatchingAvailableStack(available, ingredient);
            if (matchIndex < 0) {
                missing.add(ingredient);
                continue;
            }
            ItemStack matched = available.get(matchIndex);
            matched.setAmount(matched.getAmount() - 1);
            if (matched.getAmount() <= 0) {
                available.remove(matchIndex);
            }
        }
        return missing;
    }

    private int findMatchingAvailableStack(List<ItemStack> available, RecipeIngredient ingredient) {
        for (int i = 0; i < available.size(); i++) {
            ItemStack item = available.get(i);
            if (item != null && item.getAmount() > 0 && plugin.getCookingPotRecipes().matchesIngredient(item, ingredient)) {
                return i;
            }
        }
        return -1;
    }

    private List<InventorySelection> selectRecipeItemsFromInventory(Player player, CookingPotRecipe recipe,
                                                                    CookingPotBlockEntity entity, boolean fillAll) {
        List<InventorySelection> available = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            for (int amount = 0; amount < item.getAmount(); amount++) {
                ItemStack one = item.clone();
                one.setAmount(1);
                available.add(new InventorySelection(slot, one));
            }
        }

        List<InventorySelection> selected = new ArrayList<>();
        List<RecipeIngredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || entity == null) {
            return selected;
        }

        ItemStack[] simulatedSlots = createInputSlotSimulation(entity);
        boolean firstPass = true;
        do {
            int selectedBefore = selected.size();
            List<RecipeIngredient> needed = firstPass
                    ? missingIngredients(recipe, Arrays.asList(simulatedSlots))
                    : ingredients;
            if (needed.isEmpty()) {
                if (!fillAll) {
                    break;
                }
                needed = ingredients;
            }

            for (RecipeIngredient ingredient : needed) {
                int matchIndex = findMatchingAvailableIngredient(available, ingredient, simulatedSlots);
                if (matchIndex < 0) {
                    return selected;
                }
                InventorySelection selection = available.remove(matchIndex);
                insertIntoSimulatedSlots(simulatedSlots, selection.item());
                selected.add(selection);
            }

            firstPass = false;
            if (!fillAll || selected.size() == selectedBefore) {
                break;
            }
        } while (true);
        return selected;
    }

    private ItemStack[] createInputSlotSimulation(CookingPotBlockEntity entity) {
        int[] inputSlots = entity.getLayout().inputSlots();
        ItemStack[] simulated = new ItemStack[inputSlots.length];
        for (int i = 0; i < inputSlots.length; i++) {
            simulated[i] = entity.getInventorySlot(inputSlots[i]);
        }
        return simulated;
    }

    private int findMatchingAvailableIngredient(List<InventorySelection> available, RecipeIngredient ingredient,
                                                ItemStack[] simulatedSlots) {
        for (int i = 0; i < available.size(); i++) {
            ItemStack item = available.get(i).item();
            if (plugin.getCookingPotRecipes().matchesIngredient(item, ingredient) && canInsertIntoSimulatedSlots(simulatedSlots, item)) {
                return i;
            }
        }
        return -1;
    }

    private boolean canInsertIntoSimulatedSlots(ItemStack[] slots, ItemStack item) {
        if (slots == null || item == null || item.getType().isAir()) {
            return false;
        }
        for (ItemStack slotItem : slots) {
            if (slotItem != null && !slotItem.getType().isAir() && slotItem.isSimilar(item)
                    && slotItem.getAmount() < slotItem.getMaxStackSize()) {
                return true;
            }
        }
        for (ItemStack slotItem : slots) {
            if (slotItem == null || slotItem.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private void insertIntoSimulatedSlots(ItemStack[] slots, ItemStack item) {
        if (slots == null || item == null || item.getType().isAir()) {
            return;
        }
        int target = -1;
        int amount = Integer.MAX_VALUE;
        for (int i = 0; i < slots.length; i++) {
            ItemStack slotItem = slots[i];
            if (slotItem != null && !slotItem.getType().isAir()
                    && (!slotItem.isSimilar(item) || slotItem.getAmount() >= slotItem.getMaxStackSize())) {
                continue;
            }
            int count = slotItem == null || slotItem.getType().isAir() ? 0 : slotItem.getAmount();
            if (count < amount) {
                amount = count;
                target = i;
            }
        }
        if (target >= 0) {
            ItemStack slotItem = slots[target];
            if (slotItem == null || slotItem.getType().isAir()) {
                slotItem = item.clone();
                slotItem.setAmount(1);
                slots[target] = slotItem;
            } else {
                slotItem.setAmount(slotItem.getAmount() + 1);
            }
        }
    }

    private InventorySelection selectRecipeContainerFromInventory(Player player, CookingPotRecipe recipe,
                                                                  List<InventorySelection> reservedItems) {
        if (recipe == null || !recipe.needsContainer()) {
            return null;
        }
        ItemStack required = recipe.getContainer();
        if (required == null || required.getType().isAir()) {
            return null;
        }
        Map<Integer, Integer> reservedBySlot = new HashMap<>();
        if (reservedItems != null) {
            for (InventorySelection selection : reservedItems) {
                reservedBySlot.merge(selection.slot(), 1, Integer::sum);
            }
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (item.getAmount() <= reservedBySlot.getOrDefault(slot, 0)) {
                continue;
            }
            if (sameRecipeItem(required, item)) {
                ItemStack selected = item.clone();
                selected.setAmount(1);
                return new InventorySelection(slot, selected);
            }
        }
        return null;
    }

    private boolean sameRecipeItem(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.getType().isAir() || actual.getType().isAir()) {
            return false;
        }
        String expectedId = ItemUtils.getCustomItemId(expected);
        String actualId = ItemUtils.getCustomItemId(actual);
        if (expectedId != null || actualId != null) {
            return expectedId != null && expectedId.equals(actualId);
        }
        return expected.getType() == actual.getType();
    }

    record InventorySelection(int slot, ItemStack item) {}

    enum FillButtonState {
        READY("fill", "gui.recipe.fill_ready"),
        FILLED("fill-success", "gui.recipe.ingredients_filled"),
        MISSING_INGREDIENTS("fill-missing", "gui.recipe.missing_ingredients"),
        INVENTORY_FULL("fill-inventory-full", "gui.recipe.inventory_full");

        private final String itemKey;
        private final String messageKey;

        FillButtonState(String itemKey, String messageKey) {
            this.itemKey = itemKey;
            this.messageKey = messageKey;
        }

        String itemKey() {
            return itemKey;
        }

        Map<String, String> placeholders(Player player) {
            return Map.of("status", I18n.get(messageKey, player));
        }
    }

    record FillResult(boolean returnToPot, FillButtonState buttonState) {
        static FillResult returnToPot(FillButtonState buttonState) {
            return new FillResult(true, buttonState);
        }

        static FillResult stay(FillButtonState buttonState) {
            return new FillResult(false, buttonState);
        }
    }
}
