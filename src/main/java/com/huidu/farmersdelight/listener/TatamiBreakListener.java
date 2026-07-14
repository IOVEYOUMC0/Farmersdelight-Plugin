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

/**
 * Un-pairs the surviving half of a paired tatami when its partner is removed, by resetting any adjacent paired
 * tatami whose facing points at the broken block. This is the authoritative reset: it runs on the guaranteed
 * block-break events (so it does not depend on the engine dispatching updateShape / neighborChanged to a custom
 * block, which proved unreliable in-game), and it keys on the surviving neighbor's live state plus the known
 * broken position rather than re-reading the broken block (whose custom state CraftEngine may already have
 * cleared). CraftEngine's CustomBlockBreakEvent carries the captured block state, so the tatami is identified
 * reliably; the vanilla and explosion events are fallbacks for removal causes CraftEngine does not wrap.
 */
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
                .map(Object::toString)
                .filter(TATAMI_BLOCK_ID::equals)
                .isPresent();
    }
}
