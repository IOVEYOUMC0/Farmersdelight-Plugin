package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.TatamiPairingBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

public class TatamiBreakListener implements Listener {
    private static final String TATAMI_BLOCK_ID = "farmersdelight:tatami";

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        if (isTatami(event.blockState())) {
            TatamiPairingBehavior.resetFacingNeighbors(event.bukkitBlock().getLocation(), "custom-break");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (isTatami(CraftEngineBlocks.getCustomBlockState(block))) {
            TatamiPairingBehavior.resetFacingNeighbors(block.getLocation(), "block-break");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (isTatami(CraftEngineBlocks.getCustomBlockState(block))) {
                TatamiPairingBehavior.resetFacingNeighbors(block.getLocation(), "block-explode");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (isTatami(CraftEngineBlocks.getCustomBlockState(block))) {
                TatamiPairingBehavior.resetFacingNeighbors(block.getLocation(), "entity-explode");
            }
        }
    }

    private static boolean isTatami(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return false;
        }
        return state.owner().keyOptional()
                .map(k -> k.location().toString())
                .filter(TATAMI_BLOCK_ID::equals)
                .isPresent();
    }
}
