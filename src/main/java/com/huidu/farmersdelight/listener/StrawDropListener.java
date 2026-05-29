package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.config.StrawDropConfig;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class StrawDropListener implements Listener {

    private final FarmersDelightPlugin plugin;

    public StrawDropListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!FarmersDelightPlugin.isEnabled0()) return;

        Player player = event.getPlayer();
        Block block = event.getBlock();
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!isKnife(tool)) return;

        StrawDropConfig.StrawDropRule rule = getStrawDropRule(block);
        if (rule != null) {
            dropStraw(block, rule);
            
            AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
            if (advancementManager != null) {
                advancementManager.award(player, "harvest_straw");
            }
        }
    }

    private boolean isKnife(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;

        String customItemId = ItemUtils.getCustomItemId(item);
        if (plugin.isKnifeItemId(customItemId)) {
            return true;
        }

        if (customItemId != null) {
            Set<Key> itemTags = ItemUtils.getCustomItemTags(Key.of(customItemId));
            return itemTags.stream().anyMatch(tag ->
                    plugin.getKnifeTagIds().stream().anyMatch(knifeTag -> tag.toString().equalsIgnoreCase(knifeTag)));
        }

        return false;
    }
    private StrawDropConfig.StrawDropRule getStrawDropRule(Block block) {
        StrawDropConfig config = plugin.getStrawDropConfig();
        if (config == null) return null;

        Material type = block.getType();

        if (type == Material.TALL_GRASS) {
            return config.getRule("tall_grass");
        }

        if (type == Material.SHORT_GRASS) {
            StrawDropConfig.StrawDropRule shortGrassRule = config.getRule("short_grass");
            if (shortGrassRule != null) {
                return shortGrassRule;
            }
            return config.getRule("grass");
        }

        if (type == Material.WHEAT) {
            if (block.getBlockData() instanceof Ageable ageable) {
                if (ageable.getAge() >= ageable.getMaximumAge()) {
                    return config.getRule("mature_wheat");
                }
            }
        }

        if (isMatureRicePanicles(block)) {
            return config.getRule("mature_rice");
        }

        return config.getRule(type.name().toLowerCase());
    }

    private boolean isMatureRicePanicles(Block block) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) {
            return false;
        }

        if (!Constants.BLOCK_RICE.equals(CustomBlockUtils.getId(state))) {
            return false;
        }

        String half = CustomBlockUtils.getPropertyString(state, "half");
        if (!"upper".equalsIgnoreCase(half)) {
            return false;
        }

        Integer age = CustomBlockUtils.getPropertyInt(state, "age");
        TallCropBlockBehavior behavior = TallCropBlockBehavior.getBehavior(state);
        int maxUpperAge = behavior != null ? behavior.getMaxAgeUpper() : 3;
        if (age == null || age != maxUpperAge) {
            return false;
        }

        Block lowerBlock = block.getRelative(org.bukkit.block.BlockFace.DOWN);
        ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
        if (lowerState == null || lowerState.isEmpty()) {
            return false;
        }
        if (behavior != null) {
            return behavior.isLowerHalf(lowerState) && behavior.isLowerMature(lowerState);
        }
        Integer lowerAge = CustomBlockUtils.getPropertyInt(lowerState, "age");
        return Constants.BLOCK_RICE.equals(CustomBlockUtils.getId(lowerState))
                && lowerAge != null
                && lowerAge >= 4;
    }

    private void dropStraw(Block block, StrawDropConfig.StrawDropRule rule) {
        if (rule == null || rule.getDropItem() == null) return;
        
        Key dropKey = Key.of(rule.getDropItem());
        
        ItemStack drop = ItemUtils.createItem(dropKey);
        if (drop != null) {
            int minAmount = rule.getMinAmount();
            int maxAmount = rule.getMaxAmount();
            int amount = minAmount + ThreadLocalRandom.current().nextInt(maxAmount - minAmount + 1);
            drop.setAmount(amount);
            
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }
    }
}

