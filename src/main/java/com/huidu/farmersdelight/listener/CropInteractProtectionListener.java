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

/**
 * WorldGuard per-feature gate for the tomato right-click harvest. A mature ground/hanging tomato is
 * harvested by a CraftEngine YAML on: right_click event (drop produce + reset age), which
 * fires no BlockBreak/BlockPlace event, so WorldGuard's native protection never sees it — a player in
 * a region with build/use denied could still strip the crop.
 *
 * The YAML functions run before useOnBlock in CraftEngine's dispatch, so the only way to
 * suppress the harvest is to cancel CustomBlockInteractEvent; there is no hook to skip only
 * the YAML harvest. The cancel is limited to harvest-ready states so immature crops are never
 * touched (eating/placing/bonemeal on them work normally). Growth (bonemeal) and breaking stay
 * governed by WorldGuard's native flags.
 *
 * Known limitation: the CE harvest does not consume the interaction, so on a normal (allowed)
 * right-click of a mature crop the harvest AND the vanilla item-use both fire (the food is eaten /
 * the block is placed). Because our only lever is a full event cancel, denying the flag suppresses
 * that co-occurring item-use for the one harvest-ready click too. This is narrow (requires the flag
 * denied, the crop at max age, and the player aiming at it while holding a usable item) and is
 * accepted rather than reimplementing the YAML loot tables in Java just to gate them.
 *
 * Mushroom colonies are gated separately inside MushroomColonyBehavior.useOnBlock, which
 * can PASS cleanly and needs no event cancel.
 */
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
