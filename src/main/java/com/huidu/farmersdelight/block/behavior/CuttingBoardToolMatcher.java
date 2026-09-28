package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.List;

// Pure tool-recognition logic for the cutting board: decides whether an item can act as a cutting tool
// against the configured tag / item allowlists. Carries the per-instance config lists so the block
// behavior stays focused on interaction flow.
final class CuttingBoardToolMatcher {

    private final FarmersDelightPlugin plugin;
    private final List<Key> toolTags;
    private final List<Key> toolItems;

    CuttingBoardToolMatcher(FarmersDelightPlugin plugin, List<Key> toolTags, List<Key> toolItems) {
        this.plugin = plugin;
        this.toolTags = toolTags;
        this.toolItems = toolItems;
    }

    boolean isTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

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

        for (Key toolTag : toolTags) {
            if (ItemUtils.matchesCustomOrVanillaTag(item, toolTag.toString())) {
                return true;
            }
        }

        return false;
    }

    private boolean isKnifeTool(ItemStack item) {
        return plugin.isKnife(item);
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