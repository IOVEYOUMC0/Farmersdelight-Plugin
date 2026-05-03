package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.config.StrawDropConfig;
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

import java.util.List;
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

        List<String> knifeItems = plugin.getConfig().getStringList("knife-config.items");
        List<String> configuredKnifeTags = plugin.getConfig().getStringList("knife-config.tags");
        List<String> knifeTags = configuredKnifeTags.isEmpty()
                ? List.of("farmersdelight:knives")
                : configuredKnifeTags;

        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId != null && knifeItems.contains(customItemId)) {
            return true;
        }

        if (customItemId != null) {
            var customItem = plugin.getCraftEngine().itemManager().getCustomItem(Key.of(customItemId)).orElse(null);
            if (customItem != null) {
                Set<Key> itemTags = customItem.settings().tags();
                return itemTags.stream().anyMatch(tag ->
                        knifeTags.stream().anyMatch(knifeTag -> tag.toString().equalsIgnoreCase(knifeTag)));
            }
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

        if (!"farmersdelight:rice".equals(CustomBlockUtils.getId(state))) {
            return false;
        }

        String half = CustomBlockUtils.getPropertyString(state, "half");
        if (!"upper".equalsIgnoreCase(half)) {
            return false;
        }

        Integer age = CustomBlockUtils.getPropertyInt(state, "age");
        return age != null && age >= 3;
    }

    private void dropStraw(Block block, StrawDropConfig.StrawDropRule rule) {
        if (rule == null || rule.getDropItem() == null) return;
        
        Key dropKey = Key.of(rule.getDropItem());
        
        var customItem = plugin.getCraftEngine().itemManager().getCustomItem(dropKey).orElse(null);
        
        if (customItem != null) {
            ItemStack drop = customItem.buildItemStack();
            int minAmount = rule.getMinAmount();
            int maxAmount = rule.getMaxAmount();
            int amount = minAmount + ThreadLocalRandom.current().nextInt(maxAmount - minAmount + 1);
            drop.setAmount(amount);
            
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }
    }
}
