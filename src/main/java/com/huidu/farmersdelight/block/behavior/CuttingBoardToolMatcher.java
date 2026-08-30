package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Set;

// Pure tool-recognition logic for the cutting board: decides whether an item can act as a cutting tool
// against the configured tag / item allowlists. Carries the per-instance config lists so the block
// behavior stays focused on interaction flow.
final class CuttingBoardToolMatcher {

    private final List<Key> toolTags;
    private final List<Key> toolItems;

    CuttingBoardToolMatcher(List<Key> toolTags, List<Key> toolItems) {
        this.toolTags = toolTags;
        this.toolItems = toolItems;
    }

    boolean isTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin.getCuttingBoardRecipes() != null && plugin.getCuttingBoardRecipes().isRecipeTool(item)) {
            return true;
        }

        // Vanilla swords are not knives. They are accepted only when a recipe explicitly names one.
        if (item.getType().name().endsWith("_SWORD")) {
            return false;
        }

        if (isKnifeTool(item) || isAxeTool(item) || isPickaxeTool(item) || isShovelTool(item) || isConfiguredToolItem(item)) {
            return true;
        }

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            Set<Key> itemTags = ItemUtils.getCustomItemTags(Key.of(customId));
            for (Key tag : toolTags) {
                if (itemTags.contains(tag)) {
                    return true;
                }
            }
        }

        String vanillaId = "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
        for (Key toolTag : toolTags) {
            if (!"minecraft".equals(toolTag.namespace())) {
                continue;
            }
            var vanillaItems = FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                    .vanillaItemIdsByTag(toolTag);
            for (var vanillaItem : vanillaItems) {
                if (vanillaItem.toString().equals(vanillaId)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isKnifeTool(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        return FarmersDelightPlugin.getInstance().isKnifeItemId(customId);
    }

    private boolean isAxeTool(ItemStack item) {
        return item.getType().name().endsWith("_AXE");
    }

    private boolean isPickaxeTool(ItemStack item) {
        return item.getType().name().endsWith("_PICKAXE");
    }

    private boolean isShovelTool(ItemStack item) {
        return item.getType().name().endsWith("_SHOVEL");
    }

    private boolean isConfiguredToolItem(ItemStack item) {
        return toolItems.stream().anyMatch(toolItem -> ItemUtils.matchesItemId(item, toolItem));
    }
}