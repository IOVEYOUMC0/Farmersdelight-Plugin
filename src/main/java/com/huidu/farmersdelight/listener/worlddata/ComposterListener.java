package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.InteractionDebouncer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import io.papermc.paper.event.block.CompostItemEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes the mod's compostable items work in a vanilla composter.
 *
 * Vanilla gates the composter on ComposterBlock.COMPOSTABLES, a map keyed by the item's registry entry:
 * ComposterBlock.useItemOn only runs when that map contains the held item, and Paper's CompostItemEvent is
 * fired further inside, in ComposterBlock.addItem. A CraftEngine custom item is a plain vanilla material
 * underneath (nether_brick by default), which is not in that map, so the composter refuses it outright and no
 * event ever fires. Bukkit exposes no way to add an entry to COMPOSTABLES, so the player-driven path is
 * carried out here instead: the interaction is denied and the level raise, the fill effect, the item cost and
 * the level 7 to 8 delay are reproduced from ComposterBlock.
 *
 * The CompostItemEvent handler still matters for the items whose base material happens to be compostable in
 * vanilla (dried_kelp among others): there the composter accepts the stack on its own, including from a
 * hopper, and only the chance needs correcting to the mod's value. It runs at HIGHEST so it wins over
 * CraftEngine's own handler, which applies the item's compost-probability setting and defaults it to 0.5.
 */
public final class ComposterListener implements Listener {

    /** Level at which the composter stops accepting items and starts its 20 tick wait for the bone meal. */
    private static final int FULL_LEVEL = 7;
    /** Level at which the bone meal is ready to be collected. */
    private static final int READY_LEVEL = 8;
    /** Vanilla ComposterBlock schedules the full to ready transition one second out. */
    private static final long READY_DELAY_TICKS = 20L;

    private final FarmersDelightPlugin plugin;

    public ComposterListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onComposterUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        WorldDataConfig config = WorldDataConfig.get();
        if (!config.isCompostingEnabled() || !config.hasCompostables()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.COMPOSTER) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        ItemStack used = event.getItem();
        if (used == null || used.getType().isAir()) {
            return;
        }
        Player player = event.getPlayer();
        // Vanilla skips the block interaction entirely when the player sneaks while holding an item, which is
        // what lets a block item be placed against the composter instead.
        if (player.isSneaking()) {
            return;
        }
        Float chance = config.compostChance(ItemUtils.resolveItemId(used));
        if (chance == null) {
            return;
        }
        // A CraftEngine block can be configured to report a vanilla material to Bukkit, so confirm this is the
        // real vanilla composter rather than a custom block wearing its material. isCustomBlock inspects the
        // block state class rather than the binding registry, so it still answers during a CraftEngine reload.
        if (CraftEngineBlocks.isCustomBlock(block)) {
            return;
        }
        if (!(block.getBlockData() instanceof Levelled levelled)) {
            return;
        }
        int level = levelled.getLevel();
        // A full composter takes nothing more, and a ready one hands out bone meal on the vanilla path.
        if (level >= FULL_LEVEL) {
            return;
        }
        if (!ProtectionCompat.canBuild(player, block)) {
            return;
        }
        // Both hands raise a PlayerInteractEvent once the first is reported as cancelled, and denying the
        // interaction below is exactly that report, so without this a player holding a compostable in each
        // hand would compost twice per click.
        if (!InteractionDebouncer.tryAcquire(player.getUniqueId(), block.getLocation())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }

        // Deny both halves: the block half stops any vanilla composter handling, the item half stops the
        // block items among the compostables (rice bale, mushroom colonies, pies) from being placed instead.
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        // ComposterBlock.addItem: an empty composter always takes the first item, otherwise roll the chance.
        boolean raised = (level == 0 && chance > 0.0f)
                || ThreadLocalRandom.current().nextDouble() < chance;
        if (raised) {
            levelled.setLevel(level + 1);
            block.setBlockData(levelled);
        }
        // Level event 1500 carries the success flag and produces both the particles and the fill sound.
        block.getWorld().playEffect(block.getLocation(), Effect.COMPOSTER_FILL_ATTEMPT, raised ? 1 : 0);
        if (hand == EquipmentSlot.OFF_HAND) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeOne(player.getInventory(), hand);
            // The interaction was denied, so the client still shows the stack it predicted it would keep.
            player.updateInventory();
        }
        if (raised && level + 1 == FULL_LEVEL) {
            scheduleReady(block.getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCompostItem(CompostItemEvent event) {
        WorldDataConfig config = WorldDataConfig.get();
        if (!config.isCompostingEnabled()) {
            return;
        }
        Float chance = config.compostChance(ItemUtils.resolveItemId(event.getItem()));
        if (chance == null) {
            return;
        }
        int level = event.getBlock().getBlockData() instanceof Levelled levelled ? levelled.getLevel() : 0;
        event.setWillRaiseLevel((level == 0 && chance > 0.0f)
                || ThreadLocalRandom.current().nextDouble() < chance);
    }

    private void consumeOne(PlayerInventory inventory, EquipmentSlot hand) {
        ItemStack held = inventory.getItem(hand);
        if (held == null || held.getType().isAir()) {
            return;
        }
        int amount = held.getAmount();
        if (amount <= 1) {
            inventory.setItem(hand, null);
            return;
        }
        held.setAmount(amount - 1);
        inventory.setItem(hand, held);
    }

    /**
     * Reproduces the scheduled block tick vanilla queues when a composter fills: one second later the level
     * moves from full to ready and the ready sound plays. Runs on the composter's own region.
     */
    private void scheduleReady(Location location) {
        plugin.scheduler().runLaterAt(location, () -> {
            Block block = location.getBlock();
            if (block.getType() != Material.COMPOSTER) {
                return;
            }
            if (!(block.getBlockData() instanceof Levelled levelled) || levelled.getLevel() != FULL_LEVEL) {
                return;
            }
            levelled.setLevel(READY_LEVEL);
            block.setBlockData(levelled);
            block.getWorld().playSound(location, Sound.BLOCK_COMPOSTER_READY, SoundCategory.BLOCKS, 1.0f, 1.0f);
        }, READY_DELAY_TICKS);
    }
}
