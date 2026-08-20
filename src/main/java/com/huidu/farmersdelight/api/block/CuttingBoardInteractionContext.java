package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Read/write handle handed to a cutting-board interaction handler when a player right-clicks a FarmersDelight
 * cutting board. Stored-item writes are applied to the board's live block entity (display and container sync
 * included) via setStoredItem.
 *
 * mainHand and offHand are the player's actual stacks: a handler that damages them (e.g. wearing out a tool)
 * changes the real inventory item, exactly like FarmersDelight's own board usage.
 */
public final class CuttingBoardInteractionContext {

    private final Player player;
    private final Block block;
    private final ItemStack mainHand;
    private final ItemStack offHand;
    private final Supplier<ItemStack> storedSupplier;
    private final Consumer<ItemStack> storedWriter;
    private final Supplier<Location> locationSupplier;

    public CuttingBoardInteractionContext(@NotNull Player player, @NotNull Block block,
                                          @Nullable ItemStack mainHand, @Nullable ItemStack offHand,
                                          @NotNull Supplier<ItemStack> storedSupplier,
                                          @NotNull Consumer<ItemStack> storedWriter,
                                          @NotNull Supplier<Location> locationSupplier) {
        this.player = player;
        this.block = block;
        this.mainHand = mainHand;
        this.offHand = offHand;
        this.storedSupplier = storedSupplier;
        this.storedWriter = storedWriter;
        this.locationSupplier = locationSupplier;
    }

    /** The player who right-clicked the board. */
    public Player player() {
        return player;
    }

    /** The board block that was clicked. */
    public Block block() {
        return block;
    }

    /** The player's main-hand stack (the actual inventory item). */
    @Nullable
    public ItemStack mainHand() {
        return mainHand;
    }

    /** The player's off-hand stack (the actual inventory item). */
    @Nullable
    public ItemStack offHand() {
        return offHand;
    }

    /** The item currently stored on the board (a copy), or null when the board is empty. */
    @Nullable
    public ItemStack storedItem() {
        return storedSupplier.get();
    }

    /** Replace the item stored on the board with the given copy (display and container sync included). */
    public void setStoredItem(@Nullable ItemStack item) {
        storedWriter.accept(item);
    }

    /** The board's block location (center of the block). */
    @NotNull
    public Location location() {
        return locationSupplier.get();
    }
}
