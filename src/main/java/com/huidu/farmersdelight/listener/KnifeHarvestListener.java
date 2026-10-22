package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.tag.FarmersDelightTags;
import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.CropBlockBehavior;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.setting.BlockSettings;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Set;

/**
 * Cuts a mature crop with a knife: the loot drops as usual and the crop is replanted at age 0. An immature
 * crop cannot be cut by a knife, sneaking keeps the ordinary break, and the crops that harvest by
 * right-click are left alone.
 *
 * Both crop kinds are handled: CraftEngine crops (what rich soil farmland plants) and the plain vanilla
 * crop blocks planted on vanilla farmland.
 */
public class KnifeHarvestListener implements Listener {

    private static final Key UNAFFECTED_TAG = Key.of(FarmersDelightTags.BLOCK_UNAFFECTED_BY_KNIFE_HARVEST);
    // Vanilla CropBlock variants. Melon/pumpkin stems, nether wart, cocoa and sweet berry bushes are
    // ageable too but FD does not cut them.
    private static final Set<Material> VANILLA_CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.PITCHER_CROP, Material.TORCHFLOWER_CROP);

    private final FarmersDelightPlugin plugin;

    public KnifeHarvestListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (event.isCancelled() || player.getGameMode() == GameMode.CREATIVE || player.isSneaking()) {
            return;
        }
        Block block = event.getBlock();
        // Resolve what the block is before testing the item: the state read is cheaper than the item-identity
        // and tag lookups behind isKnife, and an ordinary break never pays for them.
        ImmutableBlockState state = CustomBlockUtils.getState(block);
        if (state != null && !state.isEmpty()) {
            cutCraftEngineCrop(event, player, block, state);
        } else if (VANILLA_CROPS.contains(block.getType())) {
            cutVanillaCrop(event, player, block);
        }
    }

    private void cutCraftEngineCrop(BlockBreakEvent event, Player player, Block block, ImmutableBlockState state) {
        CropBlockBehavior crop = CustomBlockUtils.getBehavior(state, CropBlockBehavior.class);
        if (crop == null || CustomBlockUtils.hasBehavior(state, TomatoVineBlockBehavior.class)
                || isUnaffectedByKnifeHarvest(state)) {
            return;
        }
        if (!plugin.isKnife(player.getInventory().getItemInMainHand())) {
            return;
        }
        KnifeHarvestRules.Decision decision = KnifeHarvestRules.decide(true, false, crop.isMaxAge(state));
        if (decision == KnifeHarvestRules.Decision.CANCEL) {
            event.setCancelled(true);
            return;
        }
        // CraftEngine removes the block after this event, so the regrown crop is placed next tick.
        ImmutableBlockState regrown = state.with(crop.ageProperty, 0);
        Location location = block.getLocation();
        plugin.scheduler().runLaterAt(location, () -> replantCustom(location, regrown), 1L);
    }

    private void cutVanillaCrop(BlockBreakEvent event, Player player, Block block) {
        if (!plugin.isKnife(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (!(block.getBlockData() instanceof Ageable crop)) {
            return;
        }
        KnifeHarvestRules.Decision decision = KnifeHarvestRules.decide(
                true, false, crop.getAge() >= crop.getMaximumAge());
        if (decision == KnifeHarvestRules.Decision.CANCEL) {
            event.setCancelled(true);
            return;
        }
        BlockData young = crop.clone();
        ((Ageable) young).setAge(0);
        Location location = block.getLocation();
        plugin.scheduler().runLaterAt(location, () -> replantVanilla(location, young), 1L);
    }

    private static void replantCustom(Location location, ImmutableBlockState regrown) {
        Block block = location.getBlock();
        if (block.getType().isAir()) {
            CraftEngineBlocks.place(location, regrown, false);
        }
    }

    private static void replantVanilla(Location location, BlockData young) {
        Block block = location.getBlock();
        if (block.getType().isAir()) {
            block.setBlockData(young, false);
        }
    }

    static boolean isVanillaCrop(Material material) {
        return VANILLA_CROPS.contains(material);
    }

    private static boolean isUnaffectedByKnifeHarvest(ImmutableBlockState state) {
        BlockSettings settings = state.settings();
        return settings != null && settings.tags() != null && settings.tags().contains(UNAFFECTED_TAG);
    }
}
