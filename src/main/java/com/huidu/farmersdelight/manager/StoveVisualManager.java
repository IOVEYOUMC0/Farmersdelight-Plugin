package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.compat.DisplayTransformUtils;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collection;

// Owns the ItemDisplay entity visuals for each stove slot (what the items grilling on the stove look
// like). Self-contained: depends only on the plugin (stove display config, item-display manager) and
// static tools plus package-visible StoveData fields, so it keeps no back-reference to StoveManager.
// The per-slot world offsets also live here because both display placement and the smoke/fire spawn
// coordinates derive from them; StoveManager asks this class for a rotated offset when it spawns a
// particle.
final class StoveVisualManager {

    private final FarmersDelightPlugin plugin;
    private double[][] slotOffsets = StoveSlotOffsets.defaults();

    StoveVisualManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    void reloadSlotOffsets() {
        this.slotOffsets = StoveSlotOffsets.load(plugin, StoveData.SLOT_COUNT);
    }

    double[] getRotatedSlotOffset(int slot, BlockFace facing) {
        double[] offset = slotOffsets[slot];
        return DisplayTransformUtils.stoveSlotOffset(offset[0], offset[1], offset[2], facing);
    }

    void createVisual(Location location, StoveData stove, int slot, BlockFace facing) {
        removeVisual(location, stove, slot);

        ItemStack item = stove.items[slot];
        if (item == null || item.getType().isAir()) {
            debug("spawn display: skipped empty item for slot=" + slot + ", location=" + ManagerSupport.formatLocation(location));
            return;
        }

        double[] offset = getRotatedSlotOffset(slot, facing);
        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager == null || !visualManager.isAvailable()) {
            debug("spawn display: visual manager unavailable for slot=" + slot + ", item=" + ManagerSupport.formatItem(item)
                    + ", location=" + ManagerSupport.formatLocation(location));
            return;
        }

        Location displayLocation = location.clone().add(0.5 + offset[0], offset[1], 0.5 + offset[2]);
        CuttingBoardDisplayConfig displayConfig = plugin.getStoveDisplayConfig();
        CuttingBoardDisplayConfig.DisplayOverride displayOverride = displayConfig.getOverride(item);
        ItemStack visualItem = displayConfig.resolveDisplayItem(item, displayOverride);
        if (visualItem == null || visualItem.getType().isAir()) {
            debug("spawn display: skipped unresolved display item for slot=" + slot + ", item=" + ManagerSupport.formatItem(item)
                    + ", location=" + ManagerSupport.formatLocation(location));
            return;
        }
        if (displayOverride.offset() != null) {
            double[] configuredOffset = DisplayTransformUtils.stoveSlotOffset(
                    (double) displayOverride.offset().x(),
                    (double) displayOverride.offset().y(),
                    (double) displayOverride.offset().z(),
                    facing
            );
            displayLocation.add(configuredOffset[0], configuredOffset[1], configuredOffset[2]);
        }

        boolean isBlockItem = switch (displayOverride.style()) {
            case BLOCK -> true;
            case ITEM -> false;
            default -> ItemUtils.shouldUseBlockStyleDisplay(visualItem);
        };
        float xRotation = isBlockItem ? 0.0F : -90.0F;
        float yRotation = DisplayTransformUtils.stoveYaw(facing);
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
                ? new Vector3f(plugin.getStoveDisplayScale(), plugin.getStoveDisplayScale(), plugin.getStoveDisplayScale())
                : new Vector3f(displayOverride.scale());
        Transformation transformation = new Transformation(
                translation,
                leftRotation,
                scale,
                new Quaternionf()
        );

        stove.displayEntities[slot] = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                displayLocation,
                visualItem,
                ItemDisplay.ItemDisplayTransform.FIXED,
                transformation
        ));
        debug("spawn display: slot=" + slot + ", entityId=" + stove.displayEntities[slot] + ", item=" + ManagerSupport.formatItem(visualItem)
                + ", location=" + ManagerSupport.formatLocation(location));
    }

    void ensureVisualExists(Location location, StoveData stove, int slot, BlockFace facing) {
        int entityId = stove.displayEntities[slot];
        if (entityId < 0) {
            createVisual(location, stove, slot, facing);
        }
    }

    void removeVisual(Location location, StoveData stove, int slot) {
        int entityId = stove.displayEntities[slot];
        if (entityId < 0) return;
        stove.displayEntities[slot] = -1;

        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager != null) {
            visualManager.destroyDisplay(entityId);
        }
    }

    void cleanupAllVisuals(StoveData stove) {
        for (int i = 0; i < StoveData.SLOT_COUNT; i++) {
            removeVisual(stove.location, stove, i);
        }
    }

    // Rebuild visuals for every active stove after a config reload (slots may be skipped because of CE)
    // so display size/offset/style changes apply without requiring the blocks to be replaced.
    void refreshAll(Collection<StoveData> active) {
        if (active == null || active.isEmpty()) {
            return;
        }
        for (StoveData stove : active) {
            if (stove == null || stove.location == null) {
                continue;
            }
            Location stoveLoc = stove.location;
            plugin.scheduler().runAt(stoveLoc, () -> {
                BlockFace facing = CustomBlockUtils.getFacing(stoveLoc.getBlock()).getOppositeFace();
                for (int slot = 0; slot < StoveData.SLOT_COUNT; slot++) {
                    if (stove.items[slot] != null && !stove.items[slot].getType().isAir()) {
                        createVisual(stoveLoc, stove, slot, facing);
                    }
                }
            });
        }
    }

    private void debug(String message) {
        if (plugin.isDebugEnabled("stove")) {
            plugin.getLogger().info(I18n.formatConsole("debug.stove", "message", message));
        }
    }
}