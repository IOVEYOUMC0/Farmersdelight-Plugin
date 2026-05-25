package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.WorldGuardCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class SkilletPlaceListener implements Listener {

    private static final Key SKILLET_BLOCK_ID = Key.of("farmersdelight:skillet");
    private static final String SKILLET_ITEM_ID = "farmersdelight:skillet";

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShiftPlaceSkillet(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!isSkilletItem(mainHand)) {
            return;
        }

        Block targetBlock = resolvePlacementTarget(event.getClickedBlock(), event.getBlockFace());
        if (!canReplace(targetBlock)) {
            return;
        }
        if (!WorldGuardCompat.canBuild(player, targetBlock)) {
            return;
        }

        BlockDefinition skilletBlock = CraftEngineBlocks.byId(SKILLET_BLOCK_ID);
        if (skilletBlock == null) {
            return;
        }

        ImmutableBlockState state = applyFacing(skilletBlock.defaultState(), player.getFacing().getOppositeFace());
        ItemStack placedSnapshot = mainHand.clone();
        placedSnapshot.setAmount(1);

        if (!CraftEngineBlocks.place(targetBlock.getLocation(), state, true)) {
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            mainHand.setAmount(mainHand.getAmount() - 1);
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin.getSkilletManager() != null) {
            plugin.getSkilletManager().recordPlacedSkillet(targetBlock.getLocation(), placedSnapshot);
        }
        AdvancementManager advancementManager = plugin.getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "place_skillet");
        }

        player.swingMainHand();
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
    }

    private boolean isSkilletItem(ItemStack itemStack) {
        return itemStack != null && SKILLET_ITEM_ID.equals(ItemUtils.getCustomItemId(itemStack));
    }

    private Block resolvePlacementTarget(Block clickedBlock, BlockFace clickedFace) {
        if (clickedBlock == null) {
            return null;
        }
        if (canReplace(clickedBlock)) {
            return clickedBlock;
        }
        return clickedBlock.getRelative(clickedFace);
    }

    private boolean canReplace(Block block) {
        return block != null && BlockStateUtils.isReplaceable(BlockStateUtils.getBlockState(block));
    }

    @SuppressWarnings("unchecked")
    private ImmutableBlockState applyFacing(ImmutableBlockState state, BlockFace playerFacing) {
        if (state == null) {
            return null;
        }
        Property<?> facingProperty = state.owner().value().getProperty("facing");
        if (facingProperty == null || facingProperty.valueClass() != Direction.class) {
            return state;
        }
        Direction direction = toDirection(playerFacing);
        if (direction == null) {
            return state;
        }
        return state.with((Property<Direction>) facingProperty, direction);
    }

    private Direction toDirection(BlockFace face) {
        return switch (face) {
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }
}

