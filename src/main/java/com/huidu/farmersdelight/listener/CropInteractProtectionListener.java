package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class CropInteractProtectionListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK) return;

        ImmutableBlockState state = event.blockState();
        TomatoVineBlockBehavior behavior = CustomBlockUtils.getBehavior(state, TomatoVineBlockBehavior.class);
        if (behavior == null || !behavior.isHarvestReady(state)) return;

        Player player = event.player();
        Block block = event.bukkitBlock();
        if (!ProtectionCompat.canUse(player, block, ProtectionCompat.Feature.TOMATO)
                || !ProtectionCompat.canBuild(player, block, ProtectionCompat.Feature.TOMATO)) {
            event.setCancelled(true);
        }
    }
}
