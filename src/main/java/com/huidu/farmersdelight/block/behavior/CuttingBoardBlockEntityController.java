package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
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
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.WorldlyContainer;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
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

public final class CuttingBoardBlockEntityController extends BlockEntityController implements BukkitContainer, WorldlyContainer, InventoryHolder {

    private static final String STORED_ITEM = "stored_item";
    private static final String ITEM_CARVED = "item_carved";
    private static final int[] SLOT = {0};
    private static final int[] EMPTY_SLOTS = {};

    private final CuttingBoardBlockBehavior behavior;
    private final Object container;
    private final Inventory inventory;
    private Item item = Item.empty();
    private boolean itemCarved;
    private int maxStackSize = 99;
    private CompoundTag pendingLoadData;

    public CuttingBoardBlockEntityController(BlockEntity blockEntity, CuttingBoardBlockBehavior behavior) {
        super(blockEntity);
        this.behavior = behavior;
        this.container = CraftEngine.instance().platform().createContainer(this);
        this.inventory = CraftInventoryProxy.INSTANCE.newInstance(this.container);
    }

    public Object container() {
        return this.container;
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        loadPendingDataIfReady();
        if (this.pendingLoadData != null) {
            tag.put(this.behavior.customDataKey(), this.pendingLoadData);
            return;
        }
        World world = getBukkitWorld();
        if (world != null) {
            CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(world, new BlockPosKey(this.blockEntity.pos));
            if (entity != null) {
                refreshFromEntity(entity);
            }
        }
        if (this.item == null || this.item.isEmpty()) return;

        CompoundTag data = new CompoundTag();
        Tag itemTag = ItemStackUtils.saveBukkitItemAsTag(asBukkitStack(this.item));
        if (itemTag != null) {
            data.put(STORED_ITEM, itemTag);
        }
        data.putBoolean(ITEM_CARVED, this.itemCarved);
        tag.put(this.behavior.customDataKey(), data);
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        CompoundTag data = tag.getCompound(this.behavior.customDataKey());
        if (data == null) return;
        queueLoadData(data);
    }

    @Override
    public void loadCustomDataFromItem(Item item) {
        CompoundTag data = CustomBlockUtils.getNestedComponentCompound(item, DataComponentKeys.BLOCK_ENTITY_DATA,
                this.behavior.customDataKey());
        if (data == null) return;
        queueLoadData(data);
    }

    public void loadPendingDataIfReady() {
        CompoundTag data = this.pendingLoadData;
        if (data == null) {
            return;
        }
        if (loadData(data)) {
            this.pendingLoadData = null;
        }
    }

    private void queueLoadData(CompoundTag data) {
        this.pendingLoadData = data;
        loadPendingDataIfReady();
    }

    private boolean loadData(CompoundTag data) {
        World world = getBukkitWorld();
        if (world == null) return false;

        BlockPosKey posKey = new BlockPosKey(this.blockEntity.pos);
        Tag itemTag = data.get(STORED_ITEM);
        if (itemTag == null) return true;

        ItemStack storedItem = ItemStackUtils.parseBukkitItem(itemTag, Config.itemDataFixerUpperFallbackVersion());
        if (storedItem == null || storedItem.getType().isAir()) return true;

        CuttingBoardBlockEntity entity = new CuttingBoardBlockEntity(posKey, world);
        entity.setItem(storedItem, world, posKey, CustomBlockUtils.getFacing(posKey.toLocation(world).getBlock()),
                data.getBoolean(ITEM_CARVED, false));
        CuttingBoardBlockBehavior.putBlockEntity(world, posKey, entity);
        refreshFromEntity(entity);
        return true;
    }

    private CuttingBoardBlockEntity getOrCreateEntity() {
        World world = getBukkitWorld();
        if (world == null) return null;

        BlockPosKey posKey = new BlockPosKey(this.blockEntity.pos);
        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(world, posKey);
        if (entity == null) {
            entity = new CuttingBoardBlockEntity(posKey, world);
            CuttingBoardBlockBehavior.putBlockEntity(world, posKey, entity);
        }
        return entity;
    }

    void refreshFromEntity(CuttingBoardBlockEntity entity) {
        this.item = normalize(BukkitItemManager.instance().wrap(entity.getStoredItem()));
        this.itemCarved = entity.isItemCarved();
    }

    public void setChangedFromEntity(CuttingBoardBlockEntity entity) {
        if (entity != null) {
            refreshFromEntity(entity);
        }
        setChanged();
    }

    private void writeToEntity() {
        World world = getBukkitWorld();
        if (world == null) return;

        BlockPosKey posKey = new BlockPosKey(this.blockEntity.pos);
        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(world, posKey);
        if (entity == null) {
            entity = new CuttingBoardBlockEntity(posKey, world);
            CuttingBoardBlockBehavior.putBlockEntity(world, posKey, entity);
        }

        ItemStack stack = asBukkitStack(this.item);
        if (stack == null || stack.getType().isAir()) {
            entity.clearItem();
            this.itemCarved = false;
        } else {
            entity.setStoredItem(stack, world, posKey, CustomBlockUtils.getFacing(posKey.toLocation(world).getBlock()));
        }

        CustomBlockUtils.markBlockEntityDirty(this.blockEntity);
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
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return this.item == null || this.item.isEmpty();
    }

    @Override
    public Item getItem(int slot) {
        if (!isValidSlot(slot)) return Item.empty();
        CuttingBoardBlockEntity entity = getOrCreateEntity();
        if (entity != null) {
            refreshFromEntity(entity);
        }
        return this.item;
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
            this.item = Item.empty();
            this.itemCarved = false;
        } else {
            result = item.copyWithCount(count);
            item.shrink(count);
        }
        setChanged();
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
        this.item = Item.empty();
        this.itemCarved = false;
        return item;
    }

    @Override
    public void setItem(int slot, Item item) {
        if (!isValidSlot(slot)) return;
        this.item = normalize(item);
        if (this.item.isEmpty()) {
            this.itemCarved = false;
        }
        if (!this.item.isEmpty()) {
            int cappedStackSize = Math.min(this.maxStackSize, this.item.maxStackSize());
            if (this.item.count() > cappedStackSize) {
                this.item.count(cappedStackSize);
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
        CuttingBoardBlockEntity entity = getOrCreateEntity();
        if (entity != null) {
            refreshFromEntity(entity);
        }
        return Arrays.asList(this.item);
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
        this.item = Item.empty();
        this.itemCarved = false;
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, Item item) {
        CuttingBoardBlockEntity entity = getOrCreateEntity();
        return slot == 0 && (entity == null || !entity.hasItem());
    }

    @Override
    public boolean canTakeItem(Object into, int slot, Item item) {
        return slot == 0;
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        return switch (direction) {
            case UP, DOWN -> SLOT;
            default -> EMPTY_SLOTS;
        };
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, Item stack, Direction direction) {
        return direction == Direction.UP && canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, Item stack, Direction direction) {
        return direction == Direction.DOWN && slot == 0;
    }

    private boolean isValidSlot(int slot) {
        return slot == 0;
    }

    private World getBukkitWorld() {
        return CustomBlockUtils.getBukkitWorld(this.blockEntity);
    }
}
