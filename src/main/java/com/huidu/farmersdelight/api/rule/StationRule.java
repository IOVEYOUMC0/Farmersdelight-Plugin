package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;
import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;

/**
 * A working-station rule an addon can register. FarmersDelight uses it for station identification,
 * recipe-book association and optional heat-source querying; the addon keeps ownership of the
 * station's inventory, GUI and execution.
 *
 * <p>Rules are read-only and must be safe to call from the region thread that owns the block.
 * Exceptions are isolated by the registry and treated as "no match".</p>
 */
@ApiStatus.Experimental
public interface StationRule {

    ContentId id();

    boolean matches(Block block);

    /** Recipe-book type id associated with this station, or null when it has no recipe book. */
    default String recipeTypeId() {
        return null;
    }

    /** Opt-in heat-source query for stations that participate in FD cooking. */
    default boolean isHeatSource(Block block) {
        return false;
    }
}
