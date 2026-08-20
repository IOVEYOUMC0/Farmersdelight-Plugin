package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.DisplayTransformUtils;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Random;

// Owns the ItemDisplay entity visuals for placed skillets (what each stored food item looks like on the
// pan). Self-contained: only depends on the plugin (display config, item-display manager) and static
// tools, plus package-visible SkilletData fields, so it needs no back-reference to SkilletManager.
final class SkilletVisualManager {

    private final FarmersDelightPlugin plugin;

    SkilletVisualManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    void createVisual(Location location, SkilletData skillet) {
        if (skillet.storedItem == null || skillet.storedItem.getType().isAir() || skillet.location == null) {
            cleanupVisual(skillet);
            return;
        }

        CuttingBoardDisplayConfig displayConfig = plugin.getSkilletDisplayConfig();
        CuttingBoardDisplayConfig.DisplayOverride displayOverride = displayConfig.getOverride(skillet.storedItem);
        ItemStack visualItem = displayConfig.resolveDisplayItem(skillet.storedItem, displayOverride);
        if (visualItem == null || visualItem.getType().isAir()) {
            cleanupVisual(skillet);
            return;
        }
        BlockFace facing = CustomBlockUtils.getFacing(location.getBlock());
        boolean itemChanged = skillet.displayedItem == null || !skillet.displayedItem.isSimilar(visualItem);
        boolean facingChanged = skillet.displayedFacing != facing;
        boolean overrideChanged = !displayOverride.equals(skillet.displayedOverride);
        int displayCount = getModelCount(skillet.storedItem);
        if (itemChanged || facingChanged || overrideChanged || skillet.displayEntityIds.size() != displayCount) {
            cleanupVisual(skillet);
            skillet.displayedItem = visualItem;
            skillet.displayedFacing = facing;
            skillet.displayedOverride = displayOverride;
            skillet.lastVisualStoredItem = skillet.storedItem == null ? null : skillet.storedItem.clone();
        }

        Random random = new Random(getVisualSeed(skillet.storedItem));
        while (skillet.displayEntityIds.size() > displayCount) {
            int entityId = skillet.displayEntityIds.remove(skillet.displayEntityIds.size() - 1);
            ItemDisplayManager visualManager = plugin.getItemDisplayManager();
            if (visualManager != null) {
                visualManager.destroyDisplay(entityId);
            }
        }

        for (int i = skillet.displayEntityIds.size(); i < displayCount; i++) {
            double spread = displayConfig.getItemSpread();
            double offsetX = displayCount == 1 ? 0 : (random.nextDouble() - 0.5D) * spread;
            double offsetZ = displayCount == 1 ? 0 : (random.nextDouble() - 0.5D) * spread;
            double offsetY = (i + 1) * 0.03D;
            Vector3f configuredOffset = displayOverride.offset();
            if (configuredOffset != null) {
                offsetX += configuredOffset.x();
                offsetY += configuredOffset.y();
                offsetZ += configuredOffset.z();
            }

            ItemStack stackForDisplay = visualItem.clone();

            boolean isBlockItem = switch (displayOverride.style()) {
                case BLOCK -> true;
                case ITEM -> false;
                default -> ItemUtils.shouldUseBlockStyleDisplay(stackForDisplay);
            };
            float xRotation = isBlockItem ? 0.0F : -90.0F;
            float yRotation = DisplayTransformUtils.skilletYaw(facing);
            float zRotation = 0.0F;
            if (displayOverride.rotationDegrees() != null) {
                xRotation = displayOverride.rotationDegrees().x();
                yRotation = displayOverride.rotationDegrees().y();
                zRotation = displayOverride.rotationDegrees().z();
            }

            Quaternionf leftRotation = new Quaternionf();
            leftRotation.rotationYXZ(
                    (float) Math.toRadians(yRotation),
                    (float) Math.toRadians(xRotation),
                    (float) Math.toRadians(zRotation)
            );

            Vector3f translation = displayOverride.translation() == null
                    ? new Vector3f(0.0F, 0.0F, 0.0F)
                    : new Vector3f(displayOverride.translation());
            Vector3f scale = displayOverride.scale() == null
                    ? new Vector3f(plugin.getSkilletDisplayScale(), plugin.getSkilletDisplayScale(), plugin.getSkilletDisplayScale())
                    : new Vector3f(displayOverride.scale());
            Transformation transformation = new Transformation(
                    translation,
                    leftRotation,
                    scale,
                    new Quaternionf()
            );

            ItemDisplayManager visualManager = plugin.getItemDisplayManager();
            if (visualManager == null || !visualManager.isAvailable()) {
                continue;
            }

            Location displayLoc = location.clone().add(0.5 + offsetX, offsetY, 0.5 + offsetZ);
            int displayId = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                    displayLoc,
                    stackForDisplay,
                    ItemDisplay.ItemDisplayTransform.FIXED,
                    transformation
            ));
            if (displayId >= 0) {
                skillet.displayEntityIds.add(displayId);
            }
        }
    }

    int getModelCount(ItemStack stack) {
        int amount = stack.getAmount();
        if (amount > 48) {
            return 5;
        }
        if (amount > 32) {
            return 4;
        }
        if (amount > 16) {
            return 3;
        }
        if (amount > 1) {
            return 2;
        }
        return 1;
    }

    long getVisualSeed(ItemStack stack) {
        long seed = stack.getType().ordinal();
        String customItemId = ItemUtils.getCustomItemId(stack);
        if (customItemId != null) {
            seed = 31L * seed + customItemId.hashCode();
        }
        if (stack.hasItemMeta() && stack.getItemMeta().hasCustomModelData()) {
            seed = 31L * seed + stack.getItemMeta().getCustomModelData();
        }
        return seed;
    }

    void cleanupVisual(SkilletData skillet) {
        if (skillet.displayEntityIds.isEmpty()) {
            skillet.displayedItem = null;
            skillet.displayedFacing = null;
            skillet.displayedOverride = null;
            return;
        }

        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager == null) {
            skillet.displayEntityIds.clear();
            skillet.displayedItem = null;
            skillet.displayedFacing = null;
            skillet.displayedOverride = null;
            return;
        }

        for (Integer entityId : new ArrayList<>(skillet.displayEntityIds)) {
            visualManager.destroyDisplay(entityId);
        }
        skillet.displayEntityIds.clear();
        skillet.displayedItem = null;
        skillet.displayedFacing = null;
        skillet.displayedOverride = null;
    }

    void ensureVisualsExist(Location location, SkilletData skillet) {
        if (!skillet.hasItem()) {
            return;
        }

        int expectedCount = getModelCount(skillet.storedItem);
        // Cheap precheck: return early when the display entity count is correct and storedItem is unchanged since
        // the last build, skipping the costly facing/override/resolveDisplayItem resolution below (a cook/insert
        // or config reload each take their own full rebuild path, and block facing never changes after placement).
        if (skillet.displayEntityIds.size() == expectedCount
                && skillet.lastVisualStoredItem != null
                && skillet.lastVisualStoredItem.isSimilar(skillet.storedItem)) {
            return;
        }

        BlockFace facing = CustomBlockUtils.getFacing(location.getBlock());
        CuttingBoardDisplayConfig.DisplayOverride displayOverride = plugin.getSkilletDisplayConfig().getOverride(skillet.storedItem);
        ItemStack visualItem = plugin.getSkilletDisplayConfig().resolveDisplayItem(skillet.storedItem, displayOverride);
        boolean itemChanged = visualItem != null && (skillet.displayedItem == null || !skillet.displayedItem.isSimilar(visualItem));
        if (skillet.displayEntityIds.size() != expectedCount
                || skillet.displayedFacing != facing
                || !displayOverride.equals(skillet.displayedOverride)
                || itemChanged) {
            createVisual(location, skillet);
        }
    }
}