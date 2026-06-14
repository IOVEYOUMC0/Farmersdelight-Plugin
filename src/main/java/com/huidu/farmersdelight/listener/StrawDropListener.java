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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class StrawDropListener implements Listener {

    private final FarmersDelightPlugin plugin;

    // 缓存“自定义物品 id -> 是否算小刀（按 id 或标签判定）”，避免每次破坏方块都重新分配
    // ItemUtils.getCustomItemTags(...) 的 Set 并做嵌套 stream 扫描。
    // 仅缓存自定义物品（customId != null）的判定；vanilla 短路逻辑不入缓存。
    // 用 ConcurrentHashMap 是因为 onBlockBreak 在 Folia 下可能由不同 region 线程触发。
    private final Map<String, Boolean> knifeCache = new ConcurrentHashMap<>();

    // 失效依据：FarmersDelightPlugin 在 onEnable 只 new 出一个 StrawDropListener 实例，
    // /fd reload（reloadAll / reloadMainConfigOnly）只调用 loadConfigs() 在“同一实例”上
    // 把 knifeItemIds / knifeTagIds 重新赋值为全新的不可变 Set，并不会重建本监听器。
    // 因此实例字段缓存不会随 reload 自动失效。而本文件不允许改动 FarmersDelightPlugin，
    // 无法在重载路径里显式调用清空方法，于是改用“引用身份快照”来检测重载：
    // loadConfigs() 每次都生成全新的 Set 对象（toUnmodifiableSet / Set.of），且这些 Set 不可变、
    // 只会被整体替换而不会原地修改，故只要任一引用发生变化即说明 knife 配置已重载，需清空缓存。
    private volatile Set<String> cachedKnifeItemIds;
    private volatile Set<String> cachedKnifeTagIds;

    public StrawDropListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    // 若 plugin 当前的 knife 配置 Set 与缓存构建时捕获的引用不同（即发生过 /fd reload），
    // 则清空缓存并刷新快照，保证 reload 后返回的是新结果。
    private void invalidateKnifeCacheIfConfigReloaded() {
        Set<String> currentItemIds = plugin.getKnifeItemIds();
        Set<String> currentTagIds = plugin.getKnifeTagIds();
        if (currentItemIds != cachedKnifeItemIds || currentTagIds != cachedKnifeTagIds) {
            knifeCache.clear();
            cachedKnifeItemIds = currentItemIds;
            cachedKnifeTagIds = currentTagIds;
        }
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

        // vanilla（customId == null）保持原有短路逻辑，不进缓存。
        if (customItemId == null) {
            return false;
        }

        // 在读取缓存前先检测是否发生过 /fd reload，必要时清空过期结果。
        invalidateKnifeCacheIfConfigReloaded();

        Boolean cached = knifeCache.get(customItemId);
        if (cached != null) {
            return cached;
        }

        boolean result = computeIsKnife(customItemId);
        knifeCache.put(customItemId, result);
        return result;
    }

    // 实际判定：先按 id 命中 knife 集合，否则走标签兜底。仅在缓存未命中时调用一次。
    private boolean computeIsKnife(String customItemId) {
        if (plugin.isKnifeItemId(customItemId)) {
            return true;
        }

        Set<Key> itemTags = ItemUtils.getCustomItemTags(Key.of(customItemId));
        return itemTags.stream().anyMatch(tag ->
                plugin.getKnifeTagIds().stream().anyMatch(knifeTag -> tag.toString().equalsIgnoreCase(knifeTag)));
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

        return config.getRule(type.name().toLowerCase(java.util.Locale.ROOT));
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
            int maxAmount = Math.max(minAmount, rule.getMaxAmount());
            int amount = minAmount + ThreadLocalRandom.current().nextInt(maxAmount - minAmount + 1);
            if (amount <= 0) {
                return;
            }
            drop.setAmount(amount);

            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }
    }
}

