package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.UpperHalfLootRelayBehavior;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.world.BukkitExistingBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class UpperHalfLootRelayListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        if (event == null || !event.dropItems()) {
            return;
        }

        ImmutableBlockState brokenState = event.blockState();
        if (brokenState == null || brokenState.isEmpty()) {
            return;
        }

        UpperHalfLootRelayBehavior behavior = brokenState.behavior()
                .getFirst(UpperHalfLootRelayBehavior.class);
        if (behavior == null) {
            return;
        }

        Block brokenBlock = event.bukkitBlock();
        TallCropBlockBehavior tallCrop = TallCropBlockBehavior.getBehavior(brokenState);
        if (hasImmatureUpperHalf(behavior, tallCrop, brokenState, brokenBlock)) {
            event.setDropItems(false);
            return;
        }

        if (!behavior.shouldRelayUpperHalfLoot(brokenState, brokenBlock)) {
            return;
        }

        Block lootSourceBlock = brokenBlock.getRelative(behavior.getLowerHalfDirection());
        ImmutableBlockState lootSourceState = event.blockState();
        ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lootSourceBlock);
        if (lowerState != null && !lowerState.isEmpty()) {
            lootSourceState = lowerState;
        }

        WorldPosition position = new WorldPosition(
                BukkitAdaptor.adapt(brokenBlock.getWorld()),
                brokenBlock.getX() + 0.5,
                brokenBlock.getY() + 0.5,
                brokenBlock.getZ() + 0.5
        );
        ContextHolder.Builder builder = event.contextBuilder()
                .withParameter(DirectContextParameters.BLOCK, new BukkitExistingBlock(lootSourceBlock))
                .withParameter(DirectContextParameters.POSITION, position)
                .withParameter(DirectContextParameters.PLAYER, event.player());

        ItemStack mainHand = event.getPlayer().getInventory().getItemInMainHand();
        if (mainHand != null && !mainHand.getType().isAir()) {
            builder.withOptionalParameter(DirectContextParameters.ITEM_IN_HAND, BukkitAdaptor.adapt(mainHand));
        }

        List<net.momirealms.craftengine.core.item.Item> drops =
                lootSourceState.getDrops(builder, position.world(), event.player());
        if (drops.isEmpty()) {
            return;
        }

        event.setDropItems(false);
        for (net.momirealms.craftengine.core.item.Item drop : drops) {
            position.world().dropItemNaturally(position, drop);
        }
    }

    private boolean hasImmatureUpperHalf(
            UpperHalfLootRelayBehavior relayBehavior,
            TallCropBlockBehavior tallCrop,
            ImmutableBlockState brokenState,
            Block brokenBlock
    ) {
        if (tallCrop == null || brokenBlock == null) {
            return false;
        }

        ImmutableBlockState upperState = brokenState;
        if (tallCrop.isLowerHalf(brokenState)) {
            BlockFace upperDirection = relayBehavior != null
                    ? relayBehavior.getUpperHalfDirection()
                    : BlockFace.UP;
            Block upperBlock = brokenBlock.getRelative(upperDirection);
            upperState = CraftEngineBlocks.getCustomBlockState(upperBlock);
        }

        if (upperState == null || upperState.isEmpty() || !tallCrop.isUpperHalf(upperState)) {
            return false;
        }

        if (!tallCrop.isUpperMature(upperState)) {
            return true;
        }

        BlockFace lowerDirection = relayBehavior != null
                ? relayBehavior.getLowerHalfDirection()
                : BlockFace.DOWN;
        Block lowerBlock = tallCrop.isLowerHalf(brokenState)
                ? brokenBlock
                : brokenBlock.getRelative(lowerDirection);
        ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
        return lowerState == null || lowerState.isEmpty()
                || !tallCrop.isLowerHalf(lowerState)
                || !tallCrop.isLowerMature(lowerState);
    }
}

