package com.huidu.farmersdelight.api.rule;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable read-only context passed to a {@link RecipeCondition}. Stacks and the station location
 * are defensive copies, so a condition cannot mutate the live cooking pot or cutting board.
 */
@ApiStatus.Experimental
public final class RecipeContext {

    private final Player player;
    private final Location station;
    private final List<ItemStack> inputs;
    private final ItemStack container;
    private final ItemStack tool;
    private final String recipeType;
    private final String recipeId;

    private RecipeContext(@Nullable Player player, @Nullable Location station,
                          List<ItemStack> inputs, @Nullable ItemStack container, @Nullable ItemStack tool,
                          @Nullable String recipeType, @Nullable String recipeId) {
        this.player = player;
        this.station = station == null ? null : station.clone();
        List<ItemStack> copied = new ArrayList<>(inputs == null ? 0 : inputs.size());
        if (inputs != null) {
            for (ItemStack input : inputs) {
                copied.add(input == null ? null : input.clone());
            }
        }
        this.inputs = Collections.unmodifiableList(copied);
        this.container = container == null ? null : container.clone();
        this.tool = tool == null ? null : tool.clone();
        this.recipeType = recipeType;
        this.recipeId = recipeId;
    }

    public static RecipeContext of(@Nullable Player player, @Nullable Location station,
                                   List<ItemStack> inputs, @Nullable ItemStack container) {
        return new RecipeContext(player, station, inputs, container, null, null, null);
    }

    /** Cutting-board context; the tool is the item used for the cut. */
    public static RecipeContext of(@Nullable Player player, @Nullable Location station,
                                   List<ItemStack> inputs, @Nullable ItemStack container,
                                   @Nullable ItemStack tool) {
        return new RecipeContext(player, station, inputs, container, tool, null, null);
    }

    /** Full context with the matched recipe identity, for conditions scoped to specific recipes. */
    public static RecipeContext of(@Nullable Player player, @Nullable Location station,
                                   List<ItemStack> inputs, @Nullable ItemStack container,
                                   @Nullable ItemStack tool, @Nullable String recipeType,
                                   @Nullable String recipeId) {
        return new RecipeContext(player, station, inputs, container, tool, recipeType, recipeId);
    }

    @Nullable
    public Player player() {
        return this.player;
    }

    @Nullable
    public Location station() {
        return this.station == null ? null : this.station.clone();
    }

    /** Immutable list of defensive stack copies; null entries are preserved for empty slots. */
    public List<ItemStack> inputs() {
        List<ItemStack> copied = new ArrayList<>(this.inputs.size());
        for (ItemStack input : this.inputs) {
            copied.add(input == null ? null : input.clone());
        }
        return Collections.unmodifiableList(copied);
    }

    @Nullable
    public ItemStack container() {
        return this.container == null ? null : this.container.clone();
    }

    /** The tool for a cutting-board condition, otherwise null. */
    @Nullable
    public ItemStack tool() {
        return this.tool == null ? null : this.tool.clone();
    }

    /** Matched station/recipe type, e.g. {@code cooking_pot}; null in a generic context. */
    @Nullable
    public String recipeType() {
        return this.recipeType;
    }

    /** Matched recipe id; null in a generic context. */
    @Nullable
    public String recipeId() {
        return this.recipeId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RecipeContext context)) {
            return false;
        }
        return Objects.equals(this.player, context.player)
                && Objects.equals(this.station, context.station)
                && this.inputs.equals(context.inputs)
                && Objects.equals(this.container, context.container)
                && Objects.equals(this.tool, context.tool)
                && Objects.equals(this.recipeType, context.recipeType)
                && Objects.equals(this.recipeId, context.recipeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.player, this.station, this.inputs, this.container, this.tool,
                this.recipeType, this.recipeId);
    }
}
