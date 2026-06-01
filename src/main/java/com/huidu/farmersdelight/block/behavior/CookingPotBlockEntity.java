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
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CookingPotBlockEntity {

    private final BlockPosKey posKey;
    private volatile World world;
    private volatile CookingPotLayout layout;
    private volatile String recipeGroupId;
    private ItemStack[] inventory;
    private double[] slotExperience;
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
        this(posKey, null, CookingPotLayout.DEFAULT, null);
    }

    public CookingPotBlockEntity(BlockPosKey posKey, World world) {
        this(posKey, world, CookingPotLayout.DEFAULT, null);
    }

    public CookingPotBlockEntity(BlockPosKey posKey, World world, CookingPotLayout layout, String recipeGroupId) {
        this.posKey = posKey;
        this.world = world;
        this.layout = layout != null ? layout : CookingPotLayout.DEFAULT;
        this.recipeGroupId = normalizeBlank(recipeGroupId);
        this.inventory = new ItemStack[this.layout.size()];
        this.slotExperience = new double[this.layout.size()];
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public World getWorld() {
        return world;
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

    public void applyBehavior(CookingPotBlockBehavior behavior) {
        if (behavior == null) {
            return;
        }
        applyLayout(behavior.getLayout(), behavior.getCustomRecipeGroupId());
    }

    private void applyLayout(CookingPotLayout newLayout, String newRecipeGroupId) {
        if (newLayout == null) {
            newLayout = CookingPotLayout.DEFAULT;
        }
        synchronized (inventoryLock) {
            this.recipeGroupId = normalizeBlank(newRecipeGroupId);
            if (this.layout.isDefault() == newLayout.isDefault()
                    && this.layout.size() == newLayout.size()
                    && java.util.Arrays.equals(this.layout.inputSlots(), newLayout.inputSlots())
                    && java.util.Arrays.equals(this.layout.pendingOutputSlots(), newLayout.pendingOutputSlots())
                    && java.util.Arrays.equals(this.layout.outputSlots(), newLayout.outputSlots())
                    && java.util.Arrays.equals(this.layout.containerSlots(), newLayout.containerSlots())) {
                this.layout = newLayout;
                return;
            }
            ItemStack[] oldInventory = this.inventory;
            double[] oldExperience = this.slotExperience;
            this.layout = newLayout;
            this.inventory = Arrays.copyOf(oldInventory, newLayout.size());
            this.slotExperience = Arrays.copyOf(oldExperience, newLayout.size());
        }
    }

    public CookingPotLayout getLayout() {
        return layout;
    }

    public String getRecipeGroupId() {
        return recipeGroupId;
    }

    public int getInventorySize() {
        return inventory.length;
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < inventory.length;
    }

    public ItemStack getInventorySlot(int slot) {
        synchronized (inventoryLock) {
            if (!isValidSlot(slot)) {
                return null;
            }
            return copyOrNull(inventory[slot]);
        }
    }

    public void setInventorySlot(int slot, ItemStack item) {
        synchronized (inventoryLock) {
            if (!isValidSlot(slot)) {
                return;
            }
            inventory[slot] = copyOrNull(item);
            if (inventory[slot] == null || inventory[slot].getType().isAir()) {
                slotExperience[slot] = 0.0D;
            }
        }
        syncWorldlyContainer();
    }

    public double getSlotExperience(int slot) {
        synchronized (inventoryLock) {
            if (!isValidSlot(slot)) {
                return 0.0D;
            }
            return slotExperience[slot];
        }
    }

    public void setSlotExperience(int slot, double experience) {
        boolean changed;
        synchronized (inventoryLock) {
            if (!isValidSlot(slot)) {
                return;
            }
            double normalized = Math.max(0.0D, experience);
            changed = Double.compare(slotExperience[slot], normalized) != 0;
            slotExperience[slot] = normalized;
        }
        if (changed) {
            syncWorldlyContainer();
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

    private ItemStack getFirstItem(int[] slots) {
        int slot = firstNonEmptySlot(slots);
        return slot < 0 ? null : copyOrNull(inventory[slot]);
    }

    private int firstNonEmptySlot(int[] slots) {
        if (slots == null) {
            return -1;
        }
        for (int slot : slots) {
            if (!isValidSlot(slot)) {
                continue;
            }
            ItemStack item = inventory[slot];
            if (item != null && !item.getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

    private boolean hasAnyItem(int[] slots) {
        return firstNonEmptySlot(slots) >= 0;
    }

    private boolean consumeOneFromSlots(int[] slots) {
        int slot = firstNonEmptySlot(slots);
        if (slot < 0) {
            return false;
        }
        ItemStack item = inventory[slot];
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            inventory[slot] = null;
            slotExperience[slot] = 0.0D;
        }
        return true;
    }

    private void syncWorldlyContainer() {
        World currentWorld = world;
        if (currentWorld != null) {
            CookingPotBlockBehavior.markBlockEntityDirty(currentWorld, posKey);
        }
    }

    public ItemStack getContainerItem() {
        synchronized (inventoryLock) {
            return getContainerItemInternal();
        }
    }

    public ItemStack insertIngredientStack(ItemStack item) {
        synchronized (inventoryLock) {
            ItemStack remainder = insertIntoSlots(item, layout.inputSlots());
            syncWorldlyContainer();
            return remainder;
        }
    }

    public ItemStack insertContainerStack(ItemStack item) {
        synchronized (inventoryLock) {
            ItemStack remainder = insertIntoSlots(item, layout.containerSlots());
            syncWorldlyContainer();
            return remainder;
        }
    }

    public ItemStack getMealDisplayItem() {
        synchronized (inventoryLock) {
            return getFirstItem(layout.outputSlots());
        }
    }

    public void setMealDisplayItem(ItemStack item) {
        synchronized (inventoryLock) {
            addItemToSlots(layout.outputSlots(), item);
        }
        syncWorldlyContainer();
    }

    public ItemStack getPendingOutputItem() {
        synchronized (inventoryLock) {
            return getFirstItem(layout.pendingOutputSlots());
        }
    }

    public boolean hasPendingOutput() {
        synchronized (inventoryLock) {
            return hasAnyItem(layout.pendingOutputSlots());
        }
    }

    public boolean hasInput() {
        synchronized (inventoryLock) {
            for (int i : layout.inputSlots()) {
                ItemStack item = inventory[i];
                if (item != null && !item.getType().isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasStoredContents() {
        synchronized (inventoryLock) {
            for (ItemStack item : inventory) {
                if (item != null && !item.getType().isAir()) {
                    return true;
                }
            }
        }
        return doesMealHaveContainer();
    }

    public boolean consumeContainer() {
        synchronized (inventoryLock) {
            if (!consumeOneFromSlots(layout.containerSlots())) {
                return false;
            }
        }
        syncWorldlyContainer();
        return true;
    }

    public void consumeIngredients(CookingPotRecipe recipe, World world, Location blockLoc) {
        if (recipe == null) return;

        synchronized (inventoryLock) {
            consumeIngredientsInternal(recipe, world, blockLoc);
        }
        syncWorldlyContainer();
    }

    private ItemStack storeRemainderInIngredientSlots(ItemStack remainder) {
        ItemStack pending = remainder.clone();
        for (int i : layout.inputSlots()) {
            ItemStack slotItem = inventory[i];
            if (slotItem == null || slotItem.getType().isAir()) {
                inventory[i] = pending;
                slotExperience[i] = 0.0D;
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
        return insertIntoSlots(item, java.util.stream.IntStream.rangeClosed(startSlot, endSlot).toArray());
    }

    private ItemStack insertIntoSlots(ItemStack item, int[] slots) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        if (slots == null || slots.length == 0) {
            return item.clone();
        }

        ItemStack pending = item.clone();
        for (int i : slots) {
            if (!isValidSlot(i)) continue;
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

        for (int i : slots) {
            if (!isValidSlot(i)) continue;
            ItemStack existing = inventory[i];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }

            inventory[i] = pending.clone();
            slotExperience[i] = 0.0D;
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
            slotExperience[slot] = 0.0D;
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
            return ItemUtils.matchesItemId(item, itemIngredient.key());
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
                if (blocked) {
                    return false;
                }
                return true;
            }

            Key itemKey = Key.of("minecraft:" + item.getType().name().toLowerCase());
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

                boolean stored = storeCookedResult(outputItem, recipe, recipe.getExperience());
                if (!stored) {
                    cookingProgress.set(getCookingDuration());
                    syncWorldlyContainer();
                    return false;
                }

                consumeIngredientsInternal(recipe, world, blockLoc);
                cookingProgress.set(0);
                currentRecipe.set(null);
                lastRecipeId.set(null);
                lastRecipeFingerprint.set(null);

                syncWorldlyContainer();
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

            List<ItemStack> inputItems = getIngredientSlotsInternal();
            ItemStack containerItem = getContainerItemInternal();
            CookingPotRecipe previousRecipe = currentRecipe.get();
            if (previousRecipe != null
                    && instance.getCookingPotRecipes().canCraft(previousRecipe, inputItems, containerItem)) {
                lastRecipeId.set(previousRecipe.getId());
                lastRecipeFingerprint.set(buildRecipeFingerprint(previousRecipe));
                currentRecipe.set(previousRecipe);
                return true;
            }

            CookingPotRecipe recipe = instance.getCookingPotRecipes()
                    .matchRecipe(inputItems, containerItem, recipeGroupId);

            if (recipe == null) {
                currentRecipe.set(null);
                lastRecipeId.set(null);
                lastRecipeFingerprint.set(null);
                return false;
            }

            String newRecipeId = recipe.getId();
            String lastId = lastRecipeId.get();

            if (lastId != null && !newRecipeId.equals(lastId)) {
                cookingProgress.set(0);
            }

            lastRecipeId.set(newRecipeId);
            lastRecipeFingerprint.set(buildRecipeFingerprint(recipe));

            currentRecipe.set(recipe);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private List<ItemStack> getIngredientSlotsInternal() {
        List<ItemStack> slots = new ArrayList<>(layout.inputSlots().length);
        for (int i : layout.inputSlots()) {
            slots.add(copyOrNull(inventory[i]));
        }
        return slots;
    }

    private ItemStack getContainerItemInternal() {
        return getFirstItem(layout.containerSlots());
    }

    private void consumeIngredientsInternal(CookingPotRecipe recipe, World world, Location blockLoc) {
        if (recipe == null) return;

        List<ItemStack> drops = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (int i : layout.inputSlots()) {
                ItemStack slotItem = inventory[i];
                if (slotItem == null || slotItem.getType().isAir() || !matchesIngredient(slotItem, ingredient)) {
                    continue;
                }

                ItemStack remainder = getCraftingRemainder(slotItem, 1);

                int newAmount = slotItem.getAmount() - 1;
                if (newAmount <= 0) {
                    inventory[i] = null;
                    slotExperience[i] = 0.0D;
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
            for (int i : layout.inputSlots()) {
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
        syncWorldlyContainer();
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

    public ItemStack useHeldContainerOnPendingMeal(World world, ItemStack container) {
        TakenMeal meal = useHeldContainerOnPendingMeal(container);
        if (meal != null && world != null && meal.experience() > 0.0D && shouldDropVanillaExperience()) {
            dropExperience(world, meal.experience());
        }
        return meal == null ? null : meal.item();
    }

    public TakenMeal useHeldContainerOnPendingMeal(ItemStack container) {
        synchronized (inventoryLock) {
            if (!doesMealHaveContainer() || !isContainerValid(container)) {
                return null;
            }

            int pendingSlot = firstNonEmptySlot(layout.pendingOutputSlots());
            if (pendingSlot < 0) {
                return null;
            }
            ItemStack meal = inventory[pendingSlot];
            if (meal == null || meal.getType().isAir()) {
                return null;
            }

            SplitItem result = splitItemFromSlot(pendingSlot, 1);
            if (meal.getAmount() <= 0) {
                inventory[pendingSlot] = null;
                slotExperience[pendingSlot] = 0.0D;
                if (!hasAnyItem(layout.pendingOutputSlots())) {
                    mealContainerStack.set(null);
                }
            }

            syncWorldlyContainer();
            return TakenMeal.from(result);
        }
    }

    public ItemStack takeMealPortionForDelivery(World world, int requestedAmount) {
        TakenMeal meal = takeMealPortionForDelivery(requestedAmount);
        if (meal != null && world != null && meal.experience() > 0.0D && shouldDropVanillaExperience()) {
            dropExperience(world, meal.experience());
        }
        return meal == null ? null : meal.item();
    }

    public TakenMeal takeMealPortionForDelivery(int requestedAmount) {
        return TakenMeal.from(takeMealPortionWithExperience(requestedAmount));
    }

    public ItemStack takeOutputSlotPortionForDelivery(World world, int outputSlot, int requestedAmount) {
        if (!layout.isOutputSlot(outputSlot)) {
            return null;
        }
        TakenMeal meal = takeOutputSlotPortionForDelivery(outputSlot, requestedAmount);
        if (meal != null && world != null && meal.experience() > 0.0D && shouldDropVanillaExperience()) {
            dropExperience(world, meal.experience());
        }
        return meal == null ? null : meal.item();
    }

    public TakenMeal takeOutputSlotPortionForDelivery(int outputSlot, int requestedAmount) {
        if (!layout.isOutputSlot(outputSlot)) {
            return null;
        }
        return TakenMeal.from(takeMealPortionWithExperience(outputSlot, requestedAmount));
    }

    public ItemStack takeMealPortion(int requestedAmount) {
        SplitItem meal = takeMealPortionWithExperience(requestedAmount);
        return meal == null ? null : meal.item();
    }

    private SplitItem takeMealPortionWithExperience(int requestedAmount) {
        int outputSlot;
        synchronized (inventoryLock) {
            outputSlot = firstNonEmptySlot(layout.outputSlots());
            if (outputSlot < 0) return null;
        }
        return takeMealPortionWithExperience(outputSlot, requestedAmount);
    }

    private SplitItem takeMealPortionWithExperience(int outputSlot, int requestedAmount) {
        synchronized (inventoryLock) {
            if (!isValidSlot(outputSlot) || !layout.isOutputSlot(outputSlot)) {
                return null;
            }
            ItemStack meal = inventory[outputSlot];
            if (meal == null || meal.getType().isAir()) return null;

            int amount = Math.max(1, Math.min(requestedAmount, meal.getAmount()));
            SplitItem result = splitItemFromSlot(outputSlot, amount);

            if (meal.getAmount() <= 0) {
                inventory[outputSlot] = null;
                slotExperience[outputSlot] = 0.0D;
            }

            tryMovePendingToOutput();
            syncWorldlyContainer();
            return result;
        }
    }

    public ItemStack takeMeal() {
        synchronized (inventoryLock) {
            int outputSlot = firstNonEmptySlot(layout.outputSlots());
            if (outputSlot < 0) {
                return null;
            }
            ItemStack meal = inventory[outputSlot];
            if (meal != null && !meal.getType().isAir()) {
                slotExperience[outputSlot] = 0.0D;
                inventory[outputSlot] = null;
                tryMovePendingToOutput();
                syncWorldlyContainer();
                return meal;
            }
        }
        return null;
    }

    public ItemStack takeMealWithExperience(World world) {
        SplitItem meal;
        synchronized (inventoryLock) {
            int outputSlot = firstNonEmptySlot(layout.outputSlots());
            if (outputSlot < 0) {
                return null;
            }
            ItemStack item = inventory[outputSlot];
            if (item == null || item.getType().isAir()) {
                return null;
            }
            meal = new SplitItem(item, slotExperience[outputSlot]);
            inventory[outputSlot] = null;
            slotExperience[outputSlot] = 0.0D;
            tryMovePendingToOutput();
        }
        syncWorldlyContainer();
        if (world != null && meal.experience() > 0.0D && shouldDropVanillaExperience()) {
            dropExperience(world, meal.experience());
        }
        return meal.item();
    }

    private boolean shouldDropVanillaExperience() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null || plugin.shouldDropCookingPotVanillaExperience();
    }

    public void dropExperience(World world, double totalExp) {
        if (world == null || totalExp <= 0.0D) return;

        int expValue = (int) Math.floor(totalExp);

        if (expValue > 0) {
            Location expLocation = new Location(world, posKey.x() + 0.5, posKey.y() + 1.0, posKey.z() + 0.5);
            world.spawn(expLocation, ExperienceOrb.class, orb -> orb.setExperience(expValue));
        }
    }

    public List<ItemStack> returnContainerItems() {
        List<ItemStack> returnedItems = new ArrayList<>();

        synchronized (inventoryLock) {
            for (int i : layout.inputSlots()) {
                ItemStack item = inventory[i];
                if (item != null && !item.getType().isAir()) {
                    returnedItems.add(item.clone());
                    inventory[i] = null;
                    slotExperience[i] = 0.0D;
                }
            }
        }

        if (!returnedItems.isEmpty()) {
            syncWorldlyContainer();
        }
        return returnedItems;
    }

    public void tryMovePendingToOutput() {
        synchronized (inventoryLock) {
            if (!hasAnyItem(layout.pendingOutputSlots())) {
                ItemStack previousContainer = mealContainerStack.getAndSet(null);
                if (previousContainer != null && !previousContainer.getType().isAir()) {
                    syncWorldlyContainer();
                }
                return;
            }

            for (int pendingSlot : layout.pendingOutputSlots()) {
                ItemStack pending = inventory[pendingSlot];
                if (pending == null || pending.getType().isAir()) {
                    continue;
                }
                int movableAmount = getMovablePendingAmount(pending);
                if (movableAmount <= 0) {
                    continue;
                }

                SplitItem moving = splitItemFromSlot(pendingSlot, movableAmount);
                addItemToSlots(layout.outputSlots(), moving.item(), moving.experience());

                ItemStack requiredContainer = mealContainerStack.get();
                if (requiredContainer != null && !requiredContainer.getType().isAir()) {
                    consumeContainerAmount(movableAmount);
                }

                if (pending.getAmount() <= 0) {
                    inventory[pendingSlot] = null;
                    slotExperience[pendingSlot] = 0.0D;
                }
            }
            if (!hasAnyItem(layout.pendingOutputSlots())) {
                mealContainerStack.set(null);
            }
        }
        syncWorldlyContainer();
    }

    private boolean storeCookedResult(ItemStack result, CookingPotRecipe recipe, double storedExperience) {
        synchronized (inventoryLock) {
            if (!recipe.needsContainer()) {
                if (hasSpaceFor(layout.outputSlots(), result)) {
                    addItemToSlots(layout.outputSlots(), result, storedExperience);
                    return true;
                }

                if (hasSpaceFor(layout.pendingOutputSlots(), result)) {
                    addItemToSlots(layout.pendingOutputSlots(), result, storedExperience);
                    mealContainerStack.set(null);
                    return true;
                }
                return false;
            }

            ItemStack requiredContainer = recipe.getContainer();
            int directMove = getMovableOutputAmount(result);
            int availableContainers = getAvailableContainerAmount(requiredContainer);
            if (directMove >= result.getAmount() && availableContainers >= result.getAmount()) {
                addItemToSlots(layout.outputSlots(), result, storedExperience);
                consumeContainerAmount(result.getAmount());
                mealContainerStack.set(null);
                return true;
            }

            if (hasSpaceFor(layout.pendingOutputSlots(), result)) {
                addItemToSlots(layout.pendingOutputSlots(), result, storedExperience);
                mealContainerStack.set(copyOrNull(requiredContainer));
                return true;
            }

            return false;
        }
    }

    private int getMovablePendingAmount(ItemStack pending) {
        ItemStack requiredContainer = mealContainerStack.get();
        int outputSpace = getAvailableSpace(layout.outputSlots(), pending);
        if (outputSpace <= 0) return 0;

        if (requiredContainer == null || requiredContainer.getType().isAir()) {
            return Math.min(pending.getAmount(), outputSpace);
        }

        int availableContainers = getAvailableContainerAmount(requiredContainer);
        if (availableContainers <= 0) return 0;
        return Math.min(pending.getAmount(), Math.min(outputSpace, availableContainers));
    }

    private int getMovableOutputAmount(ItemStack item) {
        return getAvailableSpace(layout.outputSlots(), item);
    }

    private int getAvailableContainerAmount(ItemStack requiredContainer) {
        if (requiredContainer == null || requiredContainer.getType().isAir()) {
            return Integer.MAX_VALUE;
        }
        int amount = 0;
        for (int slot : layout.containerSlots()) {
            ItemStack container = inventory[slot];
            if (isContainerValid(container)) {
                amount += container.getAmount();
            }
        }
        return amount;
    }

    private void consumeContainerAmount(int amount) {
        if (amount <= 0) return;
        int remainingAmount = amount;
        for (int slot : layout.containerSlots()) {
            if (remainingAmount <= 0) {
                return;
            }
            ItemStack container = inventory[slot];
            if (container == null || container.getType().isAir()) {
                continue;
            }
            int consumed = Math.min(remainingAmount, container.getAmount());
            int remaining = container.getAmount() - consumed;
            remainingAmount -= consumed;
            if (remaining > 0) {
                container.setAmount(remaining);
            } else {
                inventory[slot] = null;
                slotExperience[slot] = 0.0D;
            }
        }
    }

    private boolean hasSpaceFor(int slot, ItemStack item) {
        return getAvailableSpace(slot, item) >= item.getAmount();
    }

    private boolean hasSpaceFor(int[] slots, ItemStack item) {
        return getAvailableSpace(slots, item) >= item.getAmount();
    }

    private int getAvailableSpace(int[] slots, ItemStack item) {
        if (slots == null || slots.length == 0 || item == null || item.getType().isAir()) {
            return 0;
        }
        int space = 0;
        for (int slot : slots) {
            space += getAvailableSpace(slot, item);
            if (space >= item.getAmount()) {
                return space;
            }
        }
        return space;
    }

    private int getAvailableSpace(int slot, ItemStack item) {
        if (!isValidSlot(slot) || item == null || item.getType().isAir()) {
            return 0;
        }
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
        addItemToSlot(slot, item, 0.0D);
    }

    private void addItemToSlot(int slot, ItemStack item, double itemStoredExperience) {
        if (!isValidSlot(slot)) {
            return;
        }
        if (item == null || item.getType().isAir()) {
            inventory[slot] = null;
            slotExperience[slot] = 0.0D;
            return;
        }

        ItemStack existing = inventory[slot];
        if (existing != null && isSimilarIgnoringStoredExperience(existing, item)) {
            existing.setAmount(existing.getAmount() + item.getAmount());
            slotExperience[slot] += Math.max(0.0D, itemStoredExperience);
            return;
        }

        inventory[slot] = item.clone();
        slotExperience[slot] = Math.max(0.0D, itemStoredExperience);
    }

    private void addItemToSlots(int[] slots, ItemStack item) {
        addItemToSlots(slots, item, 0.0D);
    }

    private void addItemToSlots(int[] slots, ItemStack item, double itemStoredExperience) {
        if (item == null || item.getType().isAir() || slots == null || slots.length == 0) {
            return;
        }
        ItemStack pending = item.clone();
        double remainingExperience = Math.max(0.0D, itemStoredExperience);
        int originalAmount = Math.max(1, pending.getAmount());

        for (int slot : slots) {
            if (pending.getAmount() <= 0) {
                return;
            }
            ItemStack existing = inventory[slot];
            if (existing == null || existing.getType().isAir() || !isSimilarIgnoringStoredExperience(existing, pending)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, pending.getAmount());
            ItemStack moving = pending.clone();
            moving.setAmount(moved);
            double experiencePart = itemStoredExperience <= 0.0D ? 0.0D : (itemStoredExperience * moved) / originalAmount;
            addItemToSlot(slot, moving, experiencePart);
            pending.setAmount(pending.getAmount() - moved);
            remainingExperience = Math.max(0.0D, remainingExperience - experiencePart);
        }

        for (int slot : slots) {
            if (pending.getAmount() <= 0) {
                return;
            }
            ItemStack existing = inventory[slot];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }
            int moved = Math.min(pending.getMaxStackSize(), pending.getAmount());
            ItemStack moving = pending.clone();
            moving.setAmount(moved);
            double experiencePart = itemStoredExperience <= 0.0D ? 0.0D : Math.min(remainingExperience, (itemStoredExperience * moved) / originalAmount);
            addItemToSlot(slot, moving, experiencePart);
            pending.setAmount(pending.getAmount() - moved);
            remainingExperience = Math.max(0.0D, remainingExperience - experiencePart);
        }
    }

    private SplitItem splitItemFromSlot(int slot, int amount) {
        if (!isValidSlot(slot)) {
            return null;
        }
        ItemStack source = inventory[slot];
        if (source == null || source.getType().isAir()) {
            return null;
        }
        ItemStack split = source.clone();
        split.setAmount(amount);

        int originalAmount = source.getAmount();
        double totalExperience = slotExperience[slot];
        double extractedExperience = 0;
        if (originalAmount > 0) {
            extractedExperience = (totalExperience * amount) / originalAmount;
        }

        int remainingAmount = originalAmount - amount;
        source.setAmount(remainingAmount);
        if (remainingAmount > 0) {
            slotExperience[slot] = Math.max(0, totalExperience - extractedExperience);
        } else {
            slotExperience[slot] = 0.0D;
        }

        return new SplitItem(split, extractedExperience);
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

        return first.isSimilar(second);
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
        int previous = cookingProgress.getAndSet(progress);
        if (previous != progress) {
            syncWorldlyContainer();
        }
    }

    public void incrementCookingProgress() {
        cookingProgress.incrementAndGet();
        syncWorldlyContainer();
    }

    public void decrementCookingProgress() {
        int previous = cookingProgress.get();
        int updated = cookingProgress.updateAndGet(v -> Math.max(0, v - 2));
        if (previous != updated) {
            syncWorldlyContainer();
        }
    }

    public int getCookingDuration() {
        return cookingDuration.get();
    }

    public void setCookingDuration(int duration) {
        int previous = this.cookingDuration.getAndSet(duration);
        if (previous != duration) {
            syncWorldlyContainer();
        }
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

    public String debugInputSummary() {
        synchronized (inventoryLock) {
            List<String> parts = new ArrayList<>();
            for (int slot : layout.inputSlots()) {
                ItemStack item = inventory[slot];
                if (item != null && !item.getType().isAir()) {
                    parts.add(slot + "=" + ItemUtils.resolveItemId(item) + "x" + item.getAmount());
                }
            }
            ItemStack container = getContainerItemInternal();
            if (container != null && !container.getType().isAir()) {
                parts.add("container=" + ItemUtils.resolveItemId(container) + "x" + container.getAmount());
            }
            return parts.isEmpty() ? "empty" : String.join(",", parts);
        }
    }

    private record SplitItem(ItemStack item, double experience) {
    }

    public record TakenMeal(ItemStack item, double experience) {
        private static TakenMeal from(SplitItem splitItem) {
            if (splitItem == null) {
                return null;
            }
            return new TakenMeal(splitItem.item(), splitItem.experience());
        }

        public TakenMeal {
            item = item == null ? null : item.clone();
        }

        @Override
        public ItemStack item() {
            return item == null ? null : item.clone();
        }
    }
}
