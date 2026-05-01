package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;

public final class HopperInteractionListener {

    private static final long HOPPER_INTERVAL_TICKS = 8L;

    private final FarmersDelightPlugin plugin;
    private BukkitTask task;

    public HopperInteractionListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, HOPPER_INTERVAL_TICKS, HOPPER_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (World world : Bukkit.getWorlds()) {
            processCookingPots(world);
            processCuttingBoards(world);
        }
    }

    private void processCookingPots(World world) {
        for (Map.Entry<BlockPosKey, CookingPotBlockEntity> entry : CookingPotBlockBehavior.getAllBlockEntities(world).entrySet()) {
            BlockPosKey posKey = entry.getKey();
            CookingPotBlockEntity entity = entry.getValue();
            if (!CookingPotBlockBehavior.isCookingPotBlock(world, posKey)) {
                continue;
            }

            boolean changed = false;
            changed |= moveFromHopperAboveIntoPot(world, posKey, entity);
            changed |= moveFromSideHoppersIntoPotContainer(world, posKey, entity);
            changed |= movePotOutputIntoHopperBelow(world, posKey, entity);

            if (changed) {
                syncCookingPot(world, posKey, entity);
            }
        }
    }

    private void processCuttingBoards(World world) {
        for (Map.Entry<BlockPosKey, CuttingBoardBlockEntity> entry : CuttingBoardBlockBehavior.getAllBlockEntities(world).entrySet()) {
            BlockPosKey posKey = entry.getKey();
            CuttingBoardBlockEntity entity = entry.getValue();
            if (!CuttingBoardBlockBehavior.isCuttingBoardBlock(world, posKey)) {
                continue;
            }

            boolean changed = false;
            changed |= moveFromHopperAboveIntoCuttingBoard(world, posKey, entity);
            changed |= moveFromSideHoppersIntoCuttingBoard(world, posKey, entity);
            changed |= moveCuttingBoardItemIntoHopperBelow(world, posKey, entity);

            if (changed) {
                CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
            }
        }
    }

    private boolean moveFromHopperAboveIntoPot(World world, BlockPosKey posKey, CookingPotBlockEntity entity) {
        Hopper hopper = getHopperAt(world, posKey.x(), posKey.y() + 1, posKey.z());
        if (hopper == null || !isFacing(hopper.getBlock(), BlockFace.DOWN)) {
            return false;
        }
        return moveOneItemFromInventory(hopper.getInventory(), entity::insertIngredientStack);
    }

    private boolean moveFromSideHoppersIntoPotContainer(World world, BlockPosKey posKey, CookingPotBlockEntity entity) {
        boolean changed = false;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Hopper hopper = getHopperAt(world, posKey.x() + face.getModX(), posKey.y(), posKey.z() + face.getModZ());
            if (hopper == null || !isFacing(hopper.getBlock(), face.getOppositeFace())) {
                continue;
            }
            changed |= moveOneItemFromInventory(hopper.getInventory(), entity::insertContainerStack);
            if (changed) {
                return true;
            }
        }
        return false;
    }

    private boolean movePotOutputIntoHopperBelow(World world, BlockPosKey posKey, CookingPotBlockEntity entity) {
        Hopper hopper = getHopperAt(world, posKey.x(), posKey.y() - 1, posKey.z());
        if (hopper == null) {
            return false;
        }

        entity.tryMovePendingToOutput();
        ItemStack output = entity.getMealDisplayItem();
        if (output == null || output.getType().isAir()) {
            return false;
        }

        ItemStack single = output.clone();
        single.setAmount(1);
        if (!canInsertIntoInventory(hopper.getInventory(), single)) {
            return false;
        }

        ItemStack extracted = entity.takeMealPortionForDelivery(world, 1);
        if (extracted == null || extracted.getType().isAir()) {
            return false;
        }

        ItemStack leftover = insertIntoInventory(hopper.getInventory(), extracted);
        if (leftover != null && !leftover.getType().isAir() && leftover.getAmount() > 0) {
            Location dropLocation = posKey.toLocation(world).add(0.5, 0.7, 0.5);
            world.dropItemNaturally(dropLocation, leftover);
        }
        return true;
    }

    private boolean moveFromHopperAboveIntoCuttingBoard(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
        Hopper hopper = getHopperAt(world, posKey.x(), posKey.y() + 1, posKey.z());
        if (hopper == null || !isFacing(hopper.getBlock(), BlockFace.DOWN)) {
            return false;
        }
        return moveOneItemFromInventory(hopper.getInventory(), stack -> insertIntoCuttingBoard(world, posKey, entity, stack));
    }

    private boolean moveFromSideHoppersIntoCuttingBoard(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Hopper hopper = getHopperAt(world, posKey.x() + face.getModX(), posKey.y(), posKey.z() + face.getModZ());
            if (hopper == null || !isFacing(hopper.getBlock(), face.getOppositeFace())) {
                continue;
            }
            if (moveOneItemFromInventory(hopper.getInventory(), stack -> insertIntoCuttingBoard(world, posKey, entity, stack))) {
                return true;
            }
        }
        return false;
    }

    private boolean moveCuttingBoardItemIntoHopperBelow(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
        Hopper hopper = getHopperAt(world, posKey.x(), posKey.y() - 1, posKey.z());
        if (hopper == null || !entity.hasItem()) {
            return false;
        }

        ItemStack stored = entity.getStoredItem();
        if (stored == null || stored.getType().isAir()) {
            return false;
        }

        if (!canInsertIntoInventory(hopper.getInventory(), stored)) {
            return false;
        }

        ItemStack leftover = insertIntoInventory(hopper.getInventory(), stored);
        entity.clearItem();
        if (leftover != null && !leftover.getType().isAir() && leftover.getAmount() > 0) {
            Location dropLocation = posKey.toLocation(world).add(0.5, 0.2, 0.5);
            world.dropItemNaturally(dropLocation, leftover);
        }
        return true;
    }

    private ItemStack insertIntoCuttingBoard(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity, ItemStack stack) {
        if (entity.hasItem()) {
            return stack.clone();
        }

        ItemStack placed = stack.clone();
        placed.setAmount(1);
        entity.setItem(placed, world, posKey, getCuttingBoardFacing(world, posKey));

        ItemStack leftover = stack.clone();
        leftover.setAmount(Math.max(0, leftover.getAmount() - 1));
        if (leftover.getAmount() > 0) {
            return leftover;
        }
        return null;
    }

    private void syncCookingPot(World world, BlockPosKey posKey, CookingPotBlockEntity entity) {
        TickManager tickManager = plugin.getTickManager();
        if (tickManager != null) {
            if (entity.hasInput() || entity.hasPendingOutput()) {
                tickManager.markActive(world, posKey, TickManager.BlockType.COOKING_POT);
            } else {
                tickManager.unregisterActiveBlock(world, posKey, TickManager.BlockType.COOKING_POT);
            }
        }
        CookingPotBlockBehavior.saveBlockEntityData(world, posKey);
    }

    private Hopper getHopperAt(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getState() instanceof Hopper hopper) {
            return hopper;
        }
        return null;
    }

    private boolean isFacing(Block block, BlockFace expectedFacing) {
        BlockData data = block.getBlockData();
        if (data instanceof org.bukkit.block.data.type.Hopper hopperData) {
            return hopperData.getFacing() == expectedFacing;
        }
        return false;
    }

    private BlockFace getCuttingBoardFacing(World world, BlockPosKey posKey) {
        try {
            return CustomBlockUtils.getFacing(posKey.toLocation(world).getBlock());
        } catch (Exception ignored) {
            return BlockFace.NORTH;
        }
    }

    private boolean moveOneItemFromInventory(Inventory inventory, java.util.function.Function<ItemStack, ItemStack> inserter) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }

            ItemStack single = stack.clone();
            single.setAmount(1);
            ItemStack leftover = inserter.apply(single);
            if (leftover != null && !leftover.getType().isAir() && leftover.getAmount() > 0) {
                continue;
            }

            if (stack.getAmount() <= 1) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
                inventory.setItem(slot, stack);
            }
            return true;
        }
        return false;
    }

    private boolean canInsertIntoInventory(Inventory inventory, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }

        for (ItemStack existing : inventory.getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                return true;
            }
            if (!existing.isSimilar(stack)) {
                continue;
            }
            if (existing.getAmount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack pending = stack.clone();

        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack existing = contents[i];
            if (existing == null || existing.getType().isAir() || !existing.isSimilar(pending)) {
                continue;
            }

            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, pending.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            pending.setAmount(pending.getAmount() - moved);
            contents[i] = existing;
            if (pending.getAmount() <= 0) {
                inventory.setStorageContents(contents);
                return null;
            }
        }

        for (int i = 0; i < contents.length; i++) {
            ItemStack existing = contents[i];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }

            contents[i] = pending.clone();
            inventory.setStorageContents(contents);
            return null;
        }

        inventory.setStorageContents(contents);
        return pending;
    }
}
