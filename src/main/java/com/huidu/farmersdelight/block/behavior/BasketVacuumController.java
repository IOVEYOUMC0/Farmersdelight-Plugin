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
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class BasketVacuumController extends BlockEntityController {

    private static final int NO_OP_COOLDOWN = 10;
    // Idle baskets re-poll the redstone signal at this cadence. Once a signal is observed the poll tightens
    // to every tick so the basket resumes promptly when the signal drops; the cached state turns the hot
    // isBlockIndirectlyPowered (6-neighbour signal scan) from a per-cooldown-expiry call into ~1/s per
    // idle basket regardless of the configured transfer cooldown.
    private static final int REDSTONE_POLL_INTERVAL = 20;
    private final int transferCooldownTicks;
    // Controls whether the basket pushes contents into the container it faces. Collection always runs.
    private final boolean eject;
    // Access is confined to the block entity's region tick thread, so cross-thread synchronization is unnecessary.
    // Start negative to match the original basket, which is ready to collect immediately after startup.
    private int transferCooldown = -1;
    private boolean poweredByRedstone;
    private int redstonePollTicks;

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
        // The poll is adaptive: an idle basket re-checks every REDSTONE_POLL_INTERVAL ticks, but the
        // moment a signal is seen the check runs every tick so the basket resumes as soon as the signal
        // drops. The adaptive interval avoids a six-neighbor scan on every cooldown expiry while idle.
        if (this.poweredByRedstone || ++this.redstonePollTicks >= REDSTONE_POLL_INTERVAL) {
            this.redstonePollTicks = 0;
            this.poweredByRedstone = world.getBlockAt(pos.x(), pos.y(), pos.z()).isBlockIndirectlyPowered();
        }
        if (this.poweredByRedstone) {
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

    private static Inventory facedContainerInventory(World world, int x, int y, int z) {
        org.bukkit.block.BlockState facedState = world.getBlockAt(x, y, z).getState(false);
        if (facedState instanceof org.bukkit.block.Container container) {
            return container.getInventory();
        }
        return null;
    }

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
        // spatially filtered through the server's entity slices and returns only Item entities in range.
        // The box stays inside the basket's own cell (plus the faced cell only when it is region-owned), so on
        // Folia it never reaches into an unowned region; a region-ownership rejection at a chunk edge is caught
        // and the scan is skipped for this tick.
        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(world,
                (int) Math.floor(minX) >> 4, (int) Math.floor(minZ) >> 4,
                (int) Math.floor(maxX) >> 4, (int) Math.floor(maxZ) >> 4)) {
            return false;
        }
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
