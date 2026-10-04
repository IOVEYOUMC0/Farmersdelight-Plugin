package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.CeItemInterop;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.world.BukkitContainer;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.WorldlyContainer;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import org.bukkit.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class CookingPotBlockEntityController extends BlockEntityController implements BukkitContainer, WorldlyContainer, InventoryHolder {

    private static final String DATA_VERSION = "data_version";
    private static final String ITEMS = "items";
    private static final String COOKING_PROGRESS = "cooking_progress";
    private static final String COOKING_DURATION = "cooking_duration";
    private static final String MEAL_CONTAINER = "meal_container";

    private final FarmersDelightPlugin plugin;
    private final CookingPotBlockBehavior behavior;
    private final CookingPotLayout layout;
    private final Item[] items;
    private final boolean[] dirtySlots;
    // The entity value each slot's shadow was last read from (refreshFromEntity). Lets writeToEntity tell a
    // local hopper in-place grow (shadow changed, entity still equals this baseline) apart from a concurrent
    // entity write by another region's GUI viewer (entity no longer equals this baseline) — so it persists
    // the grow but never clobbers the concurrent write with a stale shadow.
    private final ItemStack[] entityBaseline;
    // Original Bukkit ItemStacks for non-CE items (e.g. MMOItems), preserved through CE wrap/unwrap.
    private final ItemStack[] nonCeOriginal;
    private final Object container;
    private final Inventory inventory;
    private ItemStack mealContainer;
    private int cookingProgress;
    private int cookingDuration = 200;
    private int maxStackSize = 99;
    private boolean allSlotsDirty;
    private volatile CompoundTag pendingLoadData;
    // Snapshot taken when the plugin-side entity is dropped on chunk unload. CraftEngine's chunk cache can
    // serve this same controller object back on a quick reload without ever re-running loadCustomData, so
    // the snapshot both feeds saveCustomData while the entity is gone and re-hydrates the entity on reload.
    private volatile CompoundTag pendingSaveData;
    // Guards loadPendingDataIfReady against re-entry: applying pending data creates the plugin entity,
    // whose creation hook flushes pending data again.
    private volatile boolean applyingPendingLoad;
    // The block pos is fixed for this controller's lifetime; cache the key so getItem/contents
    // (called per slot during container scans) need not reallocate it on every access.
    private BlockPosKey cachedPosKey;

    public CookingPotBlockEntityController(FarmersDelightPlugin plugin, BlockEntity blockEntity, CookingPotBlockBehavior behavior) {
        super(blockEntity);
        this.plugin = plugin;
        this.behavior = behavior;
        this.layout = behavior != null ? behavior.getLayout() : CookingPotLayout.DEFAULT;
        this.items = new Item[this.layout.size()];
        this.dirtySlots = new boolean[this.layout.size()];
        this.entityBaseline = new ItemStack[this.layout.size()];
        this.nonCeOriginal = new ItemStack[this.layout.size()];
        Arrays.fill(this.items, Item.empty());
        this.container = CraftEngine.instance().platform().createContainer(this);
        this.inventory = CraftInventoryProxy.INSTANCE.newInstance(this.container);
    }

    public Object container() {
        return this.container;
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        // Never rehydrate pending data here: doing so may synchronously request the chunk CraftEngine is
        // currently serializing. Reading the plugin entity from its already-loaded map does not load a chunk.
        CompoundTag data = this.pendingSaveData;
        if (data != null) {
            tag.put(this.behavior.getCustomDataKey(), data);
            return;
        }
        data = this.pendingLoadData;
        if (data != null) {
            tag.put(this.behavior.getCustomDataKey(), data);
            return;
        }
        CookingPotBlockEntity entity = getEntityIfLoaded();
        if (entity != null) {
            refreshFromEntity(entity);
            tag.put(this.behavior.getCustomDataKey(), saveData(entity));
            return;
        }
        tag.put(this.behavior.getCustomDataKey(), saveSnapshotData());
    }

    public static CompoundTag saveData(CookingPotBlockEntity entity) {
        CompoundTag data = new CompoundTag();
        data.putInt(DATA_VERSION, VersionHelper.WORLD_VERSION);
        synchronized (entity.getLock()) {
            data.put(ITEMS, ItemStackUtils.saveBukkitItemsAsListTag(entity.getInventoryInternal()));
        }
        data.putInt(COOKING_PROGRESS, entity.getCookingProgress());
        data.putInt(COOKING_DURATION, entity.getCookingDuration());
        ItemStack mealContainer = entity.getMealContainer();
        if (mealContainer != null && !mealContainer.getType().isAir()) {
            Tag mealContainerTag = ItemUtils.saveBukkitItemAsTag(mealContainer);
            if (mealContainerTag != null) {
                data.put(MEAL_CONTAINER, mealContainerTag);
            }
        }
        return data;
    }

    /**
     * The drop's data when only the ready meal is packed: the pending slots' meals plus the serving container
     * they still owe, every other slot empty and no cooking progress. Used where the pot contents are scattered
     * instead of packed — a meal in a pending slot has already been paid for with the ingredients and has not
     * consumed its serving container, so it is packed rather than spilled (dropping it loose would hand out a
     * free bowl) or deleted. The mod packs that slot on every break path.
     */
    public static CompoundTag savePendingMealData(CookingPotBlockEntity entity) {
        CompoundTag data = new CompoundTag();
        data.putInt(DATA_VERSION, VersionHelper.WORLD_VERSION);
        synchronized (entity.getLock()) {
            ItemStack[] inventory = entity.getInventoryInternal();
            ItemStack[] packed = new ItemStack[inventory.length];
            for (int slot : entity.getLayout().pendingOutputSlots()) {
                if (slot >= 0 && slot < packed.length) {
                    packed[slot] = inventory[slot];
                }
            }
            data.put(ITEMS, ItemStackUtils.saveBukkitItemsAsListTag(packed));
        }
        // The packed meal is finished; any in-flight batch belongs to the ingredients that stay behind.
        data.putInt(COOKING_PROGRESS, 0);
        data.putInt(COOKING_DURATION, entity.getCookingDuration());
        ItemStack mealContainer = entity.getMealContainer();
        if (mealContainer != null && !mealContainer.getType().isAir()) {
            Tag mealContainerTag = ItemUtils.saveBukkitItemAsTag(mealContainer);
            if (mealContainerTag != null) {
                data.put(MEAL_CONTAINER, mealContainerTag);
            }
        }
        return data;
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        CompoundTag data = tag.getCompound(this.behavior.getCustomDataKey());
        if (data == null) return;
        queueLoadData(data);
    }

    @Override
    public void loadCustomDataFromItem(Item item) {
        CompoundTag data = getPackedDataFromItem(item);
        if (data == null) return;
        queueLoadData(data);
    }

    public void loadPendingDataIfReady() {
        if (this.applyingPendingLoad) {
            return;
        }
        if (this.pendingLoadData == null && this.pendingSaveData != null) {
            // Chunk reload served from CraftEngine's chunk cache: loadCustomData never ran (no
            // deserialization), so the passivation snapshot is the authoritative state to re-hydrate from.
            this.pendingLoadData = this.pendingSaveData;
            this.pendingSaveData = null;
        }
        CompoundTag data = this.pendingLoadData;
        if (data == null) {
            return;
        }
        this.applyingPendingLoad = true;
        try {
            if (loadData(data)) {
                this.pendingLoadData = null;
            }
        } finally {
            this.applyingPendingLoad = false;
        }
    }

    public void passivate(CookingPotBlockEntity entity) {
        if (entity == null) {
            return;
        }
        refreshFromEntity(entity);
        this.pendingSaveData = saveData(entity);
        CustomBlockUtils.markBlockEntityDirty(this.blockEntity);
    }

    private CompoundTag getPackedDataFromItem(Item item) {
        return CustomBlockUtils.getNestedComponentCompound(item, DataComponentKeys.CUSTOM_DATA, this.behavior.getCustomDataKey());
    }

    public static void loadDataIntoEntity(CookingPotBlockEntity entity, CompoundTag data) {
        if (entity == null || data == null) {
            return;
        }

        int dataVersion = data.getInt(DATA_VERSION, Config.itemDataFixerUpperFallbackVersion());
        ItemStack[] items;
        try {
            items = ItemStackUtils.parseBukkitItems(Optional.ofNullable(data.getList(ITEMS)).orElseGet(ListTag::new),
                    entity.getInventorySize(),
                    dataVersion);
        } catch (RuntimeException e) {
            // Corrupt/version-skewed inventory: load empty rather than aborting the whole block-entity load.
            FarmersDelightPlugin.getInstance().getLogger()
                    .warning("Skipping unreadable cooking pot inventory: " + e.getMessage());
            items = new ItemStack[entity.getInventorySize()];
        }
        for (int i = 0; i < entity.getInventorySize(); i++) {
            entity.setInventorySlot(i, items[i]);
        }

        entity.setCookingProgress(data.getInt(COOKING_PROGRESS, 0));
        entity.setCookingDuration(data.getInt(COOKING_DURATION, 200));
        Tag mealContainerTag = data.get(MEAL_CONTAINER);
        if (mealContainerTag != null) {
            try {
                entity.setMealContainer(ItemStackUtils.parseBukkitItem(mealContainerTag, dataVersion));
            } catch (RuntimeException e) {
                FarmersDelightPlugin.getInstance().getLogger()
                        .warning("Skipping unreadable cooking pot meal container: " + e.getMessage());
                entity.setMealContainer(null);
            }
        } else {
            entity.setMealContainer(null);
        }
    }

    private void queueLoadData(CompoundTag data) {
        // Freshly deserialized/item-packed data is authoritative; discard any stale passivation snapshot.
        this.pendingSaveData = null;
        this.pendingLoadData = data;
        loadPendingDataIfReady();
    }

    private boolean loadData(CompoundTag data) {
        World world = getBukkitWorld();
        if (world == null) return false;

        BlockPosKey posKey = new BlockPosKey(this.blockEntity.pos);
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(posKey.toLocation(world));
        if (entity == null) return false;

        loadDataIntoEntity(entity, data);
        if (entity.hasStoredContents()) {
            if (plugin != null && plugin.getTickManager() != null) {
                plugin.getTickManager().markActive(world, posKey, TickManager.BlockType.COOKING_POT);
            }
        }
        refreshFromEntity(entity);
        return true;
    }

    private CookingPotBlockEntity getOrCreateEntity() {
        World world = getBukkitWorld();
        if (world == null) return null;
        if (this.cachedPosKey == null) {
            this.cachedPosKey = new BlockPosKey(this.blockEntity.pos);
        }
        return CookingPotBlockBehavior.getOrCreateBlockEntity(this.cachedPosKey.toLocation(world));
    }

    void refreshFromEntity(CookingPotBlockEntity entity) {
        boolean hasDirtySlots = hasDirtySlots();
        for (int i = 0; i < this.items.length; i++) {
            if (hasDirtySlots && (this.allSlotsDirty || this.dirtySlots[i])) {
                continue;
            }
            ItemStack entitySlot = entity.getInventorySlot(i);
            if (isEmptyBukkitSlot(entitySlot)) {
                this.items[i] = Item.empty();
                this.nonCeOriginal[i] = null;
                this.entityBaseline[i] = null;
                continue;
            }
            this.items[i] = CeItemInterop.normalize(BukkitItemManager.instance().wrap(entitySlot));
            this.nonCeOriginal[i] = ItemUtils.isCustomItem(entitySlot) ? null : entitySlot.clone();
            // getInventorySlot returns a fresh copy, so this baseline remains stable when setInventorySlot
            // writes later; writeToEntity compares the entity slot against this baseline.
            this.entityBaseline[i] = entitySlot;
        }
        if (!hasDirtySlots) {
            this.cookingProgress = entity.getCookingProgress();
            this.cookingDuration = entity.getCookingDuration();
            this.mealContainer = entity.getMealContainer();
        }
    }

    static boolean isEmptyBukkitSlot(ItemStack item) {
        return item == null || item.isEmpty();
    }

    public void setChangedFromEntity(CookingPotBlockEntity entity) {
        if (entity != null) {
            refreshFromEntity(entity);
        }
        CustomBlockUtils.markBlockEntityDirty(this.blockEntity);
    }

    private CompoundTag saveSnapshotData() {
        CompoundTag data = new CompoundTag();
        data.putInt(DATA_VERSION, VersionHelper.WORLD_VERSION);

        ItemStack[] bukkitItems = new ItemStack[this.items.length];
        for (int i = 0; i < this.items.length; i++) {
            bukkitItems[i] = nonCeOriginal[i] != null ? adjustCount(nonCeOriginal[i], this.items[i])
                    : CeItemInterop.asBukkitStack(this.items[i]);
        }
        data.put(ITEMS, ItemStackUtils.saveBukkitItemsAsListTag(bukkitItems));

        data.putInt(COOKING_PROGRESS, this.cookingProgress);
        data.putInt(COOKING_DURATION, this.cookingDuration);
        if (this.mealContainer != null && !this.mealContainer.getType().isAir()) {
            Tag mealContainerTag = ItemUtils.saveBukkitItemAsTag(this.mealContainer);
            if (mealContainerTag != null) {
                data.put(MEAL_CONTAINER, mealContainerTag);
            }
        }
        return data;
    }

    private CookingPotBlockEntity getEntityIfLoaded() {
        World world = getBukkitWorld();
        if (world == null) return null;
        return CookingPotBlockBehavior.getBlockEntity(world, new BlockPosKey(this.blockEntity.pos));
    }

    private void writeToEntity() {
        CookingPotBlockEntity entity = getOrCreateEntity();
        World world = getBukkitWorld();
        if (entity == null || world == null) return;

        synchronized (entity.getLock()) {
            for (int i = 0; i < this.items.length; i++) {
                boolean slotDirty = this.allSlotsDirty || this.dirtySlots[i];
                ItemStack entityNow = entity.getInventorySlot(i);
                // Non-dirty slot with no local change: nothing to write.
                if (!slotDirty && itemStacksEqual(toBukkitPreserving(i), entityNow)) {
                    continue;
                }
                // A local change is pending: a hopper setItem/removeItem (dirty slot) or a hopper in-place
                // grow (non-dirty, grows getItem without marking dirty). Commit it, but never clobber a
                // concurrent entity write. The shadow was read from the entity at refreshFromEntity (recorded
                // in entityBaseline); if the live entity no longer equals that baseline, another region's GUI
                // viewer wrote this slot under the entity lock — adopt the entity's current value instead of
                // overwriting it with a stale shadow, which would duplicate extracts or lose inserts.
                if (!itemStacksEqual(this.entityBaseline[i], entityNow)) {
                    if (isEmptyBukkitSlot(entityNow)) {
                        this.items[i] = Item.empty();
                        this.nonCeOriginal[i] = null;
                        this.entityBaseline[i] = null;
                    } else {
                        this.items[i] = CeItemInterop.normalize(BukkitItemManager.instance().wrap(entityNow));
                        this.nonCeOriginal[i] = ItemUtils.isCustomItem(entityNow) ? null : entityNow.clone();
                        this.entityBaseline[i] = entityNow;
                    }
                    continue;
                }
                entity.setInventorySlot(i, toBukkitPreserving(i));
            }
            clearDirtySlots();
        }
        entity.tryMovePendingToOutput();
        refreshFromEntity(entity);

        if (plugin != null && plugin.getTickManager() != null && entity.hasStoredContents()) {
            plugin.getTickManager().markActive(world, new BlockPosKey(this.blockEntity.pos), TickManager.BlockType.COOKING_POT);
        }

        CustomBlockUtils.markBlockEntityDirty(this.blockEntity);
    }

    private ItemStack toBukkitPreserving(int slot) {
        if (this.nonCeOriginal[slot] != null) {
            return adjustCount(this.nonCeOriginal[slot], this.items[slot]);
        }
        return CeItemInterop.asBukkitStack(this.items[slot]);
    }

    private static ItemStack adjustCount(ItemStack original, Item ceItem) {
        if (original == null || ceItem == null || ceItem.isEmpty()) return null;
        int ceCount = ceItem.count();
        if (ceCount <= 0) return null;
        ItemStack result = original.clone();
        result.setAmount(ceCount);
        return result;
    }

    private static boolean itemStacksEqual(ItemStack a, ItemStack b) {
        boolean aEmpty = a == null || a.getType().isAir();
        boolean bEmpty = b == null || b.getType().isAir();
        if (aEmpty || bEmpty) {
            return aEmpty && bEmpty;
        }
        return a.equals(b);
    }

    public ItemStack insertStackThroughFace(ItemStack stack, Direction direction) {
        return insertStackThroughFace(stack, direction, false);
    }

    public ItemStack insertStackThroughFace(ItemStack stack, Direction direction, boolean preferEmptySlots) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        ItemStack pending = stack.clone();
        int[] slots = getSlotsForFace(direction);
        if (preferEmptySlots) {
            // Recipe filling supplies one item at a time; choose the least-filled compatible
            // slot so repeated inserts stay balanced instead of piling into the first slot.
            while (pending != null && !pending.getType().isAir() && pending.getAmount() > 0) {
                int target = findLeastFilledSlot(slots, pending, direction);
                if (target < 0) {
                    break;
                }
                ItemStack one = pending.clone();
                one.setAmount(1);
                ItemStack remainder = insertBukkitStackIntoControllerSlot(target, one);
                if (remainder != null && !remainder.getType().isAir()) {
                    break;
                }
                pending.setAmount(pending.getAmount() - 1);
            }
            if (pending == null || pending.getType().isAir() || pending.getAmount() <= 0) {
                setChanged();
                return null;
            }
            if (pending.getAmount() != stack.getAmount()) {
                setChanged();
            }
            return pending;
        }
        for (int slot : slots) {
            if (pending.getAmount() <= 0) {
                break;
            }
            if (!isValidSlot(slot)) {
                continue;
            }
            Item pendingItem = CeItemInterop.normalize(BukkitItemManager.instance().wrap(pending));
            if (pendingItem.isEmpty() || !canPlaceItemThroughFace(slot, pendingItem, direction)) {
                continue;
            }
            pending = insertBukkitStackIntoControllerSlot(slot, pending);
            if (pending == null || pending.getType().isAir()) {
                setChanged();
                return null;
            }
        }
        if (pending.getAmount() != stack.getAmount()) {
            setChanged();
        }
        return pending;
    }

    private int findLeastFilledSlot(int[] slots, ItemStack stack, Direction direction) {
        int target = -1;
        int amount = Integer.MAX_VALUE;
        for (int slot : slots) {
            if (!isValidSlot(slot) || !canPlaceItemThroughFace(slot,
                    CeItemInterop.normalize(BukkitItemManager.instance().wrap(stack)), direction)) {
                continue;
            }
            ItemStack existing = toBukkitPreserving(slot);
            if (!isEmpty(existing) && !existing.isSimilar(stack)) {
                continue;
            }
            int count = isEmpty(existing) ? 0 : existing.getAmount();
            if (count < amount) {
                amount = count;
                target = slot;
            }
        }
        return target;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private ItemStack insertBukkitStackIntoControllerSlot(int slot, ItemStack stack) {
        ItemStack existing = toBukkitPreserving(slot);
        ItemStack pending = stack.clone();
        if (existing == null || existing.getType().isAir()) {
            int moved = Math.min(pending.getAmount(), Math.min(pending.getMaxStackSize(), this.maxStackSize));
            ItemStack placed = pending.clone();
            placed.setAmount(moved);
            setItem(slot, BukkitItemManager.instance().wrap(placed));
            this.nonCeOriginal[slot] = ItemUtils.isCustomItem(placed) ? null : placed.clone();
            pending.setAmount(pending.getAmount() - moved);
            return pending.getAmount() <= 0 ? null : pending;
        }

        if (!existing.isSimilar(pending)) {
            return pending;
        }

        int maxStack = Math.min(existing.getMaxStackSize(), this.maxStackSize);
        int space = maxStack - existing.getAmount();
        if (space <= 0) {
            return pending;
        }

        int moved = Math.min(space, pending.getAmount());
        existing.setAmount(existing.getAmount() + moved);
        setItem(slot, BukkitItemManager.instance().wrap(existing));
        pending.setAmount(pending.getAmount() - moved);
        return pending.getAmount() <= 0 ? null : pending;
    }

    @Override
    public void onOpen(HumanEntity player) {
    }

    @Override
    public void onClose(HumanEntity player) {
    }

    @Override
    public List<HumanEntity> getViewers() {
        return List.of();
    }

    @Override
    public InventoryHolder getOwner() {
        return this;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }

    @Override
    public int containerSize() {
        return this.items.length;
    }

    @Override
    public boolean isEmpty() {
        for (Item item : this.items) {
            if (item != null && !item.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Item getItem(int slot) {
        if (!isValidSlot(slot)) {
            return Item.empty();
        }
        // Live shadow, no per-call refresh: re-pulling would overwrite a hopper's in-place grow
        // before setChanged() persists it (losing items). The shadow stays current via getContainer + the cooking pot tick.
        return this.items[slot];
    }

    @Override
    public Item removeItem(int slot, int count) {
        if (!isValidSlot(slot) || count <= 0) {
            return Item.empty();
        }
        Item item = getItem(slot);
        if (item == null || item.isEmpty()) {
            return Item.empty();
        }

        Item result;
        if (item.count() <= count) {
            result = item;
            this.items[slot] = Item.empty();
        } else {
            result = item.copyWithCount(count);
            item.shrink(count);
        }
        markDirty(slot);
        this.setChanged();
        return result;
    }

    @Override
    public Item removeItemNoUpdate(int slot) {
        if (!isValidSlot(slot)) {
            return Item.empty();
        }
        Item item = getItem(slot);
        if (item == null || item.isEmpty()) {
            return Item.empty();
        }

        this.items[slot] = Item.empty();
        markDirty(slot);
        this.setChanged();
        return item;
    }

    @Override
    public void setItem(int slot, Item item) {
        if (!isValidSlot(slot)) {
            return;
        }
        this.items[slot] = CeItemInterop.normalize(item);
        if (this.items[slot].isEmpty()) {
            this.nonCeOriginal[slot] = null;
        }
        if (!this.items[slot].isEmpty()) {
            int cappedStackSize = Math.min(this.maxStackSize, this.items[slot].maxStackSize());
            if (this.items[slot].count() > cappedStackSize) {
                this.items[slot].count(cappedStackSize);
            }
        }
        markDirty(slot);
    }

    @Override
    public int maxStackSize() {
        return this.maxStackSize;
    }

    @Override
    public void setChanged() {
        writeToEntity();
    }

    @Override
    public boolean stillValid(Player player) {
        WorldPosition position = this.position();
        return position != null && player.canInteractPoint(position.toVec3d(), player.getCachedInteractionRange());
    }

    @Override
    public List<Item> contents() {
        // See getItem: the shadow is kept current by getContainer / the cooking pot tick, so no per-call refresh is needed.
        return Arrays.asList(this.items);
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStackSize = Math.max(1, size);
    }

    @Override
    public WorldPosition position() {
        if (this.blockEntity.world == null || this.blockEntity.world.world == null) {
            return null;
        }
        return new WorldPosition(this.blockEntity.world.world, this.blockEntity.pos.x(), this.blockEntity.pos.y(), this.blockEntity.pos.z());
    }

    @Override
    public void clearContent() {
        Arrays.fill(this.items, Item.empty());
        Arrays.fill(this.nonCeOriginal, null);
        this.mealContainer = null;
        this.cookingProgress = 0;
        this.cookingDuration = 200;
        this.allSlotsDirty = true;
        Arrays.fill(this.dirtySlots, true);
        setChanged();
    }

    private void markDirty(int slot) {
        if (isValidSlot(slot)) {
            this.dirtySlots[slot] = true;
        }
    }

    private boolean hasDirtySlots() {
        if (this.allSlotsDirty) {
            return true;
        }
        for (boolean dirty : this.dirtySlots) {
            if (dirty) {
                return true;
            }
        }
        return false;
    }

    private void clearDirtySlots() {
        this.allSlotsDirty = false;
        Arrays.fill(this.dirtySlots, false);
    }

    @Override
    public boolean canPlaceItem(int slot, Item item) {
        return isValidSlot(slot) && (layout.isInputSlot(slot) || layout.isContainerSlot(slot))
                && !isNestingHazard(item);
    }

    private boolean isNestingHazard(Item item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        if (item.id() != null && Constants.BLOCK_COOKING_POT.equals(item.id().toString())) {
            return true;
        }
        // A stored inventory in any form: CE/FD block-entity containers (block_entity_data), vanilla shulker
        // boxes (minecraft:container) and bundles (minecraft:bundle_contents). Nesting any of these into the pot
        // lets its payload be grown recursively into an NBT bomb.
        return CustomBlockUtils.getComponentCompound(item, DataComponentKeys.BLOCK_ENTITY_DATA) != null
                || item.hasComponent(DataComponentKeys.CONTAINER)
                || item.hasComponent(DataComponentKeys.BUNDLE_CONTENTS);
    }

    @Override
    public boolean canTakeItem(Object into, int slot, Item item) {
        return layout.isOutputSlot(slot);
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        return switch (direction) {
            case UP -> layout.inputSlots();
            case DOWN -> layout.outputSlots();
            case NORTH, SOUTH, EAST, WEST -> layout.containerSlots();
            default -> new int[0];
        };
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, Item stack, Direction direction) {
        if (isNestingHazard(stack)) {
            return false;
        }
        return switch (direction) {
            case UP -> layout.isInputSlot(slot);
            case NORTH, SOUTH, EAST, WEST -> layout.isContainerSlot(slot);
            default -> false;
        };
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, Item stack, Direction direction) {
        return direction == Direction.DOWN && layout.isOutputSlot(slot);
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < this.items.length;
    }

    private World getBukkitWorld() {
        return CustomBlockUtils.getBukkitWorld(this.blockEntity);
    }
}
