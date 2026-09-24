package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.util.compat.DisplayTransformUtils;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

public class CuttingBoardBlockEntity {

    private static final int NO_DISPLAY = -1;

    private final BlockPosKey posKey;
    private volatile World world;
    private ItemStack storedItem;
    private boolean itemCarved;
    private final List<Integer> displayEntityIds = new ArrayList<>();
    private ItemStack displayedBaseItem;
    private boolean displayedCarved;
    private BlockFace displayedFacing;
    private int displayedCount;
    private CuttingBoardDisplayConfig.DisplayOverride displayedOverride;

    void collectDisplayIds(Set<Integer> out) {
        out.addAll(displayEntityIds);
    }

    public CuttingBoardBlockEntity(BlockPosKey posKey, World world) {
        this.posKey = posKey;
        this.world = world;
    }

    public void setWorld(World world) {
        this.world = world;
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

    public void setItem(ItemStack item, World world, BlockPosKey posKey, BlockFace facing, boolean itemCarved) {
        setStoredItem(item, world, posKey, facing, itemCarved);
    }

    public void setStoredItem(ItemStack item, World world, BlockPosKey posKey, BlockFace facing) {
        setStoredItem(item, world, posKey, facing, CuttingBoardStoredItemPose.FLAT);
    }

    public void setStoredItem(ItemStack item, World world, BlockPosKey posKey, BlockFace facing, boolean itemCarved) {
        setStoredItem(item, world, posKey, facing, CuttingBoardStoredItemPose.fromCarved(itemCarved));
    }

    private void setStoredItem(ItemStack item, World world, BlockPosKey posKey, BlockFace facing,
                               CuttingBoardStoredItemPose pose) {
        this.world = world;
        this.storedItem = cloneOrNull(item);
        this.itemCarved = pose.itemCarved();
        if (this.storedItem != null) {
            this.storedItem.setAmount(Math.max(1, this.storedItem.getAmount()));
        }
        syncWorldlyContainer();
        updateDisplayEntity(world, posKey, facing);
    }

    public void clearItem() {
        storedItem = null;
        itemCarved = false;
        syncWorldlyContainer();
        removeDisplayEntity();
    }

    public void refreshDisplayEntity(World world, BlockFace facing) {
        this.world = world;
        updateDisplayEntity(world, posKey, facing);
    }

    private void updateDisplayEntity(World world, BlockPosKey posKey, BlockFace facing) {
        try {
            updateDisplayEntityInternal(world, posKey, facing);
        } catch (Throwable t) {
            // The item is already stored by the time the display is (re)built; a cosmetic display failure must
            // not propagate into setStoredItem, or the cutting board's place-then-consume flow would leave the
            // player's hand item unconsumed (a duplication) with no display.
            FarmersDelightPlugin.getInstance().getLogger().log(Level.WARNING,
                    "Cutting board display update failed at " + posKey + " (item still stored)", t);
        }
    }

    private void updateDisplayEntityInternal(World world, BlockPosKey posKey, BlockFace facing) {
        if (storedItem == null || world == null) {
            removeDisplayEntity();
            return;
        }

        ItemDisplayManager visualManager = FarmersDelightPlugin.getInstance().getItemDisplayManager();
        if (visualManager == null || !visualManager.isAvailable()) return;

        CuttingBoardDisplayConfig displayConfig = FarmersDelightPlugin.getInstance().getCuttingBoardDisplayConfig();
        CuttingBoardDisplayConfig.DisplayOverride displayOverride = displayConfig.getOverride(storedItem);
        ItemStack visualItem = displayConfig.resolveDisplayItem(storedItem, displayOverride);
        if (visualItem == null || visualItem.getType().isAir()) {
            removeDisplayEntity();
            return;
        }
        int desiredCount = getDisplayCount(storedItem);
        boolean displayChanged = displayedBaseItem == null
                || !displayedBaseItem.isSimilar(visualItem)
                || displayedCarved != itemCarved
                || displayedFacing == null
                || !displayedFacing.equals(facing)
                || displayedCount != desiredCount
                || !displayOverride.equals(displayedOverride);
        if (!displayChanged && displayEntityIds.size() == desiredCount) {
            return;
        }

        adjustDisplayCount(world, posKey, facing, visualManager, desiredCount, visualItem, displayOverride);
        displayedBaseItem = visualItem;
        displayedCarved = itemCarved;
        displayedFacing = facing;
        displayedCount = desiredCount;
        displayedOverride = displayOverride;
    }

    public void removeDisplayEntity() {
        if (!displayEntityIds.isEmpty()) {
            ItemDisplayManager visualManager = FarmersDelightPlugin.getInstance().getItemDisplayManager();
            if (visualManager != null) {
                for (Integer entityId : displayEntityIds) {
                    if (entityId != null && entityId != NO_DISPLAY) {
                        visualManager.destroyDisplay(entityId);
                    }
                }
            }
            displayEntityIds.clear();
        }
        displayedBaseItem = null;
        displayedCarved = false;
        displayedFacing = null;
        displayedCount = 0;
        displayedOverride = null;
    }

    private void adjustDisplayCount(World world, BlockPosKey posKey, BlockFace facing, ItemDisplayManager visualManager,
                                    int desiredCount, ItemStack visualItem,
                                    CuttingBoardDisplayConfig.DisplayOverride displayOverride) {
        while (displayEntityIds.size() > desiredCount) {
            int entityId = displayEntityIds.removeLast();
            if (entityId != NO_DISPLAY) {
                visualManager.destroyDisplay(entityId);
            }
        }

        for (int index = 0; index < displayEntityIds.size(); index++) {
            int entityId = displayEntityIds.get(index);
            if (entityId == NO_DISPLAY) {
                continue;
            }
            ItemDisplayManager.DisplaySpec spec = createDisplaySpec(world, posKey, facing, visualItem, index, desiredCount, displayOverride);
            if (!visualManager.updateDisplay(entityId, spec)) {
                int replacementId = visualManager.createDisplay(spec);
                if (replacementId != NO_DISPLAY) {
                    displayEntityIds.set(index, replacementId);
                } else {
                    displayEntityIds.remove(index);
                    index--;
                }
            }
        }

        while (displayEntityIds.size() < desiredCount) {
            int index = displayEntityIds.size();
            int entityId = createDisplayEntity(world, posKey, facing, visualManager, visualItem, index, desiredCount, displayOverride);
            if (entityId != NO_DISPLAY) {
                displayEntityIds.add(entityId);
            } else {
                break;
            }
        }
    }

    private int createDisplayEntity(World world, BlockPosKey posKey, BlockFace facing, ItemDisplayManager visualManager,
                                    ItemStack visualItem, int index, int totalCount,
                                    CuttingBoardDisplayConfig.DisplayOverride displayOverride) {
        return visualManager.createDisplay(createDisplaySpec(world, posKey, facing, visualItem, index, totalCount, displayOverride));
    }

    private ItemDisplayManager.DisplaySpec createDisplaySpec(World world, BlockPosKey posKey, BlockFace facing,
                                                             ItemStack visualItem, int index, int totalCount,
                                                             CuttingBoardDisplayConfig.DisplayOverride displayOverride) {
        boolean isBlockItem = switch (displayOverride.style()) {
            case BLOCK -> true;
            case ITEM -> false;
            default -> ItemUtils.shouldUseBlockStyleDisplay(visualItem);
        };
        float baseYOffset = itemCarved ? 0.23f : (isBlockItem ? 0.27f : 0.08f);
        float yOffset = baseYOffset + 0.03f * (index + 1);
        float scale = isBlockItem ? 0.8f : 0.6f;

        float yRotation = DisplayTransformUtils.cuttingBoardYaw(facing);
        float xRotation = itemCarved ? 0.0f : (isBlockItem ? 0.0f : 90.0f);
        float zRotation = itemCarved ? getCarvedToolZRotation(visualItem) : 0.0f;
        if (itemCarved) {
            yRotation += 180.0f;
        }
        if (displayOverride.rotationDegrees() != null) {
            // The configured rotation is the item's LOCAL pose; the board's facing yaw still applies on
            // top so the item turns with the board (X/Z are pitch/roll — facing-independent — so they
            // replace outright, but Y composes because yRotation already contains the facing yaw and carved flip).
            xRotation = displayOverride.rotationDegrees().x();
            yRotation += displayOverride.rotationDegrees().y();
            zRotation = displayOverride.rotationDegrees().z();
        }

        Quaternionf leftRotation = new Quaternionf();
        leftRotation.rotationYXZ(
                (float) Math.toRadians(yRotation),
                (float) Math.toRadians(xRotation),
                (float) Math.toRadians(zRotation)
        );

        Vector3f scaleVector = displayOverride.scale() != null
                ? new Vector3f(displayOverride.scale())
                : new Vector3f(scale, scale, scale);
        Vector3f translation = displayOverride.translation() != null
                ? new Vector3f(displayOverride.translation())
                : new Vector3f(0.0f, 0.0f, 0.0f);
        Transformation transformation = new Transformation(
                translation,
                leftRotation,
                scaleVector,
                new Quaternionf(0.0f, 0.0f, 0.0f, 1.0f)
        );

        Random random = new Random(getDisplaySeed(visualItem) + (index * 341873128712L));
        float spread = FarmersDelightPlugin.getInstance().getCuttingBoardDisplayConfig().getItemSpread();
        float xOffset = totalCount == 1 ? 0.0f : (random.nextFloat() * 2.0f - 1.0f) * spread * 0.5f;
        float zOffset = totalCount == 1 ? 0.0f : (random.nextFloat() * 2.0f - 1.0f) * spread * 0.5f;
        if (displayOverride.offset() != null) {
            xOffset += displayOverride.offset().x();
            yOffset += displayOverride.offset().y();
            zOffset += displayOverride.offset().z();
        }

        Location location = new Location(world,
                posKey.x() + 0.5 + xOffset,
                posKey.y() + yOffset,
                posKey.z() + 0.5 + zOffset);

        return new ItemDisplayManager.DisplaySpec(
                location,
                visualItem,
                ItemDisplay.ItemDisplayTransform.FIXED,
                transformation
        );
    }

    private int getDisplayCount(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 0;
        }
        int count = Math.max(1, stack.getAmount());
        if (count <= 1) {
            return 1;
        }
        int maxStackSize = Math.max(1, stack.getMaxStackSize());
        return 1 + (int) Math.ceil(((float) count / maxStackSize) * 4.0f);
    }

    private long getDisplaySeed(ItemStack item) {
        long seed = 31L * posKey.x() + posKey.y();
        seed = 31L * seed + posKey.z();
        seed = 31L * seed + (item == null ? 0 : item.getType().ordinal());
        seed = 31L * seed + (item != null && item.hasItemMeta() ? item.getItemMeta().hashCode() : 0);
        return seed;
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
        return ItemUtils.cloneOrNull(item);
    }

    private void syncWorldlyContainer() {
        World currentWorld = world;
        if (currentWorld != null) {
            CuttingBoardBlockBehavior.markBlockEntityDirty(currentWorld, posKey);
        }
    }
}
