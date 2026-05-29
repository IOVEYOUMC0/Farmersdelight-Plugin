package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.*;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public class BlockBreakListener implements Listener {
    private static final String TATAMI_BLOCK_ID = "farmersdelight:tatami";
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        FarmersDelightPlugin.getInstance().getStoveManager()
                .invalidateBlockedAboveCache(event.getBlock().getLocation().clone().add(0, -1, 0));
        syncTraysAroundSupportChange(event.getBlock());
        if (isStateManagedInteractiveBlock(event.getBlock())) {
            return;
        }
        cleanupBlockAt(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        FarmersDelightPlugin.getInstance().getStoveManager()
                .invalidateBlockedAboveCache(event.bukkitBlock().getLocation().clone().add(0, -1, 0));
        syncTraysAroundSupportChange(event.bukkitBlock());
        if (!isManagedInteractiveBlock(event.blockState())) {
            return;
        }
        if (isCookingPotBlock(event.blockState())) {
            boolean shouldDropItems = event.dropItems() && event.getPlayer().getGameMode() != GameMode.CREATIVE;
            event.setDropItems(false);
            boolean preserveContents = FarmersDelightPlugin.getInstance().isCookingPotPackContentsOnBreak();
            cleanupBlockAt(event.bukkitBlock(), event.blockState(), preserveContents, shouldDropItems);
            return;
        }
        if (!isSkilletBlock(event.blockState())) {
            cleanupBlockAt(event.bukkitBlock(), false, event.dropItems());
            return;
        }
        boolean shouldDropItems = event.dropItems() && event.getPlayer().getGameMode() != GameMode.CREATIVE;
        event.setDropItems(false);
        cleanupBlockAt(event.bukkitBlock(), false, shouldDropItems);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (var block : event.blockList()) {
            syncTraysAroundSupportChange(block);
            cleanupBlockAt(block, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (var block : event.blockList()) {
            syncTraysAroundSupportChange(block);
            cleanupBlockAt(block, false);
        }
    }

    private void syncTraysAroundSupportChange(org.bukkit.block.Block block) {
        if (block == null) {
            return;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || plugin.getTrayManager() == null) {
            return;
        }
        plugin.getTrayManager().syncAroundSupportChange(block.getLocation());
    }

    private void cleanupBlockAt(org.bukkit.block.Block block) {
        cleanupBlockAt(block, false, true);
    }

    private void cleanupBlockAt(org.bukkit.block.Block block, boolean preserveCookingPotContents) {
        cleanupBlockAt(block, preserveCookingPotContents, true);
    }

    private void cleanupBlockAt(org.bukkit.block.Block block, boolean preserveCookingPotContents, boolean shouldDropItems) {
        cleanupBlockAt(block, CustomBlockUtils.getState(block), preserveCookingPotContents, shouldDropItems);
    }

    private void cleanupBlockAt(org.bukkit.block.Block block, ImmutableBlockState state, boolean preserveCookingPotContents, boolean shouldDropItems) {
        World world = block.getWorld();
        Location blockLocation = block.getLocation();
        Location dropLocation = blockLocation.clone().add(0.5, 0.5, 0.5);
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());

        if (isCookingPotBlock(state)) {
            cleanupCookingPot(pos, world, dropLocation, state, preserveCookingPotContents, shouldDropItems);
        } else if (CookingPotBlockBehavior.getBlockEntity(world, pos) != null) {
            CookingPotBlockBehavior.removeBlockEntity(world, pos);
        }
        cleanupSkillet(blockLocation, dropLocation, shouldDropItems);
        cleanupCuttingBoard(pos, world, dropLocation, shouldDropItems);
        cleanupStove(blockLocation, dropLocation, shouldDropItems);
        if (isTatamiBlock(block)) {
            cleanupTatami(blockLocation);
        }
    }

    private boolean isCookingPotBlock(org.bukkit.block.Block block) {
        if (block == null) {
            return false;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(block);
        return isCookingPotBlock(state);
    }

    private void cleanupCookingPot(BlockPos pos, World world, Location dropLocation, ImmutableBlockState state, boolean preserveContents, boolean shouldDropItems) {
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(world, pos);
        if (entity == null) {
            if (shouldDropItems) {
                dropCookingPotBaseItem(world, dropLocation, state, null);
            }
            CookingPotBlockBehavior.removeBlockEntity(world, pos);
            return;
        }

        if (shouldDropItems) {
            if (preserveContents) {
                dropCookingPotBaseItem(world, dropLocation, state, entity);
            } else {
                dropCookingPotBaseItem(world, dropLocation);
                dropCookingPotContents(world, dropLocation, entity);
            }
        }
        CookingPotBlockBehavior.removeBlockEntity(world, pos);
    }

    private void dropCookingPotBaseItem(World world, Location dropLocation) {
        dropCookingPotBaseItem(world, dropLocation, null);
    }

    private void dropCookingPotBaseItem(World world, Location dropLocation, CookingPotBlockEntity entity) {
        dropCookingPotBaseItem(world, dropLocation, null, entity);
    }

    private void dropCookingPotBaseItem(World world, Location dropLocation, ImmutableBlockState state, CookingPotBlockEntity entity) {
        String itemId = CustomBlockUtils.getId(state);
        ItemStack potItem = ItemUtils.createItem(itemId != null ? itemId : Constants.BLOCK_COOKING_POT);
        if (potItem == null || potItem.getType().isAir()) {
            return;
        }
        potItem.setAmount(1);
        if (entity == null || !entity.hasStoredContents()) {
            world.dropItemNaturally(dropLocation, potItem);
            return;
        }

        CookingPotBlockBehavior behavior = state != null
                ? CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class)
                : CookingPotBlockBehavior.getBlockBehavior(dropLocation);
        if (behavior == null) {
            world.dropItemNaturally(dropLocation, potItem);
            return;
        }

        Item wrapped = BukkitItemManager.instance().wrap(potItem);
        CompoundTag packedData = CookingPotBlockEntityController.saveData(entity);
        CompoundTag customData = CustomBlockUtils.getComponentCompound(wrapped, DataComponentKeys.CUSTOM_DATA);
        if (customData == null) {
            customData = new CompoundTag();
        }
        customData.put(behavior.getCustomDataKey(), packedData);
        wrapped.setSparrowTagComponent(DataComponentKeys.CUSTOM_DATA, customData);
        net.momirealms.craftengine.core.world.World ceWorld = BukkitAdaptor.adapt(world);
        ceWorld.dropItemNaturally(new WorldPosition(ceWorld, dropLocation.getX(), dropLocation.getY(), dropLocation.getZ()), wrapped);
    }

    private void dropCookingPotContents(World world, Location dropLocation, CookingPotBlockEntity entity) {
        if (world == null || dropLocation == null || entity == null) {
            return;
        }

        for (ItemStack item : entity.getInventory()) {
            if (item != null && !item.getType().isAir()) {
                world.dropItemNaturally(dropLocation, item);
            }
        }

        ItemStack mealContainer = entity.getMealContainer();
        if (mealContainer != null && !mealContainer.getType().isAir()) {
            world.dropItemNaturally(dropLocation, mealContainer);
        }
    }

    private void cleanupSkillet(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        FarmersDelightPlugin.getInstance().getSkilletManager().breakSkillet(blockLocation, dropLocation, shouldDropItems);
    }

    private void cleanupCuttingBoard(BlockPos pos, World world, Location dropLocation, boolean shouldDropItems) {
        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(world, pos);
        if (entity == null) return;

        ItemStack storedItem = entity.getStoredItem();
        if (shouldDropItems && storedItem != null && !storedItem.getType().isAir()) {
            world.dropItemNaturally(dropLocation, storedItem);
        }
        entity.removeDisplayEntity();
        CuttingBoardBlockBehavior.removeBlockEntity(world, pos);
    }

    private void cleanupStove(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        FarmersDelightPlugin.getInstance().getStoveManager().breakStove(blockLocation, dropLocation, shouldDropItems);
    }

    private void cleanupTatami(Location blockLocation) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        plugin.scheduler().runLaterAt(blockLocation, () -> TatamiPairingBehavior.refreshAdjacentTatami(blockLocation), 1L);
    }

    private boolean isManagedInteractiveBlock(ImmutableBlockState state) {
        return isCookingPotBlock(state)
                || isSkilletBlock(state)
                || CustomBlockUtils.hasBehavior(state, CuttingBoardBlockBehavior.class)
                || Constants.BLOCK_CUTTING_BOARD.equals(CustomBlockUtils.getId(state))
                || CustomBlockUtils.hasBehavior(state, StoveCookingBlockBehavior.class)
                || Constants.BLOCK_STOVE.equals(CustomBlockUtils.getId(state))
                || TATAMI_BLOCK_ID.equals(CustomBlockUtils.getId(state));
    }

    private boolean isStateManagedInteractiveBlock(org.bukkit.block.Block block) {
        ImmutableBlockState state = CustomBlockUtils.getState(block);
        return isCookingPotBlock(state)
                || isSkilletBlock(state)
                || CustomBlockUtils.hasBehavior(state, CuttingBoardBlockBehavior.class)
                || Constants.BLOCK_CUTTING_BOARD.equals(CustomBlockUtils.getId(state))
                || CustomBlockUtils.hasBehavior(state, StoveCookingBlockBehavior.class)
                || Constants.BLOCK_STOVE.equals(CustomBlockUtils.getId(state));
    }

    private boolean isSkilletBlock(ImmutableBlockState state) {
        return CustomBlockUtils.hasBehavior(state, SkilletBlockBehavior.class)
                || Constants.BLOCK_SKILLET.equals(CustomBlockUtils.getId(state));
    }

    private boolean isCookingPotBlock(ImmutableBlockState state) {
        return CustomBlockUtils.hasBehavior(state, CookingPotBlockBehavior.class)
                || Constants.BLOCK_COOKING_POT.equals(CustomBlockUtils.getId(state));
    }

    private boolean isTatamiBlock(org.bukkit.block.Block block) {
        if (block == null) {
            return false;
        }

        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) {
            return false;
        }

        try {
            return state.owner().keyOptional()
                    .map(Object::toString)
                    .filter(TATAMI_BLOCK_ID::equals)
                    .isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }
}

