package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.block.entity.SimpleStorageBlockEntityController;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

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

    private final int transferCooldownTicks;
    // Read and written only on the block entity's own region tick thread (the CraftEngine sync
    // block-entity ticker), so it needs no cross-thread synchronization. Starts negative to match the
    // reference basket, which begins ready to collect.
    private int transferCooldown = -1;

    public BasketVacuumController(BlockEntity blockEntity, int transferCooldownTicks) {
        super(blockEntity);
        this.transferCooldownTicks = transferCooldownTicks;
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

        Inventory inventory = storageInventory();
        if (inventory == null || isFull(inventory)) {
            return;
        }

        BlockFace facing = CustomBlockUtils.getFullFacing(state);
        if (facing == null) {
            return;
        }

        World world = CustomBlockUtils.getBukkitWorld(this.blockEntity);
        if (world == null) {
            return;
        }

        if (collectItems(world, pos, facing, inventory)) {
            this.transferCooldown = this.transferCooldownTicks;
        }
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
        double minX = pos.x() + Math.min(0, fx);
        double minY = pos.y() + Math.min(0, fy);
        double minZ = pos.z() + Math.min(0, fz);
        double maxX = pos.x() + 1 + Math.max(0, fx);
        double maxY = pos.y() + 1 + Math.max(0, fy);
        double maxZ = pos.z() + 1 + Math.max(0, fz);
        BoundingBox area = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);

        for (Entity entity : world.getNearbyEntities(area, candidate -> candidate instanceof Item)) {
            Item item = (Item) entity;
            if (!item.isValid() || item.isDead()) {
                continue;
            }
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
