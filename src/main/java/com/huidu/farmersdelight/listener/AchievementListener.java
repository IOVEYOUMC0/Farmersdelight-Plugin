package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.entity.projectile.BukkitProjectileManager;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Raider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public class AchievementListener implements Listener {

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
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithItem(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        handleCraftedItem(player, event.getCurrentItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        if (!(event.getEntity() instanceof Raider)) return;
        if (!(projectile.getShooter() instanceof Player player)) return;

        BukkitProjectileManager projectileManager = BukkitProjectileManager.instance();
        if (projectileManager == null) return;

        String customItemId = projectileManager.projectileByEntityId(projectile.getEntityId())
                .map(customProjectile -> customProjectile.item().id().toString())
                .orElse(null);
        if (Constants.ITEM_ROTTEN_TOMATO.equals(customItemId)) {
            AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
            if (am != null) {
                am.award(player, "hit_raider_with_rotten_tomato");
            }
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

        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
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
        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am == null) {
            return;
        }
        if (FD_SEED_IDS.contains(pickedId) && !am.hasAdvancement(player, "get_fd_seed")) {
            am.award(player, "get_fd_seed");
        }
        if (Constants.BLOCK_BROWN_MUSHROOM_COLONY.equals(pickedId)
                || Constants.BLOCK_RED_MUSHROOM_COLONY.equals(pickedId)) {
            // Both colors are required, so a scan is still needed, but only
            // when a relevant item is actually picked up.
            checkMushroomColonyAdvancement(player);
        }
        if (Constants.ITEM_ORGANIC_COMPOST.equals(pickedId)) {
            am.award(player, "get_organic_compost");
        }
        if (Constants.ITEM_RICH_SOIL.equals(pickedId)) {
            am.award(player, "get_rich_soil");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am != null) {
            am.showTo(event.getPlayer());
            am.award(event.getPlayer(), "root");
        }
        checkSeedAdvancement(event.getPlayer());
        checkMushroomColonyAdvancement(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am != null) {
            am.forgetPlayer(event.getPlayer().getUniqueId());
        }
        FarmersDelightPlugin.getInstance().getAddonAdvancementRegistry()
                .forgetPlayer(event.getPlayer().getUniqueId());
    }

    private void handleCraftedItem(Player player, ItemStack result) {
        if (result == null || result.getType().isAir()) return;

        String customItemId = ItemUtils.getCustomItemId(result);
        if (customItemId == null) return;

        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am == null) return;

        if (FarmersDelightPlugin.getInstance().isKnifeItemId(customItemId)) {
            am.award(player, "craft_knife");
        }

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
    }

    private void checkSeedAdvancement(Player player) {
        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am == null || am.hasAdvancement(player, "get_fd_seed")) {
            return;
        }

        for (ItemStack item : player.getInventory().getContents()) {
            String customItemId = ItemUtils.getCustomItemId(item);
            if (customItemId != null && FD_SEED_IDS.contains(customItemId)) {
                am.award(player, "get_fd_seed");
                return;
            }
        }
    }

    private void checkMushroomColonyAdvancement(Player player) {
        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am == null || am.hasAdvancement(player, "get_mushroom_colony")) {
            return;
        }

        boolean hasBrown = false;
        boolean hasRed = false;
        for (ItemStack item : player.getInventory().getContents()) {
            String customItemId = ItemUtils.getCustomItemId(item);
            if (Constants.BLOCK_BROWN_MUSHROOM_COLONY.equals(customItemId)) {
                hasBrown = true;
            } else if (Constants.BLOCK_RED_MUSHROOM_COLONY.equals(customItemId)) {
                hasRed = true;
            }
            if (hasBrown && hasRed) {
                am.award(player, "get_mushroom_colony");
                return;
            }
        }
    }
}

