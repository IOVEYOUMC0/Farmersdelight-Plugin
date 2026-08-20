package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Per-skillet in-memory state shared by the tick / interaction / save paths. A SkilletData also serves as
// the per-block monitor: concurrent empty-hand takes, stacks, and a racing break all lock on it.
public final class SkilletData {

    final Location location;
    ItemStack storedItem;
    ItemStack skilletStack;
    ItemStack displayedItem;
    BlockFace displayedFacing;
    CuttingBoardDisplayConfig.DisplayOverride displayedOverride;
    // Snapshot of storedItem at last visual build; cheap precheck for ensureVisualsExist to avoid
    // repeating costly facing/override/resolveDisplayItem CraftEngine lookups each tick.
    ItemStack lastVisualStoredItem;
    int cookingProgress = 0;
    int cookingDuration;
    CookingRecipe<?> currentRecipe;
    int fireAspectLevel = 0;
    UUID ownerId;
    String ownerName;
    final List<Integer> displayEntityIds = new ArrayList<>();
    Boolean lastHeatState;
    // Tick stamp of the last actual heat-source probe; Long.MIN_VALUE = never probed. Combined with
    // lastHeatState this forms a short-TTL cache so hasHeatSource skips its two getBlockAt +
    // HeatSourceConfig queries in the steady state (heat source changes are block-event driven).
    long heatSourceCheckedTick = Long.MIN_VALUE;

    SkilletData(Location location, int defaultCookingTime) {
        this.location = location;
        this.cookingDuration = defaultCookingTime;
    }

    boolean hasItem() {
        return storedItem != null && !storedItem.getType().isAir();
    }
}