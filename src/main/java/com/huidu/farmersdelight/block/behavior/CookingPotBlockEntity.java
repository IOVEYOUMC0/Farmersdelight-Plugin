package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.FarmersDelightCookStartEvent;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CookingPotBlockEntity {

    // Per-slot stack ceiling used by the comparator fill fraction, matching the default slot limit of the
    // ItemStackHandler the mod's pot uses.
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
    private final Map<String, Integer> usedRecipeTracker = new HashMap<>();
    private final AtomicInteger cookingProgress = new AtomicInteger(0);
    private final AtomicInteger cookingDuration = new AtomicInteger(200);
    private final AtomicBoolean hasHeatSource = new AtomicBoolean(false);
    private final AtomicReference<CookingPotRecipe> currentRecipe = new AtomicReference<>(null);
    private final AtomicReference<String> lastRecipeId = new AtomicReference<>(null);
    // Recipe recorded by canCookInternal on the no-match to match transition, parked here until the
    // caller has released inventoryLock/cookingLock and can dispatch FarmersDelightCookStartEvent
    // without holding this block entity's monitors. Only the transition is recorded, so a pot that
    // keeps cooking the same batch parks nothing and the event stays an edge, not a per-tick signal.
    private final AtomicReference<CookingPotRecipe> pendingCookStart = new AtomicReference<>(null);
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

    /** Runs action while holding the inventory lock, so a caller can make a compound read-modify-write
     * (e.g. the GUI's authoritative-state refresh then write-back) atomic against the cook tick, which consumes
     * ingredients under this same lock. Re-entrant: the block entity's own locked accessors nest safely. */
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
            this.layout = newLayout;
            this.inventory = Arrays.copyOf(oldInventory, newLayout.size());
            // 替换整个数组视为库存变更；递增版本号使 GUI 重新扫描。
            inventoryVersion++;
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

    // Must be called while holding inventoryLock: all writes to the inventory array funnel through here,
    // also bumping the version for the GUI to cheaply detect "did the pot change". version++ runs under the lock, so the non-atomic increment is safe.
    private void setSlot(int slot, ItemStack item) {
        inventory[slot] = item;
        inventoryVersion++;
    }

    // Same purpose as setSlot's version bump, for the paths that shrink a stack in place instead of replacing it.
    // An in-place setAmount leaves the array reference untouched, so without this an open GUI keeps showing the
    // pre-consumption count until something else makes it rescan. Callers must hold inventoryLock.
    private void bumpInventoryVersion() {
        inventoryVersion++;
    }

    // Inventory version for the GUI to read; the GUI can skip an input-slot rescan when the version is unchanged.
    public long getInventoryVersion() {
        return inventoryVersion;
    }

    public void setInventorySlot(int slot, ItemStack item) {
        synchronized (inventoryLock) {
            if (!isValidSlot(slot)) {
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

    /** Comparator strength for this pot, reproducing the mod's MathUtils.calcRedstoneFromItemHandler over the
     * full inventory: the per-slot fill fractions are averaged across all slots (inputs, meal, container and
     * output alike), scaled to 14, floored, and lifted by 1 whenever any slot holds something. An empty pot
     * reads 0; a pot with a single item in one slot reads 1. */
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
            if (!isValidSlot(slot)) {
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
        } else {
            setSlot(slot, null);
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

    /** Headline meal item for the broken-pot lore + fill bar: prefer the pending-output slot
     *  (the just-cooked meal still inside the pot), fall back to output when pending is empty.
     *  Differs from #getMealDisplayItem(), which only ever reads the output slot. */
    public ItemStack getPackedMealDisplayItem() {
        synchronized (inventoryLock) {
            ItemStack pending = getFirstItem(layout.pendingOutputSlots());
            return pending != null ? pending : getFirstItem(layout.outputSlots());
        }
    }

    /** Only checks whether the output slots hold a result, without cloning items (for high-frequency queries like comparator signals). */
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

    /** Counts filled input slots without copying the inventory (for redstone comparator signals). */
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
        if (recipe == null) return;

        synchronized (inventoryLock) {
            consumeIngredientsInternal(recipe, world, blockLoc);
        }
        syncWorldlyContainer();
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

            setSlot(i, pending.clone());
            return null;
        }

        return pending;
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

        // Ingredients whose container vanilla does not expose as a crafting remainder (fish buckets, stews,
        // potions). Consulted only after the real remainder, matching the mod's ordering.
        ItemStack override = CookingPotIngredientRemainders.getRemainder(item, amount);
        if (override != null) {
            return override;
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

            Key itemKey = Key.of("minecraft:" + item.getType().name().toLowerCase(java.util.Locale.ROOT));
            String itemKeyId = itemKey.toString();
            // Route the vanilla-tag membership test through the recipe manager's memoized lookup (O(1)
            // contains) instead of streaming CraftEngine's tag list fresh on every probe.
            var recipes = FarmersDelightPlugin.getInstance().getCookingPotRecipes();
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

    public boolean canCook() {
        boolean result;
        synchronized (inventoryLock) {
            result = canCookInternal();
        }
        // Outside the monitor: a listener may read or even mutate this pot without deadlocking.
        firePendingCookStart();
        return result;
    }

    /**
     * Raises FarmersDelightCookStartEvent once for a recipe match that was recorded while the locks
     * were held. Both entry points that can record one (canCook and finishCooking) call this after
     * releasing every monitor, so third-party listener code never runs under this block entity's lock.
     * The pending slot is cleared by the poll, so a recipe is announced exactly once even if both
     * paths run in the same tick.
     */
    private void firePendingCookStart() {
        CookingPotRecipe started = pendingCookStart.getAndSet(null);
        if (started == null || !FarmersDelightPlugin.isEnabled0()) {
            return;
        }
        World currentWorld = this.world;
        Location location = currentWorld == null
                ? null
                : new Location(currentWorld, posKey.x(), posKey.y(), posKey.z());
        org.bukkit.Bukkit.getPluginManager().callEvent(new FarmersDelightCookStartEvent(
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

    /** The body of finishCooking. Callers must hold inventoryLock then cookingLock, in that order. */
    private boolean finishCookingLocked(World world, Location blockLoc) {
        CookingPotRecipe recipe = currentRecipe.get();
        if (recipe == null) {
            if (!canCookInternal()) return false;
            recipe = currentRecipe.get();
            if (recipe == null) return false;
        } else {
            // The cached recipe may become invalid between canCook and finishCooking if inputs change (e.g. cross-region GUI sync,
            // hopper interaction); re-validate under the lock before producing, to avoid conjuring a result from zero/insufficient ingredients (item duping).
            FarmersDelightPlugin instance = FarmersDelightPlugin.getInstance();
            if (instance == null || !instance.getCookingPotRecipes()
                    .canCraft(recipe, getIngredientSlotsInternal(), getContainerItemInternal())) {
                currentRecipe.set(null);
                lastRecipeId.set(null);
                return false;
            }
        }

        ItemStack resultItem = recipe.getResult();
        if (resultItem == null) return false;

        ItemStack outputItem = resultItem.clone();

        boolean stored = storeCookedResult(outputItem, recipe);
        if (!stored) {
            cookingProgress.set(getCookingDuration());
            syncWorldlyContainer();
            return false;
        }

        consumeIngredientsInternal(recipe, world, blockLoc);
        cookingProgress.set(0);
        currentRecipe.set(null);
        lastRecipeId.set(null);

        syncWorldlyContainer();
        return true;
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
                if (!hasRoomForResult(previousRecipe)) {
                    return false;
                }
                lastRecipeId.set(previousRecipe.getId());
                currentRecipe.set(previousRecipe);
                return true;
            }

            CookingPotRecipe recipe = instance.getCookingPotRecipes()
                    .matchRecipe(inputItems, containerItem, recipeGroupId);

            if (recipe == null) {
                currentRecipe.set(null);
                lastRecipeId.set(null);
                return false;
            }

            if (!hasRoomForResult(recipe)) {
                return false;
            }

            String newRecipeId = recipe.getId();
            String lastId = lastRecipeId.get();

            if (lastId != null && !newRecipeId.equals(lastId)) {
                cookingProgress.set(0);
            }

            lastRecipeId.set(newRecipeId);

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

    /**
     * True when the finished result would actually fit somewhere. Mirrors the routing storeCookedResult uses,
     * without mutating anything. The original mod gates cooking on the same condition, so a pot whose meal slot
     * is full stops cooking instead of running the timer out and failing to store: without this the pot keeps
     * re-completing, and the player sees one more portion produced than the slot can hold.
     * Callers must hold inventoryLock.
     */
    private boolean hasRoomForResult(CookingPotRecipe recipe) {
        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir()) {
            return false;
        }
        if (recipe.needsContainer()) {
            ItemStack requiredContainer = recipe.getContainer();
            if (getMovableOutputAmount(result) >= result.getAmount()
                    && getAvailableContainerAmount(requiredContainer) >= result.getAmount()) {
                return true;
            }
            return hasSpaceFor(layout.pendingOutputSlots(), result);
        }
        return hasSpaceFor(layout.outputSlots(), result)
                || hasSpaceFor(layout.pendingOutputSlots(), result);
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

        // For each recipe ingredient, charge ONE unit of consumption to a slot — preferring slots not yet used
        // in this cook cycle (Forge's "shrink each filled slot by 1" behavior for the exact case), falling back
        // to re-using a slot with remaining stacked amount (covers the consolidated case: one slot with 2 cocoa
        // satisfying a 2-cocoa recipe). Without this, a recipe with the same ingredient listed twice
        // (e.g. 2× cocoa) would greedily pull both units from the first matching slot, leaving the second
        // identical slot untouched: e.g. [4 cocoa, 4 cocoa, …] -> after 2 cooks: [empty, 4 cocoa, …].
        int[] slots = layout.inputSlots();
        int[] consume = new int[slots.length];
        List<ItemStack> remainders = new ArrayList<>();

        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            int idx = pickConsumptionSlot(slots, consume, ingredient, true);
            if (idx < 0) {
                idx = pickConsumptionSlot(slots, consume, ingredient, false);
            }
            if (idx < 0) {
                // canCook should have prevented this; defensive no-op.
                continue;
            }
            ItemStack slotItem = inventory[slots[idx]];
            ItemStack remainder = getCraftingRemainder(slotItem, 1);
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
                setSlot(i, null);
            } else {
                slotItem.setAmount(newAmount);
                bumpInventoryVersion();
            }
        }

        if (world != null && blockLoc != null && !remainders.isEmpty()) {
            ejectRemainders(world, blockLoc, remainders);
        }
    }

    /** Picks a slot index to charge one consumption unit of ingredient. When preferFresh is
     * true, skips slots already used in this cook cycle so 2× same-ingredient recipes naturally pull one
     * from each matching slot. Returns -1 when no slot is eligible (caller falls back to a non-fresh pass). */
    private int pickConsumptionSlot(int[] slots, int[] consume, RecipeIngredient ingredient, boolean preferFresh) {
        for (int idx = 0; idx < slots.length; idx++) {
            if (preferFresh && consume[idx] > 0) continue;
            ItemStack slotItem = inventory[slots[idx]];
            if (slotItem == null || slotItem.getType().isAir()) continue;
            if (consume[idx] >= slotItem.getAmount()) continue;
            if (!matchesIngredient(slotItem, ingredient)) continue;
            return idx;
        }
        return -1;
    }

    /** Pops empty-container remainders (buckets, bottles) out the pot's LEFT side — counter-clockwise of its
     * facing — with a small horizontal push + upward hop. Freeing the input slot is the point: keeping
     * remainders in the slot clogs hopper-fed pots. Falls back to dropping at pot center when facing can't
     * be read (block already gone). */
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

    /** Bukkit has no built-in BlockFace.counterClockWise(); this returns the horizontal CCW
     * neighbor, matching Forge's Direction.getCounterClockWise() for N/S/E/W. Null for any
     * non-horizontal or unknown facing — caller falls back to the centered drop. */
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
            if (!isValidSlot(outputSlot) || !layout.isOutputSlot(outputSlot)) {
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

        for (Map.Entry<String, Integer> entry : usedRecipeTracker.entrySet()) {
            String recipeId = entry.getKey();
            int craftedAmount = entry.getValue();
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            if (plugin == null) continue;
            var recipes = plugin.getCookingPotRecipes();
            if (recipes == null) continue;
            CookingPotRecipe recipe = recipes.getRecipe(recipeId);
            if (recipe == null) continue;
            float exp = recipe.getExperience();
            splitAndSpawnExperience(currentWorld, craftedAmount, exp);
        }
        usedRecipeTracker.clear();
    }

    private void splitAndSpawnExperience(World world, int craftedAmount, float experience) {
        double totalExp = (double) craftedAmount * (double) experience;
        int expValue = (int) Math.floor(totalExp);
        double fraction = totalExp - expValue;
        if (fraction > 0.0D && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < fraction) {
            expValue += 1;
        }
        if (expValue > 0) {
            int amount = expValue;
            Location loc = new Location(world, posKey.x() + 0.5, posKey.y() + 1.0, posKey.z() + 0.5);
            world.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(amount));
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
                addItemToSlots(layout.outputSlots(), moving.item());

                ItemStack requiredContainer = mealContainerStack.get();
                if (requiredContainer != null && !requiredContainer.getType().isAir()) {
                    consumeContainerAmount(requiredContainer, movableAmount);
                }

                if (pending.getAmount() <= 0) {
                    setSlot(pendingSlot, null);
                }
            }
            if (!hasAnyItem(layout.pendingOutputSlots())) {
                mealContainerStack.set(null);
            }
        }
        syncWorldlyContainer();
    }

    private boolean storeCookedResult(ItemStack result, CookingPotRecipe recipe) {
        synchronized (inventoryLock) {
            if (!recipe.needsContainer()) {
                if (hasSpaceFor(layout.outputSlots(), result)) {
                    addItemToSlots(layout.outputSlots(), result);
                    usedRecipeTracker.merge(recipe.getId(), 1, Integer::sum);
                    return true;
                }

                if (hasSpaceFor(layout.pendingOutputSlots(), result)) {
                    addItemToSlots(layout.pendingOutputSlots(), result);
                    mealContainerStack.set(null);
                    usedRecipeTracker.merge(recipe.getId(), 1, Integer::sum);
                    return true;
                }
                return false;
            }

            ItemStack requiredContainer = recipe.getContainer();
            int directMove = getMovableOutputAmount(result);
            int availableContainers = getAvailableContainerAmount(requiredContainer);
            if (directMove >= result.getAmount() && availableContainers >= result.getAmount()) {
                addItemToSlots(layout.outputSlots(), result);
                consumeContainerAmount(requiredContainer, result.getAmount());
                mealContainerStack.set(null);
                usedRecipeTracker.merge(recipe.getId(), 1, Integer::sum);
                return true;
            }

            if (hasSpaceFor(layout.pendingOutputSlots(), result)) {
                addItemToSlots(layout.pendingOutputSlots(), result);
                mealContainerStack.set(copyOrNull(requiredContainer));
                usedRecipeTracker.merge(recipe.getId(), 1, Integer::sum);
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

    /** True if provided is the container required demands (custom-id match, else
     *  isSimilar). Unlike #isContainerValid this validates against the passed argument, not
     *  the mealContainerStack field — needed for the direct-store path where the field is still null
     *  (a null field made isContainerValid accept ANY item, so a wrong container in a C-slot was
     *  counted and consumed). required null/air = no requirement (matches anything). */
    private boolean isSameContainer(ItemStack required, ItemStack provided) {
        if (required == null || required.getType().isAir()) return true;
        if (provided == null || provided.getType().isAir()) return false;
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

    /**
     * Per-slot stack limit, mirroring the original mod's meal-display override: the pending-output
     * slot (the in-pot meal) holds at least 64 regardless of the item's own max stack size — bowl
     * foods stack to 16 elsewhere but the pot accumulates 64 servings before cooking stops. Every
     * other slot uses the item's real limit. The pending slot is display-only in the GUI (clicks are
     * cancelled) and unreachable by hoppers, so the oversized stack never enters player inventories.
     */
    private int slotStackLimit(int slot, ItemStack item) {
        int itemMax = Math.max(1, item.getMaxStackSize());
        return layout.isPendingOutputSlot(slot) ? Math.max(64, itemMax) : itemMax;
    }

    private int getAvailableSpace(int slot, ItemStack item) {
        if (!isValidSlot(slot) || item == null || item.getType().isAir()) {
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
        if (!isValidSlot(slot)) {
            return;
        }
        if (item == null || item.getType().isAir()) {
            setSlot(slot, null);
            return;
        }

        ItemStack existing = inventory[slot];
        if (existing != null && isSimilarIgnoringStoredExperience(existing, item)) {
            int maxStack = slotStackLimit(slot, existing);
            int merged = Math.min(maxStack, existing.getAmount() + item.getAmount());
            existing.setAmount(merged);
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
        if (!isValidSlot(slot)) {
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

        return new SplitItem(split, 0.0D);
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

    // Pure utility, cannot delegate to ItemUtils — ItemUtils' static initializer depends on
    // Bukkit Registry which isn't available in unit test environments.
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
        // progress is a transient counter incremented every tick. Marking this block entity dirty on every change
        // would cause every cooking pot to re-serialize its chunk every tick
        // (defeating "save only changed chunks"). The live value is still persisted by saveData()
        // on chunk save/unload, while meaningful state changes (ingredients/output) are what mark it dirty.
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
