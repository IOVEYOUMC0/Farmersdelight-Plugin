package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.config.StrawDropConfig;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Awards the harvest_straw advancement when a knife breaks a configured block. The straw item
 * itself is produced by the CraftEngine packs, so this listener holds no drop logic.
 */
public class StrawDropListener implements Listener {

    private final FarmersDelightPlugin plugin;

    // Caches "custom item id -> is knife (by id or tag)" to avoid reallocating the
    // ItemUtils.getCustomItemTags(...) Set and running a nested stream scan on every block break.
    // Only custom items (customId != null) are cached; the vanilla short-circuit isn't cached.
    // ConcurrentHashMap because onBlockBreak may be triggered by different region threads on Folia.
    private final Map<String, Boolean> knifeCache = new ConcurrentHashMap<>();

    // Reload replaces knife configuration sets with fresh immutable instances.
    // Compare their identities to invalidate this listener's cache when the settings change.
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

        if (isStrawBlock(block)) {
            AdvancementManager advancementManager = plugin.getAdvancementManager();
            if (advancementManager != null) {
                advancementManager.award(player, "harvest_straw");
            }
        }
    }

    // The straw item itself comes from the CraftEngine packs: grass and mature wheat through
    // vanilla_loots.yml, mature rice through the break-loot chain on farmersdelight:rice (which the
    // right-click harvest path runs too, see TallCropBlockBehavior). This listener only decides whether
    // the break earns the harvest_straw advancement.

    private boolean isKnife(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        // Cache on the item's resolved identity (CraftEngine id, mmoitems:<TYPE>:<ID>, vanilla id) so an
        // MMOItems or configured vanilla knife is cached too instead of being rejected before the check.
        String identity = ItemUtils.resolveItemId(item);
        if (identity == null) {
            return false;
        }

        // Detect whether a /fd reload happened before reading the cache, clearing stale results if needed.
        invalidateKnifeCacheIfConfigReloaded();

        Boolean cached = knifeCache.get(identity);
        if (cached != null) {
            return cached;
        }

        boolean result = plugin.isKnife(item);
        knifeCache.put(identity, result);
        return result;
    }

    private boolean isStrawBlock(Block block) {
        StrawDropConfig config = plugin.getStrawDropConfig();
        if (config == null) return false;

        Material type = block.getType();

        if (type == Material.TALL_GRASS) {
            return config.hasRule("tall_grass");
        }

        if (type == Material.SHORT_GRASS) {
            return config.hasRule("short_grass") || config.hasRule("grass");
        }

        if (type == Material.WHEAT) {
            if (block.getBlockData() instanceof Ageable ageable) {
                if (ageable.getAge() >= ageable.getMaximumAge()) {
                    return config.hasRule("mature_wheat");
                }
            }
        }

        if (isMatureRicePanicles(block)) {
            return config.hasRule("mature_rice");
        }

        return config.hasRule(type.name().toLowerCase(Locale.ROOT));
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

        Block lowerBlock = block.getRelative(BlockFace.DOWN);
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
}
