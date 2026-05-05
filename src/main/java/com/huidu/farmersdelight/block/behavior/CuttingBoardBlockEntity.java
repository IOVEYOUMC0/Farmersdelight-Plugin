package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class CuttingBoardBlockEntity {

    private static final int NO_DISPLAY = -1;

    private final BlockPosKey posKey;
    private ItemStack storedItem;
    private boolean itemCarved;
    private int displayEntityId = NO_DISPLAY;

    public CuttingBoardBlockEntity(BlockPosKey posKey, World world) {
        this.posKey = posKey;
    }

    public BlockPosKey getPosKey() {
        return posKey;
    }

    public BlockPos getPos() {
        return posKey.toBlockPos();
    }

    public ItemStack getStoredItem() {
        if (storedItem == null) {
            return null;
        }
        return storedItem.clone();
    }

    public boolean hasItem() {
        return storedItem != null && !storedItem.getType().isAir();
    }

    public boolean isItemCarved() {
        return itemCarved;
    }

    public void setItem(ItemStack item, World world, BlockPosKey posKey, BlockFace facing) {
        setItem(item, world, posKey, facing, false);
    }

    public void setItem(ItemStack item, World world, BlockPosKey posKey, BlockFace facing, boolean itemCarved) {
        this.storedItem = cloneOrNull(item);
        this.itemCarved = itemCarved;
        if (this.storedItem != null) {
            this.storedItem.setAmount(Math.max(1, this.storedItem.getAmount()));
        }
        updateDisplayEntity(world, posKey, facing);
    }

    public void setStoredItem(ItemStack item, World world, BlockPosKey posKey, BlockFace facing) {
        this.storedItem = cloneOrNull(item);
        this.itemCarved = false;
        updateDisplayEntity(world, posKey, facing);
    }

    public void clearItem() {
        storedItem = null;
        itemCarved = false;
        removeDisplayEntity();
    }

    private void updateDisplayEntity(World world, BlockPosKey posKey, BlockFace facing) {
        removeDisplayEntity();
        if (storedItem == null || world == null) return;

        ItemDisplayManager visualManager = FarmersDelightPlugin.getInstance().getFakeItemDisplayManager();
        if (visualManager == null || !visualManager.isAvailable()) return;

        boolean isBlockItem = ItemUtils.shouldUseBlockStyleDisplay(storedItem);
        float yOffset = itemCarved ? 0.23f : (isBlockItem ? 0.27f : 0.08f);
        float scale = isBlockItem ? 0.8f : 0.6f;

        float yRotation = getYRotation(facing.getOppositeFace());
        float xRotation = itemCarved ? 0.0f : (isBlockItem ? 0.0f : 90.0f);
        float zRotation = itemCarved ? getCarvedToolZRotation(storedItem) : 0.0f;
        if (itemCarved) {
            yRotation += 180.0f;
        }

        Quaternionf leftRotation = new Quaternionf();
        leftRotation.rotationYXZ(
                (float) Math.toRadians(yRotation),
                (float) Math.toRadians(xRotation),
                (float) Math.toRadians(zRotation)
        );

        Transformation transformation = new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                leftRotation,
                new Vector3f(scale, scale, scale),
                new Quaternionf(0.0f, 0.0f, 0.0f, 1.0f)
        );

        Location location = new Location(world,
                posKey.x() + 0.5,
                posKey.y() + yOffset,
                posKey.z() + 0.5);

        ItemStack visualItem = storedItem.clone();
        visualItem.setAmount(1);

        displayEntityId = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                location,
                visualItem,
                ItemDisplay.ItemDisplayTransform.FIXED,
                transformation
        ));
    }

    public void removeDisplayEntity() {
        if (displayEntityId == NO_DISPLAY) return;
        int entityId = displayEntityId;
        displayEntityId = NO_DISPLAY;

        ItemDisplayManager visualManager = FarmersDelightPlugin.getInstance().getFakeItemDisplayManager();
        if (visualManager != null) {
            visualManager.destroyDisplay(entityId);
        }
    }

    private float getYRotation(BlockFace facing) {
        return switch (facing) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case EAST -> -90.0f;
            default -> 180.0f;
        };
    }

    @SuppressWarnings("unused")
    private boolean isCarvedTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        Material type = item.getType();
        if (type.name().endsWith("_PICKAXE") || type.name().endsWith("_HOE") || type.name().endsWith("_AXE")) {
            return true;
        }
        if (type == Material.TRIDENT) {
            return true;
        }
        if (type.name().contains("KNIFE")) {
            return true;
        }
        String customItemId = ItemUtils.getCustomItemId(item);
        return customItemId != null && customItemId.contains("knife");
    }

    private float getCarvedToolZRotation(ItemStack item) {
        if (item == null) {
            return 180.0f;
        }

        Material type = item.getType();
        if (type == Material.TRIDENT) {
            return 135.0f;
        }
        if (type.name().endsWith("_PICKAXE") || type.name().endsWith("_HOE")) {
            return 225.0f;
        }
        return 180.0f;
    }

    private ItemStack cloneOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }
}
