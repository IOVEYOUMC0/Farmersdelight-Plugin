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
    // Tick stamp of the last cook-progress credit; Long.MIN_VALUE = never credited. A freshly created or
    // re-loaded entry starts here, so time spent unloaded is never credited.
    long lastCookingCreditTick = Long.MIN_VALUE;

    SkilletData(Location location, int defaultCookingTime) {
        this.location = location;
        this.cookingDuration = defaultCookingTime;
    }

    boolean hasItem() {
        return storedItem != null && !storedItem.getType().isAir();
    }

    void advanceCookingProgress(int elapsedTicks, boolean heated, int coolingDecrement) {
        // Recipe durations and cooling rates use game ticks, not manager invocations.
        long change = heated ? elapsedTicks : -(long) elapsedTicks * coolingDecrement;
        cookingProgress = (int) Math.max(0L, Math.min(cookingDuration, cookingProgress + change));
    }

    /**
     * Game ticks elapsed since the previous credit, or the interval on the first one. The placed loop only
     * revisits a skillet every interval ticks while the tick budget covers every skillet, so crediting a
     * fixed interval would cook and cool slower than real time once a server holds more skillets than the
     * budget. Bounded so a long stall cannot credit an implausible batch in one step.
     */
    int elapsedSinceLastCredit(long currentTick, int interval, int cap) {
        long previous = lastCookingCreditTick;
        lastCookingCreditTick = currentTick;
        if (previous == Long.MIN_VALUE) {
            return interval;
        }
        long elapsed = currentTick - previous;
        if (elapsed <= 0L) {
            return interval;
        }
        return (int) Math.min(cap, elapsed);
    }
}
