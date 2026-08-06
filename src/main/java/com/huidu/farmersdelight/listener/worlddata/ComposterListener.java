package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.InteractionDebouncer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
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

public final class ComposterListener implements Listener {

    private static final int FULL_LEVEL = 7;
    private static final int READY_LEVEL = 8;
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
