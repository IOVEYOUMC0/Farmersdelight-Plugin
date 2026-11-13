package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.tool.ToolAttackListener;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.compat.ProtectionChecks;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Carves a pumpkin with a knife: right-clicking one produces a carved pumpkin facing the clicked side (or away
 * from the player when the top is clicked) and drops four pumpkin seeds, consuming one durability.
 */
public final class PumpkinCarveListener implements Listener {

    private static final String CARVE_SOUND = "minecraft:block.pumpkin.carve";
    private static final int SEED_COUNT = 4;

    private final FarmersDelightPlugin plugin;

    public PumpkinCarveListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || event.getHand() == null) {
            return;
        }
        if (event.useInteractedBlock() == Event.Result.DENY || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        // Ownership before any block read: the event runs on the player's region, and a pumpkin on the other
        // side of a region border is not the current thread's to read or carve.
        if (!ownsRegion(block)) {
            return;
        }
        if (block.getType() != Material.PUMPKIN) {
            return;
        }
        // Material first, then the CraftEngine state (the expensive part): only real vanilla pumpkins carve.
        if (isCustomBlock(block)) {
            return;
        }
        Player player = event.getPlayer();
        boolean offHand = event.getHand() == EquipmentSlot.OFF_HAND;
        ItemStack tool = offHand ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!plugin.isKnife(tool)) {
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE
                || !ProtectionChecks.canUse(player, block, (String) null)) {
            return;
        }

        event.setCancelled(true);
        BlockData before = block.getBlockData().clone();
        var harvest = new org.bukkit.event.player.PlayerHarvestBlockEvent(player, block, event.getHand(),
                new java.util.ArrayList<>(java.util.List.of(new ItemStack(Material.PUMPKIN_SEEDS, SEED_COUNT))));
        org.bukkit.Bukkit.getPluginManager().callEvent(harvest);
        if (harvest.isCancelled() || !before.equals(block.getBlockData())) return;
        BlockFace facing = PumpkinCarveRules.carvedFacing(event.getBlockFace(), player.getFacing());
        BlockData carved = Material.CARVED_PUMPKIN.createBlockData();
        if (carved instanceof Directional directional) {
            directional.setFacing(facing);
        }
        World world = block.getWorld();
        SoundUtils.play(world, block.getLocation().add(0.5, 0.5, 0.5), CARVE_SOUND, Sound.BLOCK_PUMPKIN_CARVE,
                1.0f, 1.0f);
        block.setBlockData(carved, false);

        Location drop = block.getLocation().add(0.5 + facing.getModX() * 0.65, 0.1,
                0.5 + facing.getModZ() * 0.65);
        for (ItemStack seedDrop : harvest.getItemsHarvested()) {
            if (seedDrop == null || seedDrop.getType().isAir()) continue;
            world.dropItem(drop, seedDrop.clone(), item -> {
                item.setPickupDelay(10);
                item.setVelocity(new Vector(0.05 * facing.getModX(), 0.05, 0.05 * facing.getModZ()));
            });
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            ToolAttackListener.consumeDurability(tool, player.getLocation());
        }
        if (offHand) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }
        event.setCancelled(true);
    }

    private boolean ownsRegion(Block block) {
        return !plugin.scheduler().isFolia() || plugin.scheduler().isOwnedByCurrentRegion(block.getLocation());
    }

    /** A CraftEngine custom block carried by minecraft:pumpkin must stay that block, not become a carved pumpkin. */
    private boolean isCustomBlock(Block block) {
        var state = CustomBlockUtils.getState(block);
        return state != null && !state.isEmpty();
    }
}
