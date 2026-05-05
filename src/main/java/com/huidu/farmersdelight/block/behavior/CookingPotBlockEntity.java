package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CookingPotBlockEntity {

    private static final int FIRST_INGREDIENT_SLOT = 0;

    private final BlockPosKey posKey;
    private final ItemStack[] inventory = new ItemStack[CookingPotBlockBehavior.INVENTORY_SIZE];
    private final AtomicInteger cookingProgress = new AtomicInteger(0);
    private final AtomicInteger cookingDuration = new AtomicInteger(200);
    private final AtomicBoolean hasHeatSource = new AtomicBoolean(false);
    private final AtomicReference<CookingPotRecipe> currentRecipe = new AtomicReference<>(null);
    private final AtomicReference<String> lastRecipeId = new AtomicReference<>(null);
    private final AtomicReference<String> lastRecipeFingerprint = new AtomicReference<>(null);
    private final AtomicReference<ItemStack> mealContainerStack = new AtomicReference<>(null);
    private final Object inventoryLock = new Object();
    private final Object cookingLock = new Object();

    public CookingPotBlockEntity(BlockPosKey posKey) {
        this.posKey = posKey;
    }

    public BlockPosKey getPosKey() {
        return posKey;
    }

    public BlockPos getPos() {
        return posKey.toBlockPos();
    }

    public ItemStack[] getInventory() {
        synchronized (inventoryLock) {
            return Arrays.copyOf(inventory, inventory.length);
        }
    }
    
    ItemStack[] getInventoryInternal() {
        return inventory;
    }
    
    Object getLock() {
        return inventoryLock;
    }

    public ItemStack getInventorySlot(int slot) {
        synchronized (inventoryLock) {
            return copyOrNull(inventory[slot]);
        }
    }

    public void setInventorySlot(int slot, ItemStack item) {
        synchronized (inventoryLock) {
            inventory[slot] = copyOrNull(item);
        }
    }

    public List<ItemStack> getIngredientSlots() {
        synchronized (inventoryLock) {
            return getIngredientSlotsInternal();
        }
    }

    private ItemStack copyOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }

    public ItemStack getContainerItem() {
        synchronized (inventoryLock) {
            return getContainerItemInternal();
        }
    }

    public ItemStack insertIngredientStack(ItemStack item) {
        synchronized (inventoryLock) {
            return insertIntoSlots(item, FIRST_INGREDIENT_SLOT, CookingPotBlockBehavior.SLOT_MEAL_DISPLAY - 1);
        }
    }

    public ItemStack insertContainerStack(ItemStack item) {
        synchronized (inventoryLock) {
            return insertIntoSlot(item, CookingPotBlockBehavior.SLOT_CONTAINER);
        }
    }

    public ItemStack getMealDisplayItem() {
        synchronized (inventoryLock) {
            return copyOrNull(inventory[CookingPotBlockBehavior.SLOT_OUTPUT]);
        }
    }

    public void setMealDisplayItem(ItemStack item) {
        synchronized (inventoryLock) {
            addItemToSlot(CookingPotBlockBehavior.SLOT_OUTPUT, item);
        }
    }

    public ItemStack getPendingOutputItem() {
        synchronized (inventoryLock) {
            return copyOrNull(inventory[CookingPotBlockBehavior.SLOT_MEAL_DISPLAY]);
        }
    }

    public boolean hasPendingOutput() {
        synchronized (inventoryLock) {
            ItemStack item = inventory[CookingPotBlockBehavior.SLOT_MEAL_DISPLAY];
            return item != null && !item.getType().isAir();
        }
    }

    public boolean hasInput() {
        synchronized (inventoryLock) {
            for (int i = 0; i < 6; i++) {
                ItemStack item = inventory[i];
                if (item != null && !item.getType().isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean consumeContainer() {
        synchronized (inventoryLock) {
            ItemStack container = inventory[CookingPotBlockBehavior.SLOT_CONTAINER];
            if (container == null) return false;
            
            if (container.getAmount() > 1) {
                container.setAmount(container.getAmount() - 1);
            } else {
                inventory[CookingPotBlockBehavior.SLOT_CONTAINER] = null;
            }
            return true;
        }
    }

    public void consumeIngredients(CookingPotRecipe recipe, World world, Location blockLoc) {
        if (recipe == null) return;

        synchronized (inventoryLock) {
            consumeIngredientsInternal(recipe, world, blockLoc);
        }
    }

    private ItemStack storeRemainderInIngredientSlots(ItemStack remainder) {
        ItemStack pending = remainder.clone();
        for (int i = 0; i < CookingPotBlockBehavior.SLOT_MEAL_DISPLAY; i++) {
            ItemStack slotItem = inventory[i];
            if (slotItem == null || slotItem.getType().isAir()) {
                inventory[i] = pending;
                return null;
            }

            if (!slotItem.isSimilar(pending)) {
                continue;
            }

            int space = slotItem.getMaxStackSize() - slotItem.getAmount();
            if (space <= 0) {
                continue;
            }

            int toMove = Math.min(space, pending.getAmount());
            slotItem.setAmount(slotItem.getAmount() + toMove);
            if (toMove == pending.getAmount()) {
                return null;
            }

            pending.setAmount(pending.getAmount() - toMove);
        }
        return pending;
    }

    private ItemStack insertIntoSlots(ItemStack item, int startSlot, int endSlot) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        ItemStack pending = item.clone();
        for (int i = startSlot; i <= endSlot; i++) {
            ItemStack existing = inventory[i];
            if (existing == null || existing.getType().isAir() || !isSimilarIgnoringStoredExperience(existing, pending)) {
                continue;
            }

            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, pending.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            pending.setAmount(pending.getAmount() - moved);
            if (pending.getAmount() <= 0) {
                return null;
            }
        }

        for (int i = startSlot; i <= endSlot; i++) {
            ItemStack existing = inventory[i];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }

            inventory[i] = pending.clone();
            return null;
        }

        return pending;
    }

    private ItemStack insertIntoSlot(ItemStack item, int slot) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        ItemStack existing = inventory[slot];
        if (existing == null || existing.getType().isAir()) {
            inventory[slot] = item.clone();
            return null;
        }

        if (!isSimilarIgnoringStoredExperience(existing, item)) {
            return item.clone();
        }

        int space = existing.getMaxStackSize() - existing.getAmount();
        if (space <= 0) {
            return item.clone();
        }

        ItemStack pending = item.clone();
        int moved = Math.min(space, pending.getAmount());
        existing.setAmount(existing.getAmount() + moved);
        pending.setAmount(pending.getAmount() - moved);
        if (pending.getAmount() > 0) {
            return pending;
        }
        return null;
    }
    
    private ItemStack getCraftingRemainder(ItemStack item, int amount) {
        String customId = ItemUtils.getCustomItemId(item);
        ContainerReturnConfig config =
                FarmersDelightPlugin.getInstance().getContainerReturnConfig();
        if (customId != null && config != null) {
            return config.getReturnItem(customId, amount);
        }

        Material remainderType = item.getType().getCraftingRemainingItem();
        if (remainderType != null && !remainderType.isAir()) {
            return new ItemStack(remainderType, amount);
        }

        return switch (item.getType()) {
            case MILK_BUCKET, WATER_BUCKET, LAVA_BUCKET -> new ItemStack(Material.BUCKET, amount);
            case HONEY_BOTTLE -> new ItemStack(Material.GLASS_BOTTLE, amount);
            default -> null;
        };
    }

    private boolean matchesIngredient(ItemStack item, RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            String customId = ItemUtils.getCustomItemId(item);
            if (customId != null) {
                return customId.equals(itemIngredient.key().toString());
            }
            return ("minecraft:" + item.getType().name().toLowerCase()).equals(itemIngredient.key().toString());
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
            String customId = ItemUtils.getCustomItemId(item);
            Key itemKey = customId != null
                    ? Key.of(customId)
                    : Key.of("minecraft:" + item.getType().name().toLowerCase());

            if (tagIngredient.excludedItems().contains(itemKey)) {
                return false;
            }

            if (customId != null) {
                var customItem = FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                        .getCustomItem(Key.of(customId)).orElse(null);
                if (customItem == null || !customItem.settings().tags().contains(tagIngredient.key())) {
                    return false;
                }
                for (Key excludedTag : tagIngredient.excludedTags()) {
                    if (customItem.settings().tags().contains(excludedTag)) {
                        return false;
                    }
                }
                return true;
            }

            boolean matchesBase = FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                    .vanillaItemIdsByTag(tagIngredient.key()).stream()
                    .anyMatch(key -> key.toString().equals(itemKey.toString()))
                    || ItemUtils.matchesVanillaItemTag(item, tagIngredient.key(),
                    tagIngredient.excludedItems(), tagIngredient.excludedTags());
            if (!matchesBase) {
                return false;
            }
            for (Key excludedTag : tagIngredient.excludedTags()) {
                boolean blocked = FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                        .vanillaItemIdsByTag(excludedTag).stream()
                        .anyMatch(key -> key.toString().equals(itemKey.toString()));
                if (blocked) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    public boolean canCook() {
        synchronized (inventoryLock) {
            return canCookInternal();
        }
    }

    public boolean finishCooking(World world, Location blockLoc) {
        synchronized (inventoryLock) {
            synchronized (cookingLock) {
                CookingPotRecipe recipe = currentRecipe.get();
                if (recipe == null) {
                    if (!canCookInternal()) return false;
                    recipe = currentRecipe.get();
                    if (recipe == null) return false;
                }

                ItemStack resultItem = recipe.getResult();
                if (resultItem == null) return false;

                ItemStack outputItem = resultItem.clone();
                setItemStoredExperience(outputItem, recipe.getExperience());

                boolean stored = storeCookedResult(outputItem, recipe);
                if (!stored) {
                    cookingProgress.set(getCookingDuration());
                    return false;
                }

                consumeIngredientsInternal(recipe, world, blockLoc);
                cookingProgress.set(0);
                currentRecipe.set(null);
                lastRecipeId.set(null);
                lastRecipeFingerprint.set(null);

                return true;
            }
        }
    }

    private boolean canCookInternal() {
        try {
            FarmersDelightPlugin instance = FarmersDelightPlugin.getInstance();
            if (instance == null || !FarmersDelightPlugin.isEnabled0()) {
                return false;
            }

            CookingPotRecipe recipe = instance.getCookingPotRecipes()
                    .matchRecipe(getIngredientSlotsInternal(), getContainerItemInternal());

            if (recipe == null) {
                currentRecipe.set(null);
                lastRecipeId.set(null);
                lastRecipeFingerprint.set(null);
                return false;
            }

            String newRecipeId = recipe.getId();
            String newFingerprint = buildRecipeFingerprint(recipe);
            String lastId = lastRecipeId.get();
            String lastFingerprint = lastRecipeFingerprint.get();

            if (lastId != null && (!newRecipeId.equals(lastId) || !newFingerprint.equals(lastFingerprint))) {
                cookingProgress.set(0);
            }

            lastRecipeId.set(newRecipeId);
            lastRecipeFingerprint.set(newFingerprint);

            currentRecipe.set(recipe);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private List<ItemStack> getIngredientSlotsInternal() {
        List<ItemStack> slots = new ArrayList<>(CookingPotBlockBehavior.SLOT_MEAL_DISPLAY);
        for (int i = FIRST_INGREDIENT_SLOT; i < CookingPotBlockBehavior.SLOT_MEAL_DISPLAY; i++) {
            slots.add(copyOrNull(inventory[i]));
        }
        return slots;
    }

    private ItemStack getContainerItemInternal() {
        return copyOrNull(inventory[CookingPotBlockBehavior.SLOT_CONTAINER]);
    }

    private void consumeIngredientsInternal(CookingPotRecipe recipe, World world, Location blockLoc) {
        if (recipe == null) return;

        List<ItemStack> drops = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (int i = 0; i < CookingPotBlockBehavior.SLOT_MEAL_DISPLAY; i++) {
                ItemStack slotItem = inventory[i];
                if (slotItem == null || slotItem.getType().isAir() || !matchesIngredient(slotItem, ingredient)) {
                    continue;
                }

                ItemStack remainder = getCraftingRemainder(slotItem, 1);

                int newAmount = slotItem.getAmount() - 1;
                if (newAmount <= 0) {
                    inventory[i] = null;
                } else {
                    slotItem.setAmount(newAmount);
                }

                if (remainder != null) {
                    ItemStack leftover = storeRemainderInIngredientSlots(remainder);
                    if (leftover != null && !leftover.getType().isAir()) {
                        drops.add(leftover);
                    }
                }
                break;
            }
        }

        if (world != null && blockLoc != null) {
            Location dropLoc = blockLoc.clone().add(0.5, 0.7, 0.5);
            for (ItemStack remainder : drops) {
                world.dropItemNaturally(dropLoc, remainder);
            }
        }
    }

    private String buildRecipeFingerprint(CookingPotRecipe recipe) {
        StringBuilder builder = new StringBuilder(recipe.getId()).append('|');

        synchronized (inventoryLock) {
            List<String> ingredientFingerprints = new ArrayList<>();
            for (int i = FIRST_INGREDIENT_SLOT; i < CookingPotBlockBehavior.SLOT_MEAL_DISPLAY; i++) {
                String fingerprint = buildItemFingerprint(inventory[i]);
                if (!"none".equals(fingerprint)) {
                    ingredientFingerprints.add(fingerprint);
                }
            }
            ingredientFingerprints.sort(String::compareTo);
            for (String fingerprint : ingredientFingerprints) {
                builder.append(fingerprint).append(';');
            }
        }

        return builder.toString();
    }

    private String buildItemFingerprint(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "none";
        }

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        return "minecraft:" + item.getType().name().toLowerCase();
    }
    
    public boolean doesMealHaveContainer() {
        ItemStack container = mealContainerStack.get();
        return container != null && !container.getType().isAir();
    }
    
    public ItemStack getMealContainer() {
        ItemStack container = mealContainerStack.get();
        return copyOrNull(container);
    }

    public void setMealContainer(ItemStack container) {
        mealContainerStack.set(copyOrNull(container));
    }
    
    public boolean isContainerValid(ItemStack containerItem) {
        if (containerItem == null || containerItem.getType().isAir()) return false;
        ItemStack requiredContainer = mealContainerStack.get();
        if (requiredContainer == null) return true;
        
        String requiredId = ItemUtils.getCustomItemId(requiredContainer);
        String providedId = ItemUtils.getCustomItemId(containerItem);
        if (requiredId != null && providedId != null) {
            return requiredId.equals(providedId);
        }
        return requiredContainer.isSimilar(containerItem);
    }
    
    public ItemStack useContainerToTakeMeal() {
        return takeMealPortion(1);
    }

    public ItemStack takeMealPortionForDelivery(World world, int requestedAmount) {
        ItemStack meal = takeMealPortion(requestedAmount);
        if (meal != null && world != null) {
            float mealExperience = getItemStoredExperience(meal);
            if (mealExperience > 0f) {
                dropExperience(world, mealExperience);
                clearItemStoredExperience(meal);
            }
        }
        return meal;
    }

    public ItemStack takeMealPortion(int requestedAmount) {
        synchronized (inventoryLock) {
            ItemStack meal = inventory[CookingPotBlockBehavior.SLOT_OUTPUT];
            if (meal == null || meal.getType().isAir()) return null;

            int amount = Math.max(1, Math.min(requestedAmount, meal.getAmount()));
            ItemStack result = splitItemWithExperience(meal, amount);

            if (meal.getAmount() <= 0) {
                inventory[CookingPotBlockBehavior.SLOT_OUTPUT] = null;
            }

            tryMovePendingToOutput();
            return result;
        }
    }

    public ItemStack takeMeal() {
        synchronized (inventoryLock) {
            ItemStack meal = inventory[CookingPotBlockBehavior.SLOT_OUTPUT];
            if (meal != null && !meal.getType().isAir()) {
                inventory[CookingPotBlockBehavior.SLOT_OUTPUT] = null;
                tryMovePendingToOutput();
                return meal;
            }
        }
        return null;
    }

    public ItemStack takeMealWithExperience(World world) {
        ItemStack meal = takeMeal();
        if (meal != null && world != null) {
            float mealExperience = getItemStoredExperience(meal);
            if (mealExperience > 0) {
                dropExperience(world, mealExperience);
                clearItemStoredExperience(meal);
            }
        }
        return meal;
    }

    public void dropExperience(World world, float totalExp) {
        if (world == null || totalExp <= 0f) return;

        int expValue = (int) Math.floor(totalExp);

        if (expValue > 0) {
            Location expLocation = new Location(world, posKey.x() + 0.5, posKey.y() + 1.0, posKey.z() + 0.5);
            world.spawn(expLocation, ExperienceOrb.class, orb -> orb.setExperience(expValue));
        }
    }

    public List<ItemStack> returnContainerItems() {
        List<ItemStack> returnedItems = new ArrayList<>();

        synchronized (inventoryLock) {
            for (int i = 0; i < CookingPotBlockBehavior.SLOT_CONTAINER; i++) {
                ItemStack item = inventory[i];
                if (item != null && !item.getType().isAir()) {
                    returnedItems.add(item.clone());
                    inventory[i] = null;
                }
            }
        }

        return returnedItems;
    }

    public void tryMovePendingToOutput() {
        synchronized (inventoryLock) {
            ItemStack pending = inventory[CookingPotBlockBehavior.SLOT_MEAL_DISPLAY];
            if (pending == null || pending.getType().isAir()) {
                mealContainerStack.set(null);
                return;
            }

            int movableAmount = getMovablePendingAmount(pending);
            if (movableAmount <= 0) {
                return;
            }

            ItemStack moving = splitItemWithExperience(pending, movableAmount);
            addItemToSlot(CookingPotBlockBehavior.SLOT_OUTPUT, moving);

            ItemStack requiredContainer = mealContainerStack.get();
            if (requiredContainer != null && !requiredContainer.getType().isAir()) {
                consumeContainerAmount(movableAmount);
            }

            if (pending.getAmount() <= 0) {
                inventory[CookingPotBlockBehavior.SLOT_MEAL_DISPLAY] = null;
                mealContainerStack.set(null);
            }
        }
    }

    private boolean storeCookedResult(ItemStack result, CookingPotRecipe recipe) {
        synchronized (inventoryLock) {
            if (!recipe.needsContainer()) {
                if (hasSpaceFor(CookingPotBlockBehavior.SLOT_OUTPUT, result)) {
                    addItemToSlot(CookingPotBlockBehavior.SLOT_OUTPUT, result);
                    return true;
                }

                if (hasSpaceFor(CookingPotBlockBehavior.SLOT_MEAL_DISPLAY, result)) {
                    addItemToSlot(CookingPotBlockBehavior.SLOT_MEAL_DISPLAY, result);
                    mealContainerStack.set(null);
                    return true;
                }
                return false;
            }

            ItemStack requiredContainer = recipe.getContainer();
            int directMove = getMovableOutputAmount(result);
            int availableContainers = getAvailableContainerAmount(requiredContainer);
            if (directMove >= result.getAmount() && availableContainers >= result.getAmount()) {
                addItemToSlot(CookingPotBlockBehavior.SLOT_OUTPUT, result);
                consumeContainerAmount(result.getAmount());
                mealContainerStack.set(null);
                return true;
            }

                if (hasSpaceFor(CookingPotBlockBehavior.SLOT_MEAL_DISPLAY, result)) {
                    addItemToSlot(CookingPotBlockBehavior.SLOT_MEAL_DISPLAY, result);
                    mealContainerStack.set(copyOrNull(requiredContainer));
                    return true;
                }

            return false;
        }
    }

    private int getMovablePendingAmount(ItemStack pending) {
        ItemStack requiredContainer = mealContainerStack.get();
        int outputSpace = getAvailableSpace(CookingPotBlockBehavior.SLOT_OUTPUT, pending);
        if (outputSpace <= 0) return 0;

        if (requiredContainer == null || requiredContainer.getType().isAir()) {
            return Math.min(pending.getAmount(), outputSpace);
        }

        int availableContainers = getAvailableContainerAmount(requiredContainer);
        if (availableContainers <= 0) return 0;
        return Math.min(pending.getAmount(), Math.min(outputSpace, availableContainers));
    }

    private int getMovableOutputAmount(ItemStack item) {
        return getAvailableSpace(CookingPotBlockBehavior.SLOT_OUTPUT, item);
    }

    private int getAvailableContainerAmount(ItemStack requiredContainer) {
        ItemStack container = inventory[CookingPotBlockBehavior.SLOT_CONTAINER];
        if (requiredContainer == null || requiredContainer.getType().isAir()) {
            return Integer.MAX_VALUE;
        }
        if (!isContainerValid(container)) {
            return 0;
        }
        return container.getAmount();
    }

    private void consumeContainerAmount(int amount) {
        if (amount <= 0) return;
        ItemStack container = inventory[CookingPotBlockBehavior.SLOT_CONTAINER];
        if (container == null || container.getType().isAir()) return;

        int remaining = container.getAmount() - amount;
        if (remaining > 0) {
            container.setAmount(remaining);
        } else {
            inventory[CookingPotBlockBehavior.SLOT_CONTAINER] = null;
        }
    }

    private boolean hasSpaceFor(int slot, ItemStack item) {
        return getAvailableSpace(slot, item) >= item.getAmount();
    }

    private int getAvailableSpace(int slot, ItemStack item) {
        ItemStack existing = inventory[slot];
        if (existing == null || existing.getType().isAir()) {
            return item.getMaxStackSize();
        }
        if (!isSimilarIgnoringStoredExperience(existing, item)) {
            return 0;
        }
        return Math.max(0, existing.getMaxStackSize() - existing.getAmount());
    }

    private void addItemToSlot(int slot, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            inventory[slot] = null;
            return;
        }

        ItemStack existing = inventory[slot];
        if (existing != null && isSimilarIgnoringStoredExperience(existing, item)) {
            double mergedExperience = getItemStoredExperienceInternal(existing) + getItemStoredExperienceInternal(item);
            existing.setAmount(existing.getAmount() + item.getAmount());
            setItemStoredExperience(existing, mergedExperience);
            return;
        }

        inventory[slot] = item.clone();
    }

    private ItemStack splitItemWithExperience(ItemStack source, int amount) {
        ItemStack split = source.clone();
        split.setAmount(amount);

        int originalAmount = source.getAmount();
        double totalExperience = getItemStoredExperienceInternal(source);
        double extractedExperience = 0;
        if (originalAmount > 0) {
            extractedExperience = (totalExperience * amount) / originalAmount;
        }
        setItemStoredExperience(split, extractedExperience);

        int remainingAmount = originalAmount - amount;
        source.setAmount(remainingAmount);
        if (remainingAmount > 0) {
            setItemStoredExperience(source, Math.max(0, totalExperience - extractedExperience));
        } else {
            clearItemStoredExperience(source);
        }

        return split;
    }

    public float getItemStoredExperience(ItemStack item) {
        return (float) getItemStoredExperienceInternal(item);
    }

    public void clearItemStoredExperience(ItemStack item) {
        setItemStoredExperience(item, 0);
    }

    private double getItemStoredExperienceInternal(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return 0;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }

        NamespacedKey xpKey = getStoredExperienceKey();
        return meta.getPersistentDataContainer().getOrDefault(xpKey, org.bukkit.persistence.PersistentDataType.DOUBLE, 0.0);
    }

    private void setItemStoredExperience(ItemStack item, double experience) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        NamespacedKey xpKey = getStoredExperienceKey();
        if (experience > 0.001) {
            meta.getPersistentDataContainer().set(xpKey, org.bukkit.persistence.PersistentDataType.DOUBLE, experience);
        } else {
            meta.getPersistentDataContainer().remove(xpKey);
        }
        item.setItemMeta(meta);
    }

    private NamespacedKey getStoredExperienceKey() {
        return new NamespacedKey(FarmersDelightPlugin.getInstance(), "stored_exp");
    }

    private boolean isSimilarIgnoringStoredExperience(ItemStack first, ItemStack second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getType() != second.getType()) {
            return false;
        }

        String firstCustomId = ItemUtils.getCustomItemId(first);
        String secondCustomId = ItemUtils.getCustomItemId(second);
        if (firstCustomId != null || secondCustomId != null) {
            return firstCustomId != null && firstCustomId.equals(secondCustomId);
        }

        if (!first.hasItemMeta() && !second.hasItemMeta()) {
            return true;
        }
        if (first.hasItemMeta() != second.hasItemMeta()) {
            return false;
        }

        if (first.getAmount() != second.getAmount()) {
            return false;
        }

        if (first.isSimilar(second)) {
            return true;
        }

        ItemStack firstCopy = first.clone();
        ItemMeta firstMeta = firstCopy.getItemMeta();
        if (firstMeta != null) {
            firstMeta.getPersistentDataContainer().remove(getStoredExperienceKey());
            firstCopy.setItemMeta(firstMeta);
            return firstCopy.isSimilar(second);
        }
        return false;
    }

    public int getProgressPercent() {
        int duration = cookingDuration.get();
        if (duration <= 0) return 0;
        return Math.min(100, cookingProgress.get() * 100 / duration);
    }

    public int getCookingProgress() {
        return cookingProgress.get();
    }

    public void setCookingProgress(int progress) {
        cookingProgress.set(progress);
    }

    public void incrementCookingProgress() {
        cookingProgress.incrementAndGet();
    }

    public void decrementCookingProgress() {
        cookingProgress.updateAndGet(v -> Math.max(0, v - 2));
    }

    public int getCookingDuration() {
        return cookingDuration.get();
    }

    public void setCookingDuration(int duration) {
        this.cookingDuration.set(duration);
    }

    public boolean hasHeatSource() {
        return hasHeatSource.get();
    }

    public void setHasHeatSource(boolean heatSource) {
        hasHeatSource.set(heatSource);
    }

    public int getRemainingTime() {
        return Math.max(0, cookingDuration.get() - cookingProgress.get());
    }

    public CookingPotRecipe getCurrentRecipe() {
        return currentRecipe.get();
    }
}
