package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.SkilletManager;
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
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.WorldlyContainer;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.inventory.CraftInventoryProxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public final class SkilletBlockEntityController extends BlockEntityController implements BukkitContainer, WorldlyContainer, InventoryHolder {
    static final String DATA_KEY = "farmersdelight:skillet";
    private static final int SLOT_INDEX = 0;
    private static final int[] INPUT_SLOT = {SLOT_INDEX};
    private static final int[] EMPTY_SLOTS = {};

    private final Object container;
    private final Inventory inventory;
    private Item item = Item.empty();
    private int maxStackSize = 99;
    private CompoundTag pendingLoadData;

    public SkilletBlockEntityController(BlockEntity blockEntity) {
        super(blockEntity);
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
            tag.put(DATA_KEY, this.pendingLoadData);
            return;
        }
        SkilletManager manager = getManager();
        World world = getBukkitWorld();
        if (manager == null || world == null) {
            return;
        }
        Map<String, Object> data = manager.exportSkilletData(world, new BlockPosKey(this.blockEntity.pos));
        if (data == null || data.isEmpty()) {
            return;
        }
        tag.put(DATA_KEY, SimpleBlockEntityData.save(data));
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        CompoundTag data = tag.getCompound(DATA_KEY);
        if (data == null) return;
        queueLoadData(data);
    }

    @Override
    public void loadCustomDataFromItem(Item item) {
        CompoundTag data = CustomBlockUtils.getNestedComponentCompound(item, DataComponentKeys.BLOCK_ENTITY_DATA, DATA_KEY);
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

    private void queueLoadData(CompoundTag tag) {
        this.pendingLoadData = tag;
        loadPendingDataIfReady();
    }

    private boolean loadData(CompoundTag tag) {
        SkilletManager manager = getManager();
        World world = getBukkitWorld();
        if (manager == null || world == null) {
            return false;
        }
        manager.loadSkillet(world, new BlockPosKey(this.blockEntity.pos), SimpleBlockEntityData.load(tag,
                "storedItem", "skilletStack"));
        return true;
    }

    public ItemStack insertStackThroughFace(ItemStack stack, Direction direction) {
        if (direction == null || direction == Direction.DOWN || stack == null || stack.getType().isAir()) {
            return stack == null ? null : stack.clone();
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        SkilletManager manager = getManager();
        Location location = getLocation();
        if (plugin == null || !plugin.isSkilletHopperInteractionsEnabled() || manager == null || location == null) {
            return stack.clone();
        }

        ItemStack result = manager.insertHopperInput(location, stack);
        refreshFromManager();
        return result;
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

    /** 从 manager 重新同步缓存的物品。在每次容器交互开始时调用。 */
    public void syncFromManager() {
        refreshFromManager();
    }

    @Override
    public boolean isEmpty() {
        return this.item == null || this.item.isEmpty();
    }

    @Override
    public Item getItem(int slot) {
        if (!isValidSlot(slot)) {
            return Item.empty();
        }
        return this.item;
    }

    @Override
    public Item removeItem(int slot, int count) {
        return Item.empty();
    }

    @Override
    public Item removeItemNoUpdate(int slot) {
        return Item.empty();
    }

    @Override
    public void setItem(int slot, Item item) {
        if (!isValidSlot(slot)) {
            return;
        }
        this.item = normalize(item);
        writeSnapshotToManager();
    }

    private void writeSnapshotToManager() {
        ItemStack target = asBukkitStack(this.item);
        if (target == null || target.getType().isAir()) {
            refreshFromManager();
            return;
        }

        SkilletManager manager = getManager();
        Location location = getLocation();
        if (manager == null || location == null) {
            return;
        }
        int cap = Math.min(this.maxStackSize, target.getMaxStackSize());
        if (target.getAmount() > cap) {
            target.setAmount(cap);
        }

        ItemStack current = manager.getStoredItemSnapshot(location);
        if (current == null || current.getType().isAir()) {
            manager.insertHopperInput(location, target);
            refreshFromManager(manager, location);
            return;
        }
        if (!current.isSimilar(target) || target.getAmount() <= current.getAmount()) {
            refreshFromManager(manager, location);
            return;
        }

        ItemStack addition = target.clone();
        addition.setAmount(target.getAmount() - current.getAmount());
        manager.insertHopperInput(location, addition);
        refreshFromManager(manager, location);
    }

    @Override
    public int maxStackSize() {
        return this.maxStackSize;
    }

    @Override
    public void setChanged() {
        writeSnapshotToManager();
        CustomBlockUtils.markBlockEntityDirty(this.blockEntity);
    }

    @Override
    public boolean stillValid(Player player) {
        WorldPosition position = this.position();
        return position != null && player.canInteractPoint(position.toVec3d(), player.getCachedInteractionRange());
    }

    @Override
    public boolean canPlaceItem(int slot, Item item) {
        if (!isValidSlot(slot)) {
            return false;
        }
        SkilletManager manager = getManager();
        Location location = getLocation();
        ItemStack stack = asBukkitStack(item);
        return manager != null && location != null && manager.canAcceptHopperInput(location, stack);
    }

    @Override
    public boolean canTakeItem(Object into, int slot, Item item) {
        return false;
    }

    @Override
    public void clearContent() {
    }

    @Override
    public List<Item> contents() {
        return List.of(this.item == null ? Item.empty() : this.item);
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
    public int[] getSlotsForFace(Direction direction) {
        return direction == null || direction == Direction.DOWN ? EMPTY_SLOTS : INPUT_SLOT;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, Item stack, Direction direction) {
        return direction != null && direction != Direction.DOWN && canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, Item stack, Direction direction) {
        return false;
    }

    private boolean isValidSlot(int slot) {
        return slot == SLOT_INDEX;
    }

    private void refreshFromManager() {
        SkilletManager manager = getManager();
        Location location = getLocation();
        if (manager == null || location == null) {
            this.item = Item.empty();
            return;
        }
        refreshFromManager(manager, location);
    }

    private void refreshFromManager(SkilletManager manager, Location location) {
        this.item = normalize(BukkitItemManager.instance().wrap(manager.getStoredItemSnapshot(location)));
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

    private Location getLocation() {
        World world = getBukkitWorld();
        if (world == null) {
            return null;
        }
        return new BlockPosKey(this.blockEntity.pos).toLocation(world);
    }

    private World getBukkitWorld() {
        return CustomBlockUtils.getBukkitWorld(this.blockEntity);
    }

    private SkilletManager getManager() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null ? null : plugin.getSkilletManager();
    }
}
