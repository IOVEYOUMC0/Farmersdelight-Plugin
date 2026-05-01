package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.UpperHalfLootRelayBehavior;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptors;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.world.BukkitExistingBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class UpperHalfLootRelayListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        if (event == null || !event.dropItems()) {
            return;
        }

        ImmutableBlockState brokenState = event.blockState();
        if (brokenState == null || brokenState.isEmpty()) {
            return;
        }

        UpperHalfLootRelayBehavior behavior = brokenState.behavior()
                .getAs(UpperHalfLootRelayBehavior.class)
                .orElse(null);
        if (behavior == null) {
            return;
        }

        Block brokenBlock = event.bukkitBlock();
        if (!behavior.shouldRelayUpperHalfLoot(brokenState, brokenBlock)) {
            return;
        }

        WorldPosition position = new WorldPosition(
                BukkitAdaptors.adapt(brokenBlock.getWorld()),
                brokenBlock.getX() + 0.5,
                brokenBlock.getY() + 0.5,
                brokenBlock.getZ() + 0.5
        );
        ContextHolder.Builder builder = event.contextBuilder()
                .withParameter(DirectContextParameters.BLOCK, new BukkitExistingBlock(brokenBlock))
                .withParameter(DirectContextParameters.POSITION, position)
                .withParameter(DirectContextParameters.PLAYER, event.player());

        ItemStack mainHand = event.getPlayer().getInventory().getItemInMainHand();
        if (mainHand != null && !mainHand.getType().isAir()) {
            builder.withOptionalParameter(DirectContextParameters.ITEM_IN_HAND, BukkitAdaptors.adapt(mainHand));
        }

        List<net.momirealms.craftengine.core.item.Item<Object>> drops =
                brokenState.getDrops(builder, position.world(), event.player());
        if (drops.isEmpty()) {
            return;
        }

        event.setDropItems(false);
        for (net.momirealms.craftengine.core.item.Item<Object> drop : drops) {
            position.world().dropItemNaturally(position, drop);
        }
    }
}
