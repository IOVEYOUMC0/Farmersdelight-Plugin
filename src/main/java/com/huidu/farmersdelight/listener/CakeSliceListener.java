package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import org.bukkit.Bukkit;
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
 * Slices vanilla cakes and candle cakes with a knife. Break loot stays in the CE loot source to avoid duplicate
 * drops; CE pies implement their own right-click slicing in the content pack.
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
        Material type = block.getType();
        // Material first: this listener sees every right-click in the world, and the CraftEngine state
        // lookup behind isCustomBlock is the expensive part. Only cake clicks pay for it.
        if (type != Material.CAKE && CakeSliceRules.candleFor(type) == null) {
            return;
        }
        if (!CakeSliceRules.target(type, isCustomBlock(block))) {
            return;
        }
        Player player = event.getPlayer();
        boolean offHand = event.getHand() == EquipmentSlot.OFF_HAND;
        ItemStack tool = offHand ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!plugin.isKnife(tool)) {
            return;
        }
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || player.getGameMode() == org.bukkit.GameMode.ADVENTURE
                || !ownsRegion(block) || !ProtectionCompat.canUse(player, block, (String) null)) {
            return;
        }
        CakeSliceRules.Click click = new CakeSliceRules.Click(block.getWorld().getUID(), block.getX(), block.getY(),
                block.getZ(), Bukkit.getCurrentTick(), offHand);
        if (CakeSliceRules.duplicateHand(LAST_CLICK.put(player.getUniqueId(), click), click)) {
            event.setCancelled(true);
            return;
        }
        var before = block.getBlockData().clone();
        Material candle = CakeSliceRules.candleFor(type);
        int bites = CakeSliceRules.bites(before);
        if (bites < 0) return;
        int next = CakeSliceRules.nextBites(bites, candle != null);
        // Once this knife click is claimed, a rejected harvest must not fall through to vanilla eating.
        event.setCancelled(true);
        List<ItemStack> drops = new ArrayList<>(2);
        ItemStack slice = ItemUtils.createItem(CAKE_SLICE_ID);
        if (slice == null || slice.getType().isAir()) return;
        drops.add(slice);
        if (candle != null) {
            drops.add(new ItemStack(candle));
        }
        if (!addSliceProgress(player, block, event.getHand(), drops, next < 0)) {
            return;
        }

        if (!ownsRegion(block) || !before.equals(block.getBlockData())) return;
        if (next < 0) {
            block.setType(Material.AIR, false);
        } else if (candle != null) {
            // A sliced candle cake becomes a plain cake with one bite, so the next slice works like any cake.
            Cake plain = (Cake) Material.CAKE.createBlockData();
            plain.setBites(Math.min(next, CakeSliceRules.MAX_CAKE_BITES));
            block.setBlockData(plain, false);
        } else {
            Cake cake = (Cake) before;
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
