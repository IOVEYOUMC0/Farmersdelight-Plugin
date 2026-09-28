package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public class AchievementListener implements Listener {

    // Handed in by the registrar instead of looked up: the advancement system only exists while the plugin
    // is enabled, and the registrar creates this listener at exactly that point.
    private final FarmersDelightPlugin plugin;

    public AchievementListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    private static final Set<String> FD_SEED_IDS = Set.of(
            Constants.ITEM_CABBAGE_SEEDS,
            Constants.ITEM_TOMATO_SEEDS,
            Constants.ITEM_ONION,
            Constants.ITEM_RICE
    );

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        handleCraftedItem(player, event.getRecipe().getResult());
        String id = ItemUtils.getCustomItemId(event.getRecipe().getResult());
        if (id != null) FarmersDelightApi.get().awardItemAdvancements(player, id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithItem(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        handleCraftedItem(player, event.getCurrentItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        ItemStack item = event.getNewItemStack();
        String id = ItemUtils.getCustomItemId(item);
        if (id != null) {
            FarmersDelightApi.get().awardItemAdvancements(event.getPlayer(), id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        String customBlockId = CustomBlockUtils.getId(clicked);
        if (!Constants.BLOCK_TOMATO_CROP_ON_ROPE.equals(customBlockId)) return;

        AdvancementManager am = plugin.getAdvancementManager();
        if (am != null) {
            am.award(event.getPlayer(), "harvest_ropelogged_tomato");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Only the just-picked-up item can change these advancements, so avoid
        // scanning the whole inventory on every pickup.
        String pickedId = ItemUtils.getCustomItemId(event.getItem().getItemStack());
        if (pickedId == null) {
            return;
        }
        FarmersDelightApi.get().awardItemAdvancements(player, pickedId);
        AdvancementManager am = plugin.getAdvancementManager();
        if (am == null) {
            return;
        }
        if (FD_SEED_IDS.contains(pickedId) && !am.hasAdvancement(player, "get_fd_seed")) {
            am.award(player, "get_fd_seed");
        }
        if ((Constants.BLOCK_BROWN_MUSHROOM_COLONY.equals(pickedId)
                || Constants.BLOCK_RED_MUSHROOM_COLONY.equals(pickedId))
                && !am.hasAdvancement(player, "get_mushroom_colony")) {
            // Either colony color completes the advancement. Check the picked-up ID directly because
            // EntityPickupItemEvent runs before the item enters the player's inventory.
            am.award(player, "get_mushroom_colony");
        }
        if (Constants.ITEM_ORGANIC_COMPOST.equals(pickedId)) {
            am.award(player, "get_organic_compost");
        }
        if (Constants.ITEM_RICH_SOIL.equals(pickedId)) {
            am.award(player, "get_rich_soil");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        String id = ItemUtils.getCustomItemId(event.getItem());
        if (id != null) FarmersDelightApi.get().awardConsumedItemAdvancements(event.getPlayer(), id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        AdvancementManager am = plugin.getAdvancementManager();
        if (am != null) {
            am.showTo(event.getPlayer());
            am.award(event.getPlayer(), "root");
        }
        checkInventoryAdvancements(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        AdvancementManager am = plugin.getAdvancementManager();
        if (am != null) {
            am.forgetPlayer(event.getPlayer().getUniqueId());
        }
        plugin.getAddonAdvancementRegistry()
                .forgetPlayer(event.getPlayer().getUniqueId());
    }

    private void handleCraftedItem(Player player, ItemStack result) {
        if (result == null || result.getType().isAir()) return;

        AdvancementManager am = plugin.getAdvancementManager();
        if (am == null) return;

        // Checked before the CraftEngine id gate so a knife from another item source (MMOItems) is awarded too.
        if (plugin.isKnife(result)) {
            am.award(player, "craft_knife");
        }

        String customItemId = ItemUtils.getCustomItemId(result);
        if (customItemId == null) return;

        if (customItemId.equals(Constants.ITEM_NETHERITE_KNIFE)) {
            am.award(player, "obtain_netherite_knife");
        }

        if (FD_SEED_IDS.contains(customItemId)) {
            am.award(player, "get_fd_seed");
        }

        if (customItemId.equals(Constants.ITEM_SMOKED_HAM) ||
                customItemId.equals(Constants.ITEM_HAM)) {
            am.award(player, "get_ham");
        }

        if (customItemId.equals(Constants.ITEM_ORGANIC_COMPOST)) {
            am.award(player, "get_organic_compost");
        }

        if (customItemId.equals(Constants.BLOCK_BROWN_MUSHROOM_COLONY)
                || customItemId.equals(Constants.BLOCK_RED_MUSHROOM_COLONY)) {
            am.award(player, "get_mushroom_colony");
        }
    }

    private void checkInventoryAdvancements(Player player) {
        AdvancementManager am = plugin.getAdvancementManager();
        if (am == null) {
            return;
        }

        boolean seedDone = am.hasAdvancement(player, "get_fd_seed");
        boolean mushroomDone = am.hasAdvancement(player, "get_mushroom_colony");
        if (seedDone && mushroomDone) {
            return;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            String customItemId = ItemUtils.getCustomItemId(item);
            if (!seedDone && customItemId != null && FD_SEED_IDS.contains(customItemId)) {
                am.award(player, "get_fd_seed");
                seedDone = true;
            }
            if (!mushroomDone && (Constants.BLOCK_BROWN_MUSHROOM_COLONY.equals(customItemId)
                    || Constants.BLOCK_RED_MUSHROOM_COLONY.equals(customItemId))) {
                am.award(player, "get_mushroom_colony");
                mushroomDone = true;
            }
            if (seedDone && mushroomDone) return;
        }
    }
}
