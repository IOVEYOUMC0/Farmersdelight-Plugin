package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.config.StrawDropConfig;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.GameMode;
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

    // Caches "custom item id -> is knife (by id or tag)" to avoid reallocating the
    // ItemUtils.getCustomItemTags(...) Set and running a nested stream scan on every block break.
    // Only custom items (customId != null) are cached; the vanilla short-circuit isn't cached.
    // ConcurrentHashMap because onBlockBreak may be triggered by different region threads on Folia.
    private final Map<String, Boolean> knifeCache = new ConcurrentHashMap<>();

    // Invalidation rationale: FarmersDelightPlugin only news up one StrawDropListener in onEnable, and
    // /fd reload (reloadAll / reloadMainConfigOnly) only calls loadConfigs() on the same instance to
    // Knife settings are replaced by an immutable snapshot on reload, without rebuilding this listener.
    // So the instance-field cache isn't auto-invalidated on reload. Since this file may not modify FarmersDelightPlugin,
    // there's no way to explicitly call a clear method in the reload path, so a "reference-identity snapshot" detects reloads:
    // loadConfigs() always produces fresh Set objects (toUnmodifiableSet / Set.of), and those Sets are immutable,
    // replaced wholesale rather than mutated in place, so any reference change means the knife config reloaded and the cache must be cleared.
    private volatile Set<String> cachedKnifeItemIds;
    private volatile Set<String> cachedKnifeTagIds;

    public StrawDropListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    // If the plugin's current knife config Sets differ from the references captured when the cache was built (i.e. a /fd reload happened),
    // clear the cache and refresh the snapshot so post-reload results are returned.
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

        // Same drop gate as BlockBreakListener.onCustomBlockBreak: isDropItems() stays true in creative
        // (vanilla suppresses drops inside the server, not on the event), so the game mode has to be
        // checked separately or a creative break mints straw.
        if (!event.isDropItems() || player.getGameMode() == GameMode.CREATIVE) return;

        StrawDropConfig.StrawDropRule rule = getStrawDropRule(block);
        if (rule != null) {
            if (!isStrawDroppedByVanillaLootEntry(block)) {
                dropStraw(block, rule);
            }

            AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
            if (advancementManager != null) {
                advancementManager.award(player, "harvest_straw");
            }
        }
    }

    // Short grass, tall grass and mature wheat already drop straw through the CraftEngine vanilla loot
    // entries in vanilla_loots.yml, which reproduce the original mod's knife check and its 0.2 chance on
    // the two grasses. Dropping it here as well would give those blocks two independent straw sources, so
    // this listener yields the item for them and keeps only the advancement award. Mature rice stays with
    // this listener: it is a CraftEngine block, which the vanilla loot injection cannot target. Any other
    // block key an admin adds under drops.yml's straw section also keeps dropping through this listener.
    private boolean isStrawDroppedByVanillaLootEntry(Block block) {
        Material type = block.getType();
        if (type == Material.SHORT_GRASS || type == Material.TALL_GRASS) {
            return true;
        }
        if (type == Material.WHEAT && block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return false;
    }

    private boolean isKnife(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;

        String customItemId = ItemUtils.getCustomItemId(item);

        // Vanilla items (customId == null) short-circuit immediately and don't enter the cache.
        if (customItemId == null) {
            return false;
        }

        // Detect whether a /fd reload happened before reading the cache, clearing stale results if needed.
        invalidateKnifeCacheIfConfigReloaded();

        Boolean cached = knifeCache.get(customItemId);
        if (cached != null) {
            return cached;
        }

        boolean result = computeIsKnife(customItemId);
        knifeCache.put(customItemId, result);
        return result;
    }

    // Actual check: match the knife id set first, otherwise fall back to tags. Called only once on a cache miss.
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

        if (!CustomBlockUtils.hasBehavior(state, TallCropBlockBehavior.class)) {
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
        return CustomBlockUtils.hasBehavior(lowerState, TallCropBlockBehavior.class)
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
