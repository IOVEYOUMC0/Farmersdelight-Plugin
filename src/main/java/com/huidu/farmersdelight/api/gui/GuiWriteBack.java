package com.huidu.farmersdelight.api.gui;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The guard that decides whether a viewer's edit may be committed back into the store.
 *
 *
 * Every container GUI here hands each viewer its own snapshot inventory, and vanilla applies a click a tick
 * later, so a write-back carries a value that may have been painted before the ticker ran. The crab trap's
 * ticker eats the bait cell and drops catches into the output cells while a GUI is open; committing that
 * snapshot then hands back bait the trap already spent, or erases a catch that arrived meanwhile. Either way
 * the item exists twice or vanishes, so the rule is: commit only while the store still holds exactly what
 * the cell was painted from.
 *
 *
 * Null and air are the same thing here (an empty cell). The comparison is plain data: it reads no world,
 * clones nothing and runs on any thread.
 */
public final class GuiWriteBack {

    private GuiWriteBack() {
    }

    /**
     * True when the cell may be committed: both sides empty, or the same item in the same amount. False means
     * the caller must repaint the cell from the store instead — keeping the GUI value would duplicate or lose
     * an item.
     */
    public static boolean mayCommit(@Nullable ItemStack stored, @Nullable ItemStack paintedBaseline) {
        boolean storedEmpty = isEmpty(stored);
        boolean paintedEmpty = isEmpty(paintedBaseline);
        boolean similar = !storedEmpty && !paintedEmpty && stored.isSimilar(paintedBaseline);
        int storedAmount = storedEmpty ? 0 : stored.getAmount();
        int paintedAmount = paintedEmpty ? 0 : paintedBaseline.getAmount();
        return mayCommit(storedEmpty, paintedEmpty, similar, storedAmount, paintedAmount);
    }

    /**
     * The comparison behind mayCommit(ItemStack, ItemStack), as plain values so the rule can be
     * asserted without a running server: both empty, or similar with equal amounts.
     */
    public static boolean mayCommit(boolean storedEmpty, boolean paintedEmpty, boolean similar,
                                    int storedAmount, int paintedAmount) {
        if (storedEmpty || paintedEmpty) {
            return storedEmpty && paintedEmpty;
        }
        return similar && storedAmount == paintedAmount;
    }

    private static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }
}
