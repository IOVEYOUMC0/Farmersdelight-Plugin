package com.huidu.farmersdelight.api.compat.ce;

import net.momirealms.craftengine.core.util.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * CraftEngine-pinned knife-mineable query. The neutral addon path is
 * {@code FarmersDelightTags} plus {@code KnifeMineableBlocks.isKnifeMineableId(ContentId)}; this
 * facade exists for callers that already hold a CraftEngine {@link Key}.
 */
@ApiStatus.Experimental
public final class KnifeMineableBlocks {

    private KnifeMineableBlocks() {
    }

    public static boolean isKnifeMineable(@Nullable Key blockId) {
        return com.huidu.farmersdelight.api.tag.KnifeMineableBlocks.isKnifeMineable(blockId);
    }
}
