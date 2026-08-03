package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.block.entity.SimpleStorageBlockEntityController;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Adds the vacuum behavior of Farmer's Delight's basket on top of CraftEngine's simple_storage_block
 * container. The storage block already supplies the 27-slot GUI, comparator output, hopper I/O,
 * drop-on-break and the six-way facing state; this controller only pulls dropped item entities from the
 * cell the basket faces (its own cell plus one block toward the facing direction) into that same
 * inventory, mirroring the reference BasketBlockEntity.pushItemsTick.
 *
 * The tick is driven by CraftEngine's per-block-entity ticker (registered when the chunk activates and
 * removed when the block is broken or the chunk unloads), which runs every game tick on the region that
 * owns the block regardless of whether a viewer has the GUI open. This is the direct analog of the mod's
 * BlockEntityTicker and avoids the container's own scheduled tick, which only runs while a viewer is open.
 */
public final class BasketVacuumController extends BlockEntityController {

    /** 空闲冷却 tick 数：当篮子无事可做时跳过后续检测，避免空闲篮子每 tick 重复跑昂贵的红石检测和满箱扫描 */
    private static final int NO_OP_COOLDOWN = 10;
    private final int transferCooldownTicks;
    // 篮子是否将内容物推入面向的容器。收集始终无条件执行。
    private final boolean eject;
    // 仅在方块实体自己的区域 tick 线程上读写（CraftEngine 同步方块实体 ticker），因此无需跨线程同步。
    // 初始为负数以匹配原版篮子，原版篮子启动时即准备收集。
    private int transferCooldown = -1;

    public BasketVacuumController(BlockEntity blockEntity, int transferCooldownTicks, boolean eject) {
        super(blockEntity);
        this.transferCooldownTicks = transferCooldownTicks;
        this.eject = eject;
    }

    @Override
    public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world, ImmutableBlockState blockState) {
        return createTickerHelper(BasketVacuumController::tick);
    }

    private static void tick(CEWorld world, BlockPos pos, ImmutableBlockState state, BasketVacuumController controller) {
        controller.vacuum(pos, state);
    }

    private void vacuum(BlockPos pos, ImmutableBlockState state) {
        this.transferCooldown--;
        if (this.transferCooldown > 0) {
            return;
        }
        this.transferCooldown = 0;

        World world = CustomBlockUtils.getBukkitWorld(this.blockEntity);
        if (world == null) {
            return;
        }

        // The basket does nothing while it receives a redstone signal, mirroring the reference
        // BasketBlock ENABLED = !hasNeighborSignal. Block.isBlockIndirectlyPowered maps to the vanilla
        // level.hasNeighborSignal(pos), so the disable state is read live each tick and no block-state
        // property is added. This reads the basket's own block on the region that ticks it; the
        // neighbour-signal scan stays inside the region's owned area, which always keeps a chunk border
        // for block ticking, so it is region-safe on Folia and needs no ownership guard.
        Block ownBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (ownBlock.isBlockIndirectlyPowered()) {
            this.transferCooldown = NO_OP_COOLDOWN;
            return;
        }

        Inventory inventory = storageInventory();
        if (inventory == null) {
            return;
        }

        BlockFace facing = CustomBlockUtils.getFullFacing(state);
        if (facing == null) {
            return;
        }

        int fx = facing.getModX();
        int fy = facing.getModY();
        int fz = facing.getModZ();

        // The faced cell can belong to another region on Folia when the facing is horizontal; reading its
        // block or container from this region's tick thread throws the ownership check. A vertical facing
        // (the basket default) stays in the same column and is always owned. When the faced cell is not
        // owned here the eject branch has no fallback, so it is skipped and the tick falls through to the
        // collect branch, which vacuums only the basket's own cell.
        boolean facedOwned = true;
        if (eject && (fx != 0 || fz != 0)) {
            org.bukkit.Location facedCell = new org.bukkit.Location(world, pos.x() + fx, pos.y() + fy, pos.z() + fz);
            facedOwned = FarmersDelightPlugin.getInstance().scheduler().isOwnedByCurrentRegion(facedCell);
        }

        if (eject && facedOwned) {
            Inventory target = facedContainerInventory(world, pos.x() + fx, pos.y() + fy, pos.z() + fz);
            if (target != null) {
                // The faced cell holds a container: push contents into it hopper-style rather than
                // vacuuming. A full basket still reaches this branch, so isFull only gates the collect
                // branch below.
                if (ejectOneItem(inventory, target)) {
                    this.transferCooldown = this.transferCooldownTicks;
                } else {
                    this.transferCooldown = NO_OP_COOLDOWN;
                }
                return;
            }
        }

        if (isFull(inventory)) {
            this.transferCooldown = NO_OP_COOLDOWN;
            return;
        }

        if (collectItems(world, pos, facing, inventory)) {
            this.transferCooldown = this.transferCooldownTicks;
        } else {
            this.transferCooldown = NO_OP_COOLDOWN;
        }
    }

    /**
     * The live inventory of a vanilla container occupying the given cell, or null when that cell holds no
     * container. The block state is read without a snapshot so writes go through the real block entity,
     * which persists them and updates the container's comparator output. The caller has already confirmed
     * the cell is owned by the current region.
     */
    private static Inventory facedContainerInventory(World world, int x, int y, int z) {
        org.bukkit.block.BlockState facedState = world.getBlockAt(x, y, z).getState(false);
        if (facedState instanceof org.bukkit.block.Container container) {
            return container.getInventory();
        }
        return null;
    }

    /**
     * Moves a single item from the first occupied basket slot whose contents the target accepts, matching
     * the hopper cadence of one item per successful transfer. Returns true when an item moved so the
     * caller applies the transfer cooldown, false when nothing could be inserted (empty basket or the
     * target rejected every stack).
     */
    private static boolean ejectOneItem(Inventory source, Inventory target) {
        ItemStack[] contents = source.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            ItemStack single = stack.clone();
            single.setAmount(1);
            Map<Integer, ItemStack> leftover = target.addItem(single);
            if (!leftover.isEmpty()) {
                continue;
            }
            int remaining = stack.getAmount() - 1;
            if (remaining <= 0) {
                source.setItem(slot, null);
            } else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(remaining);
                source.setItem(slot, reduced);
            }
            return true;
        }
        return false;
    }

    /** The 27-slot inventory owned by the sibling simple_storage_block controller, or null when the block
     *  entity is no longer valid. */
    private Inventory storageInventory() {
        Inventory[] holder = new Inventory[1];
        this.blockEntity.controller.let(SimpleStorageBlockEntityController.class, c -> holder[0] = c.inventory());
        return holder[0];
    }

    private static boolean isFull(Inventory inventory) {
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                return false;
            }
            if (stack.getAmount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Scans the collection area (own cell plus one cell toward facing) for dropped item entities and
     * inserts them into the basket inventory, mirroring Basket.collectItems: an item fully absorbed is
     * removed and stops the scan (returning true so the caller applies the cooldown); a partial merge
     * updates the item entity and the scan continues without claiming a successful transfer.
     */
    private boolean collectItems(World world, BlockPos pos, BlockFace facing, Inventory inventory) {
        int fx = facing.getModX();
        int fy = facing.getModY();
        int fz = facing.getModZ();
        // A horizontal facing reaches into the neighbouring column, which on Folia can belong to another region;
        // scanning it from this region's tick thread throws the region ownership check. When that cell is not
        // owned here, drop it and scan only the basket's own cell this tick. A vertical facing (the default) stays
        // in the same column and is always owned, so it never pays this check. Paper always reports owned.
        boolean includeFaced = true;
        if (fx != 0 || fz != 0) {
            org.bukkit.Location facedCell = new org.bukkit.Location(world, pos.x() + fx, pos.y() + fy, pos.z() + fz);
            includeFaced = FarmersDelightPlugin.getInstance().scheduler().isOwnedByCurrentRegion(facedCell);
        }
        int rx = includeFaced ? fx : 0;
        int ry = includeFaced ? fy : 0;
        int rz = includeFaced ? fz : 0;
        double minX = pos.x() + Math.min(0, rx);
        double minY = pos.y() + Math.min(0, ry);
        double minZ = pos.z() + Math.min(0, rz);
        double maxX = pos.x() + 1 + Math.max(0, rx);
        double maxY = pos.y() + 1 + Math.max(0, ry);
        double maxZ = pos.z() + 1 + Math.max(0, rz);

        // Query only the vacuum box for dropped items, not the whole chunk's entity list. getNearbyEntities is
        // spatially filtered (via the server's entity slices) and returns only Item entities in range, so a
        // chunk dense with custom-block display entities no longer costs a full entity wrap+copy every scan.
        // The box stays inside the basket's own cell (plus the faced cell only when it is region-owned), so on
        // Folia it never reaches into an unowned region; a region-ownership rejection at a chunk edge is caught
        // and the scan is skipped for this tick.
        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        java.util.Collection<Entity> entities;
        try {
            entities = world.getNearbyEntities(box, entity -> entity instanceof Item);
        } catch (Exception regionRejected) {
            return false;
        }

        for (Entity entity : entities) {
            Item item = (Item) entity;
            if (!item.isValid() || item.isDead()) {
                continue;
            }
            // Leave permanently un-pickuppable drops alone: a pickup delay pinned to the never-pickup
            // sentinel marks items other plugins spawn as ground decoration or mechanic markers, which the
            // basket should not swallow. Normal drops carry a short delay that counts down, so they are still
            // collected like the reference mod and vanilla hoppers.
            if (item.getPickupDelay() >= Short.MAX_VALUE) {
                continue;
            }
            Location eloc = item.getLocation();
            if (eloc.getX() < minX || eloc.getX() > maxX
                    || eloc.getY() < minY || eloc.getY() > maxY
                    || eloc.getZ() < minZ || eloc.getZ() > maxZ) continue;
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            Map<Integer, ItemStack> leftover = inventory.addItem(stack.clone());
            if (leftover.isEmpty()) {
                item.remove();
                return true;
            }
            item.setItemStack(leftover.values().iterator().next());
        }
        return false;
    }
}
