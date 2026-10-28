package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Cake;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slices vanilla cakes and candle cakes with a knife, and drops the remaining slices when a cake is broken with
 * one. CraftEngine pies implement the same rules in their own block config; this listener covers the vanilla
 * blocks, which have no plugin-side block definition to attach behavior to.
 */
public final class CakeSliceListener implements Listener {

    private static final String CAKE_SLICE_ID = "farmersdelight:cake_slice";
    private static final String SLICE_SOUND = "farmersdelight:block.food.slice";
    // One entry per player: the second hand of a two-hand right-click arrives in the same tick and must not
    // slice the same cake twice.
    private static final Map<UUID, CakeSliceRules.Click> LAST_CLICK = new ConcurrentHashMap<>();

    private final FarmersDelightPlugin plugin;

    public CakeSliceListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || event.getHand() == null) {
            return;
        }
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY
                || event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!CakeSliceRules.target(block.getType(), isCustomBlock(block))) {
            return;
        }
        Player player = event.getPlayer();
        boolean offHand = event.getHand() == EquipmentSlot.OFF_HAND;
        ItemStack tool = offHand ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!plugin.isKnife(tool)) {
            return;
        }
        if (!ownsRegion(block) || !ProtectionCompat.canUse(player, block, (String) null)) {
            return;
        }
        CakeSliceRules.Click click = new CakeSliceRules.Click(block.getWorld().getUID(), block.getX(), block.getY(),
                block.getZ(), Bukkit.getCurrentTick(), offHand);
        if (CakeSliceRules.duplicateHand(LAST_CLICK.put(player.getUniqueId(), click), click)) {
            return;
        }
        if (!(block.getBlockData() instanceof Cake cake)) {
            return;
        }

        Material candle = CakeSliceRules.candleFor(block.getType());
        int next = CakeSliceRules.nextBites(cake.getBites(), candle != null);
        List<ItemStack> drops = new ArrayList<>(2);
        ItemStack slice = ItemUtils.createItem(CAKE_SLICE_ID);
        if (slice != null && !slice.getType().isAir()) {
            drops.add(slice);
        }
        if (candle != null) {
            drops.add(new ItemStack(candle));
        }
        if (!addSliceProgress(player, block, event.getHand(), drops, next < 0)) {
            return;
        }

        if (next < 0) {
            block.setType(Material.AIR, false);
        } else if (candle != null) {
            // A sliced candle cake becomes a plain cake with one bite, so the next slice works like any cake.
            Cake plain = (Cake) Material.CAKE.createBlockData();
            plain.setBites(Math.min(next, CakeSliceRules.MAX_CAKE_BITES));
            block.setBlockData(plain, false);
        } else {
            cake.setBites(next);
            block.setBlockData(cake, false);
        }

        dropSliceItems(block, drops);
        if (offHand) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }
        SoundUtils.play(block.getWorld(), block.getLocation().add(0.5, 0.5, 0.5),
                SLICE_SOUND, Sound.BLOCK_WOOD_BREAK, 0.8f, 0.8f);
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Block block = event.getBlock();
        if (!CakeSliceRules.target(block.getType(), isCustomBlock(block))) {
            return;
        }
        if (!plugin.isKnife(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (!ownsRegion(block) || !ProtectionCompat.canBreak(player, block, (String) null)) {
            return;
        }
        if (!(block.getBlockData() instanceof Cake cake)) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(2);
        ItemStack slice = ItemUtils.createItem(CAKE_SLICE_ID);
        if (slice != null && !slice.getType().isAir()) {
            slice.setAmount(CakeSliceRules.remainingSlices(cake.getBites(), true));
            drops.add(slice);
        }
        Material candle = CakeSliceRules.candleFor(block.getType());
        if (candle != null) {
            drops.add(new ItemStack(candle));
        }
        dropSliceItems(block, drops);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        LAST_CLICK.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Reports the slice as a harvest so protection and statistics plugins can veto it, and reports the final
     * slice as a block break for the same reason. Returns false, after dropping the recorded click, when either
     * event was cancelled.
     */
    private boolean addSliceProgress(Player player, Block block, EquipmentSlot hand, List<ItemStack> drops,
                                     boolean removesBlock) {
        PlayerHarvestBlockEvent harvest = new PlayerHarvestBlockEvent(player, block, hand, new ArrayList<>(drops));
        Bukkit.getPluginManager().callEvent(harvest);
        if (harvest.isCancelled()) {
            return false;
        }
        // Plugins may edit the harvested list; those edits decide what actually drops.
        drops.clear();
        drops.addAll(harvest.getItemsHarvested());
        if (removesBlock) {
            BlockBreakEvent breaking = new BlockBreakEvent(block, player);
            breaking.setDropItems(false);
            Bukkit.getPluginManager().callEvent(breaking);
            if (breaking.isCancelled()) {
                return false;
            }
        }
        return true;
    }

    private static void dropSliceItems(Block block, List<ItemStack> drops) {
        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.2, 0.5);
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) {
                continue;
            }
            world.dropItem(center, drop.clone(), item -> {
                item.setPickupDelay(10);
                item.setVelocity(new Vector(-0.05, 0, 0));
            });
        }
    }

    private boolean isCustomBlock(Block block) {
        var state = CustomBlockUtils.getState(block);
        return state != null && !state.isEmpty();
    }

    private boolean ownsRegion(Block block) {
        return !plugin.scheduler().isFolia() || plugin.scheduler().isOwnedByCurrentRegion(block.getLocation());
    }
}
