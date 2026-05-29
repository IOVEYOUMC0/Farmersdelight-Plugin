package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntityController;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlockPlaceListener implements Listener {

    private static final Set<String> FEAST_BLOCKS = Set.of(
            Constants.BLOCK_ROAST_CHICKEN,
            Constants.BLOCK_STUFFED_PUMPKIN,
            Constants.BLOCK_HONEY_GLAZED_HAM,
            Constants.BLOCK_SHEPHERDS_PIE,
            Constants.BLOCK_GLEAMING_SALAD,
            Constants.BLOCK_RICE_ROLL_MEDLEY
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
    private static PluginTask cleanupTask;

    public static void cleanup() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        pendingPlacedItems.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        StoveManager stoveManager = FarmersDelightPlugin.getInstance().getStoveManager();
        stoveManager.invalidateBlockedAboveCache(event.getBlock().getLocation().clone().add(0, -1, 0));
        syncTraysAroundSupportChange(event.getBlock().getLocation());

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
        syncTraysAroundSupportChange(event.location());
        ItemStack placedItem = consumePendingPlacedItem(event.player(), event.hand(), customBlockId);
        if (placedItem == null) {
            placedItem = event.hand() == InteractionHand.OFF_HAND
                    ? event.player().getInventory().getItemInOffHand()
                    : event.player().getInventory().getItemInMainHand();
        }
        awardForCustomBlock(event.player(), customBlockId, event.location(), placedItem);
    }

    private void syncTraysAroundSupportChange(Location location) {
        if (location == null) {
            return;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || plugin.getTrayManager() == null) {
            return;
        }
        plugin.getTrayManager().syncAroundSupportChange(location);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCustomBlockAttemptPlace(CustomBlockAttemptPlaceEvent event) {
        String customBlockId = event.customBlock().id().toString();
        
        if (isMushroomColony(customBlockId) && !canMushroomColonySurvive(event)) {
            event.setCancelled(true);
            return;
        }
        
        cacheAttemptedPlaceItem(event.player(), event.hand(), customBlockId);
    }

    private static final Set<String> MUSHROOM_COLONY_IDS = Set.of(
            Constants.BLOCK_BROWN_MUSHROOM_COLONY,
            Constants.BLOCK_RED_MUSHROOM_COLONY
    );

    private static final Set<Material> MUSHROOM_GROW_BLOCKS = Set.of(
            Material.MYCELIUM,
            Material.PODZOL,
            Material.CRIMSON_NYLIUM,
            Material.WARPED_NYLIUM,
            Material.MUSHROOM_STEM
    );

    private boolean isMushroomColony(String customBlockId) {
        return MUSHROOM_COLONY_IDS.contains(customBlockId);
    }

    private boolean canMushroomColonySurvive(CustomBlockAttemptPlaceEvent event) {
        World world = event.player().getWorld();
        Block blockBelow = world.getBlockAt(
                event.location().getBlockX(), event.location().getBlockY() - 1, event.location().getBlockZ()
        );
        if (MUSHROOM_GROW_BLOCKS.contains(blockBelow.getType())) {
            return true;
        }
        Block targetBlock = world.getBlockAt(
                event.location().getBlockX(), event.location().getBlockY(), event.location().getBlockZ()
        );
        return targetBlock.getLightLevel() < Constants.MUSHROOM_COLONY_MAX_LIGHT && blockBelow.getType().isSolid();
    }

    private void awardForCustomBlock(Player player, String customBlockId, org.bukkit.Location blockLocation, ItemStack placedItem) {
        if (player == null || customBlockId == null) {
            return;
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        AdvancementManager am = plugin.getAdvancementManager();

        if (isCookingPotPlacement(customBlockId, blockLocation)) {
            CookingPotBlockBehavior.markRecentlyPlaced(blockLocation);
            CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(blockLocation);
            restoreCookingPotDataFromPlacedItem(entity, blockLocation.getWorld(), placedItem);
            if (plugin.getTrayManager() != null) {
                plugin.getTrayManager().checkAndPlaceTray(blockLocation);
            }
            if (am != null) {
                am.award(player, "place_cooking_pot");
            }
        }

        if (customBlockId.equals(Constants.BLOCK_CUTTING_BOARD)) {
            CuttingBoardBlockBehavior.putBlockEntity(
                    blockLocation.getWorld(),
                    new BlockPosKey(blockLocation),
                    new CuttingBoardBlockEntity(new BlockPosKey(blockLocation), blockLocation.getWorld())
            );
        }

        if (customBlockId.equals(Constants.BLOCK_SKILLET)) {
            plugin.getSkilletManager().recordPlacedSkillet(blockLocation, placedItem);
            if (am != null) {
                am.award(player, "place_skillet");
            }
        }

        if (am != null && FEAST_BLOCKS.contains(customBlockId.toLowerCase())) {
            am.award(player, "place_feast");
        }

        awardPlantAllCropsCriterion(player, getCustomCropCriterion(customBlockId));
    }

    private boolean isCookingPotPlacement(String customBlockId, org.bukkit.Location blockLocation) {
        if (Constants.BLOCK_COOKING_POT.equals(customBlockId)) {
            return true;
        }
        return CookingPotBlockBehavior.getBlockBehavior(blockLocation) != null;
    }

    private void restoreCookingPotDataFromPlacedItem(CookingPotBlockEntity entity, World world, ItemStack placedItem) {
        if (entity == null || world == null || placedItem == null || placedItem.getType().isAir()) {
            return;
        }
        var behavior = CookingPotBlockBehavior.getBlockBehavior(entity.getPosKey().toLocation(world));
        if (behavior == null) {
            return;
        }
        Item wrapped = BukkitItemManager.instance().wrap(placedItem);
        CompoundTag data = CustomBlockUtils.getNestedComponentCompound(wrapped, DataComponentKeys.CUSTOM_DATA, behavior.getCustomDataKey());
        if (data == null) {
            return;
        }
        CookingPotBlockEntityController.loadDataIntoEntity(entity, data);
        TickManager tickManager = FarmersDelightPlugin.getInstance().getTickManager();
        if (tickManager != null && entity.hasStoredContents()) {
            tickManager.markActive(world, entity.getPosKey(), TickManager.BlockType.COOKING_POT);
        }
        CookingPotBlockBehavior.saveBlockEntityData(world, entity.getPosKey());
    }

    private String getCustomCropCriterion(String customBlockId) {
        return switch (customBlockId.toLowerCase()) {
            case Constants.BLOCK_CABBAGES -> "cabbage";
            case Constants.BLOCK_BUDDING_TOMATOES, Constants.BLOCK_TOMATOES -> "tomato";
            case Constants.BLOCK_ONIONS -> "onion";
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

        cleanupTask = FarmersDelightPlugin.getInstance().scheduler().runLater(
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

