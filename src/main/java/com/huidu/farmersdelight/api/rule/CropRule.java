package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;
import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;

/**
 * Read-only crop semantics an addon can register. FarmersDelight consults these rules after its
 * built-in crop behaviors, so addon crops can answer the same harvest/age questions without
 * exposing CraftEngine block-state types to callers.
 *
 * <p>Growth, bonemeal and block behavior remain the addon's/CE's responsibility. Rules must be
 * thread-safe and must not mutate the world.</p>
 */
@ApiStatus.Experimental
public interface CropRule {

    ContentId id();

    boolean matches(Block block);

    boolean isHarvestReady(Block block);

    /** Current growth stage, or -1 when unknown. */
    default int age(Block block) {
        return -1;
    }

    /** Whether harvesting resets the crop to a lower stage. */
    default boolean resetsOnHarvest(Block block) {
        return false;
    }
}
