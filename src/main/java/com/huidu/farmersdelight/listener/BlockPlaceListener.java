package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.util.CookingPotItemDataHelper;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlockPlaceListener implements Listener {

    private static final Set<String> FEAST_BLOCKS = Set.of(
            "farmersdelight:roast_chicken_block",
            "farmersdelight:stuffed_pumpkin_block",
            "farmersdelight:honey_glazed_ham_block",
            "farmersdelight:shepherds_pie_block",
            "farmersdelight:rice_roll_medley_block"
    );
    private static final Map<Material, String> VANILLA_CROP_CRITERIA = Map.ofEntries(
            Map.entry(Material.WHEAT, "wheat"),
            Map.entry(Material.BEETROOTS, "beetroot"),
            Map.entry(Material.CARROTS, "carrot"),
            Map.entry(Material.POTATOES, "potato"),
            Map.entry(Material.MELON_STEM, "melon"),
            Map.entry(Material.PUMPKIN_STEM, "pumpkin"),
            Map.entry(Material.SWEET_BERRY_BUSH, "sweet_berries"),
            Map.entry(Material.SUGAR_CANE, "sugar_cane"),
            Map.entry(Material.KELP, "kelp"),
            Map.entry(Material.KELP_PLANT, "kelp"),
            Map.entry(Material.COCOA, "cocoa"),
            Map.entry(Material.NETHER_WART, "nether_wart"),
            Map.entry(Material.CHORUS_FLOWER, "chorus_flower"),
            Map.entry(Material.BROWN_MUSHROOM, "brown_mushroom"),
            Map.entry(Material.RED_MUSHROOM, "red_mushroom"),
            Map.entry(Material.CAVE_VINES, "glow_berries"),
            Map.entry(Material.CAVE_VINES_PLANT, "glow_berries")
    );
    private static final Map<PlacedItemKey, ItemStack> pendingPlacedItems = new ConcurrentHashMap<>();
    private static BukkitTask cleanupTask;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        StoveManager stoveManager = FarmersDelightPlugin.getInstance().getStoveManager();
        stoveManager.invalidateBlockedAboveCache(event.getBlock().getLocation().clone().add(0, -1, 0));

        String customBlockId = getCustomBlockId(event);
        if (customBlockId == null) {
            awardPlantAllCropsCriterion(player, VANILLA_CROP_CRITERIA.get(event.getBlock().getType()));
            return;
        }

        awardForCustomBlock(player, customBlockId, event.getBlock().getLocation(), event.getItemInHand());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomBlockPlace(CustomBlockPlaceEvent event) {
        String customBlockId = event.customBlock().id().toString();
        ItemStack placedItem = consumePendingPlacedItem(event.player(), event.hand(), customBlockId);
        if (placedItem == null) {
            placedItem = event.hand() == InteractionHand.OFF_HAND
                    ? event.player().getInventory().getItemInOffHand()
                    : event.player().getInventory().getItemInMainHand();
        }
        awardForCustomBlock(event.player(), customBlockId, event.location(), placedItem);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCustomBlockAttemptPlace(CustomBlockAttemptPlaceEvent event) {
        String customBlockId = event.customBlock().id().toString();
        cacheAttemptedPlaceItem(event.player(), event.hand(), customBlockId);
    }

    private void awardForCustomBlock(Player player, String customBlockId, org.bukkit.Location blockLocation, ItemStack placedItem) {
        if (player == null || customBlockId == null) {
            return;
        }

        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am == null) return;

        if (customBlockId.equals("farmersdelight:cooking_pot")) {
            CookingPotBlockBehavior.markRecentlyPlaced(blockLocation);
            if (CookingPotItemDataHelper.isEnabled() && placedItem != null && !placedItem.getType().isAir()) {
                CookingPotItemDataHelper.restorePackedData(blockLocation, placedItem);
            }
            am.award(player, "place_cooking_pot");
        }

        if (customBlockId.equals("farmersdelight:skillet")) {
            FarmersDelightPlugin.getInstance().getSkilletManager().recordPlacedSkillet(blockLocation, placedItem);
            am.award(player, "place_skillet");
        }

        if (FEAST_BLOCKS.contains(customBlockId.toLowerCase())) {
            am.award(player, "place_feast");
        }

        awardPlantAllCropsCriterion(player, getCustomCropCriterion(customBlockId));
    }

    private String getCustomCropCriterion(String customBlockId) {
        return switch (customBlockId.toLowerCase()) {
            case "farmersdelight:cabbages" -> "cabbage";
            case "farmersdelight:budding_tomatoes", "farmersdelight:tomatoes" -> "tomato";
            case "farmersdelight:onions" -> "onion";
            case "farmersdelight:rice" -> "rice";
            default -> null;
        };
    }

    private void awardPlantAllCropsCriterion(Player player, String criterion) {
        if (criterion == null) {
            return;
        }

        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am != null) {
            // Vanilla placed_block cannot see CE custom crop IDs, so the plugin
            // mirrors the original criterion names and advances them manually.
            am.awardCriteria(player, "plant_all_crops", criterion);
        }
    }

    private String getCustomBlockId(BlockPlaceEvent event) {
        return CustomBlockUtils.getId(event.getBlock());
    }

    private void cacheAttemptedPlaceItem(Player player, InteractionHand hand, String customBlockId) {
        if (player == null || hand == null || customBlockId == null) {
            return;
        }

        ItemStack itemInHand = hand == InteractionHand.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType().isAir()) {
            return;
        }

        ItemStack snapshot = itemInHand.clone();
        snapshot.setAmount(1);
        pendingPlacedItems.put(new PlacedItemKey(player.getUniqueId(), hand, customBlockId), snapshot);
        ensureCleanupTask();
    }

    private ItemStack consumePendingPlacedItem(Player player, InteractionHand hand, String customBlockId) {
        if (player == null || hand == null || customBlockId == null) {
            return null;
        }
        return pendingPlacedItems.remove(new PlacedItemKey(player.getUniqueId(), hand, customBlockId));
    }

    private void ensureCleanupTask() {
        if (cleanupTask != null) {
            return;
        }

        cleanupTask = FarmersDelightPlugin.getInstance().getServer().getScheduler().runTaskLater(
                FarmersDelightPlugin.getInstance(),
                () -> {
                    pendingPlacedItems.clear();
                    cleanupTask = null;
                },
                2L
        );
    }

    private record PlacedItemKey(UUID playerId, InteractionHand hand, String blockId) {
    }
}
