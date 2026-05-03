package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.BlockPosKey;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

public class CuttingBoardBlockEntity {

    private final BlockPosKey posKey;
    private final UUID worldId;
    private ItemStack storedItem;
    private boolean itemCarved;
    private UUID displayEntityId;

    public CuttingBoardBlockEntity(BlockPosKey posKey, World world) {
        this.posKey = posKey;
        this.worldId = world == null ? null : world.getUID();
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
            this.storedItem.setAmount(1);
        }
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

        boolean isBlockItem = ItemUtils.shouldUseBlockStyleDisplay(storedItem);
        boolean isCarvedTool = itemCarved && isCarvedTool(storedItem);

        float yOffset = isCarvedTool ? 0.23f : (isBlockItem ? 0.27f : 0.08f);
        float scale = isBlockItem ? 0.8f : 0.6f;

        Location location = new Location(world,
                posKey.x() + 0.5,
                posKey.y() + yOffset,
                posKey.z() + 0.5);

        ItemDisplay entity = world.spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(storedItem);
            display.setPersistent(true);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.addScoreboardTag("farmersdelight_cutting_board_visual");
            display.addScoreboardTag("farmersdelight_visual");

            float yRotation = getYRotation(facing.getOppositeFace());
            float xRotation = isCarvedTool ? 0.0f : (isBlockItem ? 0.0f : 90.0f);
            float zRotation = isCarvedTool ? getCarvedToolZRotation(storedItem) : 0.0f;
            if (isCarvedTool) {
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

            display.setTransformation(transformation);
        });

        displayEntityId = entity.getUniqueId();
    }

    private float getYRotation(BlockFace facing) {
        return switch (facing) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case EAST -> -90.0f;
            default -> 180.0f;
        };
    }

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

    public void removeDisplayEntity() {
        if (displayEntityId == null) return;
        UUID entityId = displayEntityId;
        displayEntityId = null;

        World world = null;
        if (worldId != null) {
            world = Bukkit.getWorld(worldId);
        }
        if (world != null) {
            try {
                var entity = world.getEntity(entityId);
                if (entity instanceof ItemDisplay display && display.isValid()) {
                    display.remove();
                    return;
                }
            } catch (Exception e) {
                // The display entity may already be gone.
            }
        }

        for (World w : Bukkit.getWorlds()) {
            try {
                var entity = w.getEntity(entityId);
                if (entity instanceof ItemDisplay display && display.isValid()) {
                    display.remove();
                    return;
                }
            } catch (Exception e) {
                // The display entity may already be gone.
            }
        }
    }

    private ItemStack cloneOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }
}
