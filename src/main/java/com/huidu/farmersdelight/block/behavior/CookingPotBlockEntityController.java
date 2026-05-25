package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
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
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class CookingPotBlockEntityController extends BlockEntityController implements BukkitContainer, WorldlyContainer, InventoryHolder {

    private static final String DATA_VERSION = "data_version";
    private static final String ITEMS = "items";
    private static final String SLOT_EXPERIENCE = "slot_experience";
    private static final String SLOT = "slot";
    private static final String EXPERIENCE = "experience";
    private static final String COOKING_PROGRESS = "cooking_progress";
    private static final String COOKING_DURATION = "cooking_duration";
    private static final String MEAL_CONTAINER = "meal_container";
    private static final int[] INGREDIENT_SLOTS = {0, 1, 2, 3, 4, 5};
    private static final int[] CONTAINER_SLOT = {CookingPotBlockBehavior.SLOT_CONTAINER};
    private static final int[] OUTPUT_SLOT = {CookingPotBlockBehavior.SLOT_OUTPUT};

    private final CookingPotBlockBehavior behavior;
    private final Item[] items = new Item[CookingPotBlockBehavior.INVENTORY_SIZE];
    private final double[] slotExperience = new double[CookingPotBlockBehavior.INVENTORY_SIZE];
    private final Object container;
    private final Inventory inventory;
    private ItemStack mealContainer;
    private int cookingProgress;
    private int cookingDuration = 200;
    private int maxStackSize = 99;

    public CookingPotBlockEntityController(BlockEntity blockEntity, CookingPotBlockBehavior behavior) {
        super(blockEntity);
        this.behavior = behavior;
        Arrays.fill(this.items, Item.empty());
        this.container = CraftEngine.instance().platform().createContainer(this);
        this.inventory = CraftInventoryProxy.INSTANCE.newInstance(this.container);
    }

    public Object container() {
        return this.container;
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        CookingPotBlockEntity entity = getEntityIfLoaded();
        if (entity != null) {
            refreshFromEntity(entity);
            tag.put(this.behavior.customDataKey, saveData(entity));
            return;
        }

        tag.put(this.behavior.customDataKey, saveSnapshotData());
    }

    public static CompoundTag saveData(CookingPotBlockEntity entity) {
        CompoundTag data = new CompoundTag();
        data.putInt(DATA_VERSION, VersionHelper.WORLD_VERSION);
        synchronized (entity.getLock()) {
            data.put(ITEMS, ItemStackUtils.saveBukkitItemsAsListTag(entity.getInventoryInternal()));
            ListTag experienceTag = new ListTag();
            for (int i = 0; i < CookingPotBlockBehavior.INVENTORY_SIZE; i++) {
                double experience = entity.getSlotExperience(i);
                if (experience <= 0.0D) continue;
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt(SLOT, i);
                slotTag.putDouble(EXPERIENCE, experience);
                experienceTag.add(slotTag);
            }
            data.put(SLOT_EXPERIENCE, experienceTag);
        }
        data.putInt(COOKING_PROGRESS, entity.getCookingProgress());
        data.putInt(COOKING_DURATION, entity.getCookingDuration());
        ItemStack mealContainer = entity.getMealContainer();
        if (mealContainer != null && !mealContainer.getType().isAir()) {
            Tag mealContainerTag = ItemStackUtils.saveBukkitItemAsTag(mealContainer);
            if (mealContainerTag != null) {
                data.put(MEAL_CONTAINER, mealContainerTag);
            }
        }
        return data;
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        CompoundTag data = tag.getCompound(this.behavior.customDataKey);
        if (data == null) return;
        loadData(data);
    }

    @Override
    public void loadCustomDataFromItem(Item item) {
        Tag component = item.getComponentAsSparrowTag(DataComponentKeys.BLOCK_ENTITY_DATA);
        if (!(component instanceof CompoundTag tag)) return;
        CompoundTag data = tag.getCompound(this.behavior.customDataKey);
        if (data == null) return;
        loadData(data);
    }

    private void loadData(CompoundTag data) {
        World world = getBukkitWorld();
        if (world == null) return;

        BlockPosKey posKey = new BlockPosKey(this.blockEntity.pos);
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(posKey.toLocation(world));
        if (entity == null) return;

        int dataVersion = data.getInt(DATA_VERSION, Config.itemDataFixerUpperFallbackVersion());
        ItemStack[] items = ItemStackUtils.parseBukkitItems(Optional.ofNullable(data.getList(ITEMS)).orElseGet(ListTag::new),
                CookingPotBlockBehavior.INVENTORY_SIZE,
                dataVersion);
        for (int i = 0; i < CookingPotBlockBehavior.INVENTORY_SIZE; i++) {
            entity.setInventorySlot(i, items[i]);
            entity.setSlotExperience(i, 0.0D);
        }

        ListTag experienceTag = Optional.ofNullable(data.getList(SLOT_EXPERIENCE)).orElseGet(ListTag::new);
        for (int i = 0; i < experienceTag.size(); i++) {
            CompoundTag slotTag = experienceTag.getCompound(i);
            int slot = slotTag.getInt(SLOT, -1);
            if (slot < 0 || slot >= CookingPotBlockBehavior.INVENTORY_SIZE) continue;
            entity.setSlotExperience(slot, slotTag.getDouble(EXPERIENCE, 0.0D));
        }

        entity.setCookingProgress(data.getInt(COOKING_PROGRESS, 0));
        entity.setCookingDuration(data.getInt(COOKING_DURATION, 200));
        Tag mealContainerTag = data.get(MEAL_CONTAINER);
        if (mealContainerTag != null) {
            entity.setMealContainer(ItemStackUtils.parseBukkitItem(mealContainerTag, dataVersion));
        } else {
            entity.setMealContainer(null);
        }

        if (entity.hasStoredContents()) {
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            if (plugin != null && plugin.getTickManager() != null) {
                plugin.getTickManager().markActive(world, posKey, TickManager.BlockType.COOKING_POT);
            }
        }
        refreshFromEntity(entity);
    }

    private CookingPotBlockEntity getOrCreateEntity() {
        World world = getBukkitWorld();
        if (world == null) return null;
        return CookingPotBlockBehavior.getOrCreateBlockEntity(new BlockPosKey(this.blockEntity.pos).toLocation(world));
    }

    void refreshFromEntity(CookingPotBlockEntity entity) {
        for (int i = 0; i < this.items.length; i++) {
            this.items[i] = normalize(BukkitItemManager.instance().wrap(entity.getInventorySlot(i)));
            this.slotExperience[i] = entity.getSlotExperience(i);
        }
        this.cookingProgress = entity.getCookingProgress();
        this.cookingDuration = entity.getCookingDuration();
        this.mealContainer = entity.getMealContainer();
    }

    private CompoundTag saveSnapshotData() {
        CompoundTag data = new CompoundTag();
        data.putInt(DATA_VERSION, VersionHelper.WORLD_VERSION);

        ItemStack[] bukkitItems = new ItemStack[CookingPotBlockBehavior.INVENTORY_SIZE];
        for (int i = 0; i < this.items.length; i++) {
            bukkitItems[i] = asBukkitStack(this.items[i]);
        }
        data.put(ITEMS, ItemStackUtils.saveBukkitItemsAsListTag(bukkitItems));

        ListTag experienceTag = new ListTag();
        for (int i = 0; i < this.slotExperience.length; i++) {
            double experience = this.slotExperience[i];
            if (experience <= 0.0D) continue;
            CompoundTag slotTag = new CompoundTag();
            slotTag.putInt(SLOT, i);
            slotTag.putDouble(EXPERIENCE, experience);
            experienceTag.add(slotTag);
        }
        data.put(SLOT_EXPERIENCE, experienceTag);

        data.putInt(COOKING_PROGRESS, this.cookingProgress);
        data.putInt(COOKING_DURATION, this.cookingDuration);
        if (this.mealContainer != null && !this.mealContainer.getType().isAir()) {
            Tag mealContainerTag = ItemStackUtils.saveBukkitItemAsTag(this.mealContainer);
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

        for (int i = 0; i < this.items.length; i++) {
            entity.setInventorySlot(i, asBukkitStack(this.items[i]));
            if (this.items[i].isEmpty()) {
                this.slotExperience[i] = 0.0D;
            }
            entity.setSlotExperience(i, this.slotExperience[i]);
        }
        entity.tryMovePendingToOutput();
        refreshFromEntity(entity);

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.getTickManager() != null && entity.hasStoredContents()) {
            plugin.getTickManager().markActive(world, new BlockPosKey(this.blockEntity.pos), TickManager.BlockType.COOKING_POT);
        }

        if (this.blockEntity.world != null) {
            this.blockEntity.world.blockEntityChanged(this.blockEntity.pos);
        }
    }

    private Item normalize(Item item) {
        if (item == null || item.isEmpty() || item.count() <= 0) {
            return Item.empty();
        }
        return item.copyWithCount(item.count());
    }

    private ItemStack asBukkitStack(Item item) {
        return item == null || item.isEmpty() ? null : ItemStackUtils.getBukkitStack(item.minecraftItem());
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
        CookingPotBlockEntity entity = getOrCreateEntity();
        if (entity != null) {
            refreshFromEntity(entity);
        }
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
            this.slotExperience[slot] = 0.0D;
        } else {
            result = item.copyWithCount(count);
            double extractedExperience = (this.slotExperience[slot] * count) / item.count();
            this.slotExperience[slot] = Math.max(0.0D, this.slotExperience[slot] - extractedExperience);
            item.shrink(count);
        }
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
        this.slotExperience[slot] = 0.0D;
        return item;
    }

    @Override
    public void setItem(int slot, Item item) {
        if (!isValidSlot(slot)) {
            return;
        }
        this.items[slot] = normalize(item);
        if (this.items[slot].isEmpty()) {
            this.slotExperience[slot] = 0.0D;
        }
        if (!this.items[slot].isEmpty()) {
            int cappedStackSize = Math.min(this.maxStackSize, this.items[slot].maxStackSize());
            if (this.items[slot].count() > cappedStackSize) {
                this.items[slot].count(cappedStackSize);
            }
        }
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
        CookingPotBlockEntity entity = getOrCreateEntity();
        if (entity != null) {
            refreshFromEntity(entity);
        }
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
        Arrays.fill(this.slotExperience, 0.0D);
        this.mealContainer = null;
        this.cookingProgress = 0;
        this.cookingDuration = 200;
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, Item item) {
        return slot >= 0 && slot < CookingPotBlockBehavior.INVENTORY_SIZE && slot != CookingPotBlockBehavior.SLOT_OUTPUT;
    }

    @Override
    public boolean canTakeItem(Object into, int slot, Item item) {
        return slot == CookingPotBlockBehavior.SLOT_OUTPUT;
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        return switch (direction) {
            case UP -> INGREDIENT_SLOTS;
            case DOWN -> OUTPUT_SLOT;
            case NORTH, SOUTH, EAST, WEST -> CONTAINER_SLOT;
            default -> new int[0];
        };
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, Item stack, Direction direction) {
        return switch (direction) {
            case UP -> slot >= 0 && slot < CookingPotBlockBehavior.SLOT_MEAL_DISPLAY;
            case NORTH, SOUTH, EAST, WEST -> slot == CookingPotBlockBehavior.SLOT_CONTAINER;
            default -> false;
        };
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, Item stack, Direction direction) {
        return direction == Direction.DOWN && slot == CookingPotBlockBehavior.SLOT_OUTPUT;
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < this.items.length;
    }

    private World getBukkitWorld() {
        if (this.blockEntity.world == null || this.blockEntity.world.world() == null) {
            return null;
        }
        Object platformWorld = this.blockEntity.world.world().platformWorld();
        return platformWorld instanceof World world ? world : null;
    }
}
