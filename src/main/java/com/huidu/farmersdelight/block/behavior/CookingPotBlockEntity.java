package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.FarmersDelightCookStartEvent;
import com.huidu.farmersdelight.api.event.FarmersDelightProduceEvent;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CookingPotBlockEntity {

    // Per-slot stack limit used when calculating comparator fullness.
    private static final int SLOT_STACK_LIMIT = 64;

    private final BlockPosKey posKey;
    private volatile World world;
    private volatile CookingPotLayout layout;
    private volatile String recipeGroupId;
    // volatile: layout changes replace these arrays wholesale under inventoryLock, but region threads
    // read them outside the lock via getInventoryInternal() -- this safely publishes the new references.
    private volatile ItemStack[] inventory;
    // Inventory version: incremented on every write to the inventory array, lets the GUI cheaply detect
    // "did the pot change" and skip a full input-slot rescan when unchanged. volatile so GUI threads see the latest value.
    private volatile long inventoryVersion;
    // Written by the cook tick on the pot's region, drained by awardUsedRecipes on the taking player's
    // region (a GUI viewer can stand in a different region). Concurrent map + atomic per-key removal so
    // neither side can lose a craft or trip over a resize.
    private final Map<String, Integer> usedRecipeTracker = new ConcurrentHashMap<>();
    private final AtomicInteger cookingProgress = new AtomicInteger(0);
    private final AtomicInteger cookingDuration = new AtomicInteger(200);
    private final AtomicBoolean hasHeatSource = new AtomicBoolean(false);
    private final AtomicReference<CookingPotRecipe> currentRecipe = new AtomicReference<>(null);
    // The recipe the accumulated cookingProgress belongs to. Only the pot tick replaces it (see
    // beginCookingRecipe), so it survives the passes where no recipe matches at all — an ingredient swap
    // normally goes through such a pass, and comparing against canCook's per-pass result would let the bar
    // carry over into a different dish.
    private final AtomicReference<String> progressRecipeId = new AtomicReference<>(null);
    // Recipe recorded by canCookInternal on the no-match to match transition, parked here until the
    // caller has released inventoryLock/cookingLock and can dispatch FarmersDelightCookStartEvent
    // without holding this block entity's monitors. Only the transition is recorded, so a pot that
    // keeps cooking the same batch parks nothing and the event stays an edge, not a per-tick signal.
    private final AtomicReference<CookingPotRecipe> pendingCookStart = new AtomicReference<>(null);
    private final AtomicReference<ItemStack> mealContainerStack = new AtomicReference<>(null);
    private final Object inventoryLock = new Object();
    private final Object cookingLock = new Object();
    private final FarmersDelightPlugin plugin;
    private final CookingPotCraftingHandler craftingHandler;

    public CookingPotBlockEntity(BlockPosKey posKey) {
        this(null, posKey, null, CookingPotLayout.DEFAULT, null);
    }

    public CookingPotBlockEntity(BlockPosKey posKey, World world) {
        this(null, posKey, world, CookingPotLayout.DEFAULT, null);
    }

    public CookingPotBlockEntity(BlockPosKey posKey, World world, CookingPotLayout layout, String recipeGroupId) {
        this(null, posKey, world, layout, recipeGroupId);
    }

    /**
     * @param plugin the running plugin, or null in a unit test that does not exercise the plugin paths
     */
    public CookingPotBlockEntity(FarmersDelightPlugin plugin, BlockPosKey posKey, World world,
                                 CookingPotLayout layout, String recipeGroupId) {
        this.plugin = plugin;
        this.posKey = posKey;
        this.world = world;
        this.layout = layout != null ? layout : CookingPotLayout.DEFAULT;
        this.recipeGroupId = normalizeBlank(recipeGroupId);
        this.inventory = new ItemStack[this.layout.size()];
        this.craftingHandler = new CookingPotCraftingHandler(this);
    }

    public void setWorld(World world) {
        this.world = world;
    }

    /** The plugin this entity was created with, or null in a unit test that did not supply one. */
    public FarmersDelightPlugin plugin() {
        return plugin;
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

    public void withInventoryLock(Runnable action) {
        synchronized (inventoryLock) {
            action.run();
        }
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
        List<ItemStack> overflow = new ArrayList<>();
        synchronized (inventoryLock) {
            this.recipeGroupId = normalizeBlank(newRecipeGroupId);
            if (this.layout.isDefault() == newLayout.isDefault()
                    && this.layout.size() == newLayout.size()
                    && Arrays.equals(this.layout.inputSlots(), newLayout.inputSlots())
                    && Arrays.equals(this.layout.pendingOutputSlots(), newLayout.pendingOutputSlots())
                    && Arrays.equals(this.layout.outputSlots(), newLayout.outputSlots())
                    && Arrays.equals(this.layout.containerSlots(), newLayout.containerSlots())) {
                this.layout = newLayout;
                return;
            }
            ItemStack[] oldInventory = this.inventory;
            this.layout = newLayout;
            this.inventory = Arrays.copyOf(oldInventory, newLayout.size());
            // A shrinking layout (recipe group change / config reload) drops the tail of the array.
            // Hand those stacks back to the world instead of letting copyOf swallow them.
            for (int i = newLayout.size(); i < oldInventory.length; i++) {
                ItemStack orphan = oldInventory[i];
                if (orphan != null && !orphan.getType().isAir()) {
                    overflow.add(orphan.clone());
                }
            }
            // Replacing the entire array is an inventory mutation; bump the version so GUIs rescan it.
            inventoryVersion++;
        }
        dropOverflow(overflow);
    }

    // Spawns orphaned stacks above the pot on the pot's own region. Called outside the inventory lock:
    // the world write must not happen while the lock is held, and it may need a region hop.
    private void dropOverflow(List<ItemStack> overflow) {
        if (overflow.isEmpty()) {
            return;
        }
        World currentWorld = this.world;
        if (currentWorld == null || plugin == null) {
            return;
        }
        Location loc = new Location(currentWorld, posKey.x() + 0.5, posKey.y() + 1.0, posKey.z() + 0.5);
        plugin.scheduler().runAt(loc, () -> {
            for (ItemStack orphan : overflow) {
                currentWorld.dropItemNaturally(loc, orphan);
            }
        });
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

    // Package-private so CookingPotCraftingHandler reuses the same validity guard on layout slots.
    boolean isValidSlot(int slot) {
        return slot < 0 || slot >= inventory.length;
    }

    public ItemStack getInventorySlot(int slot) {
        synchronized (inventoryLock) {
            if (isValidSlot(slot)) {
                return null;
            }
            return copyOrNull(inventory[slot]);
        }
    }

    // Must be called while holding inventoryLock: all writes to the inventory array funnel through here,
    // also bumping the version for the GUI to cheaply detect "did the pot change". version++ runs under the lock, so the non-atomic increment is safe.
    void setSlot(int slot, ItemStack item) {
        inventory[slot] = item;
        inventoryVersion++;
    }

    // Same purpose as setSlot's version bump, for the paths that shrink a stack in place instead of replacing it.
    // An in-place setAmount leaves the array reference untouched, so without this an open GUI keeps showing the
    // pre-consumption count until something else makes it rescan. Callers must hold inventoryLock.
    void bumpInventoryVersion() {
        inventoryVersion++;
    }

    // Inventory version for the GUI to read; the GUI can skip an input-slot rescan when the version is unchanged.
    public long getInventoryVersion() {
        return inventoryVersion;
    }

    public void setInventorySlot(int slot, ItemStack item) {
        synchronized (inventoryLock) {
            if (isValidSlot(slot)) {
                return;
            }
            setSlot(slot, copyOrNull(item));
        }
        syncWorldlyContainer();
    }

    public double getSlotExperience(int slot) {
        return 0.0D;
    }

    public void setSlotExperience(int slot, double experience) {
    }

    public List<ItemStack> getIngredientSlots() {
        synchronized (inventoryLock) {
            return getIngredientSlotsInternal();
        }
    }

    public int getComparatorOutput() {
        int occupied = 0;
        float fill = 0.0f;
        synchronized (inventoryLock) {
            if (inventory.length == 0) {
                return 0;
            }
            for (ItemStack item : inventory) {
                // Amount-based empty check — see takeMealPortionWithExperience for rationale.
                if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                    continue;
                }
                int slotLimit = Math.max(1, Math.min(SLOT_STACK_LIMIT, item.getMaxStackSize()));
                // A slot can hold more than its nominal limit, so the ratio is capped: an over-full slot is still
                // just full, and without the cap the average can exceed 1 and push the signal past redstone 15.
                fill += Math.min(1.0f, (float) item.getAmount() / (float) slotLimit);
                occupied++;
            }
            fill /= (float) inventory.length;
        }
        return (int) Math.floor(fill * 14.0f) + (occupied > 0 ? 1 : 0);
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
            if (isValidSlot(slot)) {
                continue;
            }
            ItemStack item = inventory[slot];
            // Amount-based empty check — see takeMealPortionWithExperience for rationale.
            if (item != null && item.getAmount() > 0) {
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
            bumpInventoryVersion();
        } else {
            setSlot(slot, null);
        }
        return true;
    }

    // Package-private so CookingPotCraftingHandler marks the chunk dirty after consuming ingredients.
    void syncWorldlyContainer() {
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

    public ItemStack getPackedMealDisplayItem() {
        synchronized (inventoryLock) {
            ItemStack pending = getFirstItem(layout.pendingOutputSlots());
            return pending != null ? pending : getFirstItem(layout.outputSlots());
        }
    }

    public boolean hasMealDisplayItem() {
        synchronized (inventoryLock) {
            return hasAnyItem(layout.outputSlots());
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

    public int countFilledInputSlots() {
        int count = 0;
        synchronized (inventoryLock) {
            for (int i : layout.inputSlots()) {
                ItemStack item = inventory[i];
                if (item != null && !item.getType().isAir()) {
                    count++;
                }
            }
        }
        return count;
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
        craftingHandler.consumeIngredients(recipe, world, blockLoc);
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
            if (isValidSlot(i)) continue;
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
            bumpInventoryVersion();
            pending.setAmount(pending.getAmount() - moved);
            if (pending.getAmount() <= 0) {
                return null;
            }
        }

        for (int i : slots) {
            if (isValidSlot(i)) continue;
            ItemStack existing = inventory[i];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }

            setSlot(i, pending.clone());
            return null;
        }

        return pending;
    }

    public boolean canCook() {
        boolean result;
        synchronized (inventoryLock) {
            result = canCookInternal();
        }
        // Outside the monitor: a listener may read or even mutate this pot without deadlocking.
        firePendingCookStart();
        return result;
    }

    private void firePendingCookStart() {
        CookingPotRecipe started = pendingCookStart.getAndSet(null);
        if (started == null || !FarmersDelightPlugin.isEnabled0()) {
            return;
        }
        World currentWorld = this.world;
        Location location = currentWorld == null
                ? null
                : new Location(currentWorld, posKey.x(), posKey.y(), posKey.z());
        Bukkit.getPluginManager().callEvent(new FarmersDelightCookStartEvent(
                location, started.getId(), started.getResult(), started.getCookTime()));
    }

    public boolean finishCooking(World world, Location blockLoc) {
        boolean result;
        synchronized (inventoryLock) {
            synchronized (cookingLock) {
                result = finishCookingLocked(world, blockLoc);
            }
        }
        // Same reason as canCook: dispatch after both monitors are released.
        firePendingCookStart();
        return result;
    }

    private boolean finishCookingLocked(World world, Location blockLoc) {
        CookingPotRecipe recipe = currentRecipe.get();
        if (recipe == null) {
            if (!canCookInternal()) return false;
            recipe = currentRecipe.get();
            if (recipe == null) return false;
        } else {
            // The cached recipe may become invalid between canCook and finishCooking if inputs change (e.g. cross-region GUI sync,
            // hopper interaction); re-validate under the lock before producing, to avoid conjuring a result from zero/insufficient ingredients (item duping).
            if (plugin == null || !plugin.getCookingPotRecipes()
                    .canCraft(recipe, getIngredientSlotsInternal())) {
                currentRecipe.set(null);
                return false;
            }
        }

        ItemStack resultItem = recipe.getResult();
        if (resultItem == null) return false;

        ItemStack outputItem = resultItem.clone();
        CookingPotCraftingHandler.Consumption consumption = craftingHandler.prepareConsumption(recipe);
        if (consumption == null) return false;

        boolean stored = storeCookedResult(outputItem, recipe);
        if (!stored) {
            cookingProgress.set(getCookingDuration());
            syncWorldlyContainer();
            return false;
        }

        craftingHandler.consumeIngredientsInternal(consumption, world, blockLoc);
        cookingProgress.set(0);
        currentRecipe.set(null);

        syncWorldlyContainer();
        fireCookFinished(world, blockLoc, recipe);
        return true;
    }

    private void fireCookFinished(World world, Location blockLoc, CookingPotRecipe recipe) {
        if (!FarmersDelightPlugin.isEnabled0() || world == null || blockLoc == null || recipe == null) {
            return;
        }
        // Notify addons (achievements, statistics, loot) that the pot produced a meal. The id is null when a
        // villager cooked, letting listeners tell player vs automated production via getPlayerId() == null.
        Bukkit.getPluginManager().callEvent(new FarmersDelightProduceEvent(
                null, "cooking", recipe.getResult(), blockLoc));
    }

    // canCook is asked for every registered pot several times a second, but the answer only depends on the
    // inventory and on the loaded recipe set. inventoryVersion moves on every inventory write and the
    // recipe manager publishes a generation on every republish, so the pair is a complete key. Both
    // callers hold inventoryLock, which is also what guards every write to these fields.
    private long canCookVersion = -1L;
    private long canCookRecipeGeneration = -1L;
    private boolean canCookResult;

    private boolean canCookInternal() {
        long generation = plugin == null || plugin.getCookingPotRecipes() == null
                ? -1L
                : plugin.getCookingPotRecipes().recipeGeneration();
        if (inventoryVersion == canCookVersion && generation == canCookRecipeGeneration) {
            return canCookResult;
        }
        canCookResult = computeCanCook();
        canCookVersion = inventoryVersion;
        canCookRecipeGeneration = generation;
        return canCookResult;
    }

    private boolean computeCanCook() {
        try {
            if (plugin == null || !FarmersDelightPlugin.isEnabled0()) {
                return false;
            }

            List<ItemStack> inputItems = getIngredientSlotsInternal();
            ItemStack containerItem = getContainerItemInternal();
            CookingPotRecipe previousRecipe = currentRecipe.get();
            if (previousRecipe != null
                    && plugin.getCookingPotRecipes().canCraft(previousRecipe, inputItems)) {
                if (!hasRoomForResult(previousRecipe)) {
                    return false;
                }
                currentRecipe.set(previousRecipe);
                return true;
            }

            CookingPotRecipe recipe = plugin.getCookingPotRecipes()
                    .matchRecipe(inputItems, containerItem, recipeGroupId);

            if (recipe == null) {
                currentRecipe.set(null);
                return false;
            }

            if (!hasRoomForResult(recipe)) {
                return false;
            }

            currentRecipe.set(recipe);
            // Idle to cooking is the only transition worth announcing. Reaching here with a non-null
            // previousRecipe means the previous recipe stopped being craftable and a different one
            // matched in the same pass — a recipe swap, not a start — so nothing is parked. The pot
            // must first fall idle (the recipe == null branch above) for the next match to count.
            if (previousRecipe == null) {
                pendingCookStart.set(recipe);
            }
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private boolean hasRoomForResult(CookingPotRecipe recipe) {
        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir() || result.getAmount() <= 0) {
            return false;
        }
        ItemStack requiredContainer = recipe.needsContainer() ? recipe.getContainer() : null;
        if (recipe.needsContainer() && (requiredContainer == null || requiredContainer.getType().isAir())) {
            return false;
        }
        // One container snapshot belongs to the entire pending batch, including custom multi-slot pots.
        // New meals must fit that batch before they can be served into the output slots.
        if (hasAnyItem(layout.pendingOutputSlots())) {
            ItemStack pendingContainer = mealContainerStack.get();
            boolean pendingNeedsContainer = pendingContainer != null && !pendingContainer.getType().isAir();
            if (recipe.needsContainer() != pendingNeedsContainer
                    || (pendingNeedsContainer && !isSameContainer(requiredContainer, pendingContainer))) {
                return false;
            }
            for (int slot : layout.pendingOutputSlots()) {
                ItemStack pending = inventory[slot];
                if (pending != null && !pending.getType().isAir() && pending.getAmount() > 0
                        && !pending.isSimilar(result)) {
                    return false;
                }
            }
        }
        return hasSpaceFor(layout.pendingOutputSlots(), result);
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

    public boolean doesMealHaveContainer() {
        ItemStack container = mealContainerStack.get();
        return container != null && !container.getType().isAir();
    }
    
    public ItemStack getMealContainer() {
        ItemStack container = mealContainerStack.get();
        return copyOrNull(container);
    }

    public void setMealContainer(ItemStack container) {
        synchronized (inventoryLock) {
            mealContainerStack.set(copyOrNull(container));
            bumpInventoryVersion();
        }
        syncWorldlyContainer();
    }
    
    public boolean isContainerValid(ItemStack containerItem) {
        if (containerItem == null || containerItem.getType().isAir()) return false;
        return isSameContainer(mealContainerStack.get(), containerItem);
    }
    
    public ItemStack useContainerToTakeMeal() {
        return takeMealPortion(1);
    }

    public ItemStack useHeldContainerOnPendingMeal(Player player, ItemStack container) {
        awardUsedRecipes(player);
        TakenMeal meal = useHeldContainerOnPendingMeal(container);
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
                setSlot(pendingSlot, null);
                if (!hasAnyItem(layout.pendingOutputSlots())) {
                    mealContainerStack.set(null);
                }
            }

            syncWorldlyContainer();
            return TakenMeal.from(result);
        }
    }

    public ItemStack takeMealPortionForDelivery(Player player, int requestedAmount) {
        awardUsedRecipes(player);
        TakenMeal meal = takeMealPortionForDelivery(requestedAmount);
        return meal == null ? null : meal.item();
    }

    public TakenMeal takeMealPortionForDelivery(int requestedAmount) {
        return TakenMeal.from(takeMealPortionWithExperience(requestedAmount));
    }

    public ItemStack takeOutputSlotPortionForDelivery(Player player, int outputSlot, int requestedAmount) {
        if (!layout.isOutputSlot(outputSlot)) {
            return null;
        }
        awardUsedRecipes(player);
        TakenMeal meal = takeOutputSlotPortionForDelivery(outputSlot, requestedAmount);
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
            if (isValidSlot(outputSlot) || !layout.isOutputSlot(outputSlot)) {
                return null;
            }
            ItemStack meal = inventory[outputSlot];
            if (meal == null || meal.getAmount() <= 0) return null;

            int amount = Math.max(1, Math.min(requestedAmount, meal.getAmount()));
            SplitItem result = splitItemFromSlot(outputSlot, amount);

            if (meal.getAmount() <= 0) {
                setSlot(outputSlot, null);
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
                setSlot(outputSlot, null);
                tryMovePendingToOutput();
                syncWorldlyContainer();
                return meal;
            }
        }
        return null;
    }

    public ItemStack takeMealWithExperience(Player player) {
        awardUsedRecipes(player);
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
            meal = new SplitItem(item, 0.0D);
            setSlot(outputSlot, null);
            tryMovePendingToOutput();
        }
        syncWorldlyContainer();
        return meal.item();
    }

    public void awardUsedRecipes(Player player) {
        if (usedRecipeTracker.isEmpty()) return;
        World currentWorld = this.world;
        if (currentWorld == null) return;

        for (String recipeId : List.copyOf(usedRecipeTracker.keySet())) {
            Integer craftedAmount = usedRecipeTracker.remove(recipeId);
            if (craftedAmount == null) continue;
            if (plugin == null) continue;
            var recipes = plugin.getCookingPotRecipes();
            if (recipes == null) continue;
            CookingPotRecipe recipe = recipes.getRecipe(recipeId);
            if (recipe == null) continue;
            float exp = recipe.getExperience();
            splitAndSpawnExperience(currentWorld, craftedAmount, exp);
        }
    }

    private void splitAndSpawnExperience(World world, int craftedAmount, float experience) {
        double totalExp = (double) craftedAmount * (double) experience;
        int expValue = (int) Math.floor(totalExp);
        double fraction = totalExp - expValue;
        if (fraction > 0.0D && ThreadLocalRandom.current().nextDouble() < fraction) {
            expValue += 1;
        }
        if (expValue > 0) {
            int amount = expValue;
            Location loc = new Location(world, posKey.x() + 0.5, posKey.y() + 1.0, posKey.z() + 0.5);
            // Reward collection runs on the taking player's region, which need not own the pot's chunk;
            // spawning there would trip Folia's tick-thread check and abort the whole take.
            if (plugin != null) {
                plugin.scheduler().runAt(loc, () -> world.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(amount)));
            }
        }
    }

    public List<ItemStack> returnContainerItems() {
        List<ItemStack> returnedItems = new ArrayList<>();

        synchronized (inventoryLock) {
            for (int i : layout.inputSlots()) {
                ItemStack item = inventory[i];
                if (item != null && !item.getType().isAir()) {
                    returnedItems.add(item.clone());
                    setSlot(i, null);
                }
            }
        }

        if (!returnedItems.isEmpty()) {
            syncWorldlyContainer();
        }
        return returnedItems;
    }

    public void tryMovePendingToOutput() {
        tryMovePendingToOutput(false);
    }

    private void tryMovePendingToOutput(boolean forceSync) {
        boolean changed = false;
        synchronized (inventoryLock) {
            if (!hasAnyItem(layout.pendingOutputSlots())) {
                ItemStack previousContainer = mealContainerStack.getAndSet(null);
                if (previousContainer != null && !previousContainer.getType().isAir()) {
                    changed = true;
                }
            } else {
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
                    addItemToSlots(layout.outputSlots(), moving.item());

                    ItemStack requiredContainer = mealContainerStack.get();
                    if (requiredContainer != null && !requiredContainer.getType().isAir()) {
                        consumeContainerAmount(requiredContainer, movableAmount);
                    }

                    if (pending.getAmount() <= 0) {
                        setSlot(pendingSlot, null);
                    }
                    changed = true;
                }
                if (!hasAnyItem(layout.pendingOutputSlots())) {
                    ItemStack previousContainer = mealContainerStack.getAndSet(null);
                    changed |= previousContainer != null && !previousContainer.getType().isAir();
                }
            }
        }
        if (forceSync || changed) syncWorldlyContainer();
    }

    private boolean storeCookedResult(ItemStack result, CookingPotRecipe recipe) {
        synchronized (inventoryLock) {
            if (!hasRoomForResult(recipe)) {
                return false;
            }
            if (!hasAnyItem(layout.pendingOutputSlots())) {
                mealContainerStack.set(recipe.needsContainer() ? copyOrNull(recipe.getContainer()) : null);
            }
            addItemToSlots(layout.pendingOutputSlots(), result);
            usedRecipeTracker.merge(recipe.getId(), 1, Integer::sum);
            // All serving uses the same capacity check and per-portion container debit.
            tryMovePendingToOutput(true);
            return true;
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

    private boolean isSameContainer(ItemStack required, ItemStack provided) {
        if (required == null || required.getType().isAir()) return true;
        if (provided == null || provided.getType().isAir()) return false;
        if (required.isSimilar(provided)) return true;
        String requiredId = ItemUtils.getCustomItemId(required);
        String providedId = ItemUtils.getCustomItemId(provided);
        if (requiredId != null && providedId != null) {
            return requiredId.equals(providedId);
        }
        return required.isSimilar(provided);
    }

    private int getAvailableContainerAmount(ItemStack requiredContainer) {
        if (requiredContainer == null || requiredContainer.getType().isAir()) {
            return Integer.MAX_VALUE;
        }
        int amount = 0;
        for (int slot : layout.containerSlots()) {
            ItemStack container = inventory[slot];
            if (isSameContainer(requiredContainer, container)) {
                amount += container.getAmount();
            }
        }
        return amount;
    }

    private void consumeContainerAmount(ItemStack requiredContainer, int amount) {
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
            if (!isSameContainer(requiredContainer, container)) {
                continue;
            }
            int consumed = Math.min(remainingAmount, container.getAmount());
            int remaining = container.getAmount() - consumed;
            remainingAmount -= consumed;
            if (remaining > 0) {
                container.setAmount(remaining);
                bumpInventoryVersion();
            } else {
                setSlot(slot, null);
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

    private int slotStackLimit(int slot, ItemStack item) {
        int itemMax = Math.max(1, item.getMaxStackSize());
        return layout.isPendingOutputSlot(slot) ? Math.max(64, itemMax) : itemMax;
    }

    private int getAvailableSpace(int slot, ItemStack item) {
        if (isValidSlot(slot) || item == null || item.getType().isAir()) {
            return 0;
        }
        ItemStack existing = inventory[slot];
        if (existing == null || existing.getType().isAir()) {
            return slotStackLimit(slot, item);
        }
        if (!isSimilarIgnoringStoredExperience(existing, item)) {
            return 0;
        }
        return Math.max(0, slotStackLimit(slot, existing) - existing.getAmount());
    }

    private void addItemToSlot(int slot, ItemStack item) {
        if (isValidSlot(slot)) {
            return;
        }
        if (item == null || item.getType().isAir()) {
            setSlot(slot, null);
            return;
        }

        ItemStack existing = inventory[slot];
        if (isSimilarIgnoringStoredExperience(existing, item)) {
            int maxStack = slotStackLimit(slot, existing);
            int merged = Math.min(maxStack, existing.getAmount() + item.getAmount());
            existing.setAmount(merged);
            bumpInventoryVersion();
            return;
        }

        setSlot(slot, item.clone());
    }

    private void addItemToSlots(int[] slots, ItemStack item) {
        if (item == null || item.getType().isAir() || slots == null || slots.length == 0) {
            return;
        }
        ItemStack pending = item.clone();

        for (int slot : slots) {
            if (pending.getAmount() <= 0) {
                return;
            }
            ItemStack existing = inventory[slot];
            if (existing == null || existing.getType().isAir() || !isSimilarIgnoringStoredExperience(existing, pending)) {
                continue;
            }
            int space = slotStackLimit(slot, existing) - existing.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, pending.getAmount());
            ItemStack moving = pending.clone();
            moving.setAmount(moved);
            addItemToSlot(slot, moving);
            pending.setAmount(pending.getAmount() - moved);
        }

        for (int slot : slots) {
            if (pending.getAmount() <= 0) {
                return;
            }
            ItemStack existing = inventory[slot];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }
            int moved = Math.min(slotStackLimit(slot, pending), pending.getAmount());
            ItemStack moving = pending.clone();
            moving.setAmount(moved);
            addItemToSlot(slot, moving);
            pending.setAmount(pending.getAmount() - moved);
        }
    }

    private SplitItem splitItemFromSlot(int slot, int amount) {
        if (isValidSlot(slot)) {
            return null;
        }
        ItemStack source = inventory[slot];
        if (source == null || source.getAmount() <= 0) {
            return null;
        }
        ItemStack split = source.clone();
        split.setAmount(amount);

        int remainingAmount = source.getAmount() - amount;
        source.setAmount(remainingAmount);
        bumpInventoryVersion();

        return new SplitItem(split, 0.0D);
    }

    private boolean isSimilarIgnoringStoredExperience(ItemStack first, ItemStack second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getType() != second.getType()) {
            return false;
        }
        if (first.isSimilar(second)) {
            return true;
        }

        String firstCustomId = ItemUtils.getCustomItemId(first);
        String secondCustomId = ItemUtils.getCustomItemId(second);
        if (firstCustomId != null || secondCustomId != null) {
            return firstCustomId != null && firstCustomId.equals(secondCustomId);
        }

        return first.isSimilar(second);
    }

    // Pure utility, cannot delegate to ItemUtils — ItemUtils' static initializer depends on
    // Bukkit Registry which isn't available in unit test environments.
    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Adopts the recipe the pot tick is about to advance, restarting the progress bar when the accumulated
     * progress belongs to a different dish. The tick is the only writer of cookingProgress, and the remembered
     * id is replaced only here — never on the passes where no recipe matches — so the bar still restarts when an
     * ingredient swap leaves the pot without a valid recipe for a tick in between. Deliberate deviation from the
     * mod, which keeps its cookTime across a swap and only re-totals it, letting a nearly finished long dish
     * finish a short one instantly.
     */
    public void beginCookingRecipe(String recipeId) {
        if (isDifferentDish(progressRecipeId.getAndSet(recipeId), recipeId)) {
            cookingProgress.set(0);
        }
    }

    /** True when progress accumulated for cookedRecipeId must restart because matchedRecipeId differs. */
    static boolean isDifferentDish(String cookedRecipeId, String matchedRecipeId) {
        return cookedRecipeId != null && !cookedRecipeId.equals(matchedRecipeId);
    }

    /**
     * The progress after elapsedTicks of pot time: a cooking pot advances by those ticks (capped at the
     * duration), an idle one regresses by two per tick — the mod's cookTime behaviour — so an unheated
     * pot, or one whose ingredients no longer match, visibly winds back instead of freezing where it stopped.
     */
    public static int advanceOrDecayProgress(int progress, int duration, int elapsedTicks, boolean cooking) {
        if (elapsedTicks <= 0) {
            return progress;
        }
        if (cooking) {
            return Math.min(Math.max(duration, 0), progress + elapsedTicks);
        }
        return Math.max(0, progress - elapsedTicks * 2);
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
        // progress is a transient counter incremented every tick. Marking this block entity dirty on every change
        // would cause every cooking pot to re-serialize its chunk every tick
        // (defeating "save only changed chunks"). The live value is still persisted by saveData()
        // on chunk save/unload, while meaningful state changes (ingredients/output) are what mark it dirty.
        cookingProgress.set(progress);
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
