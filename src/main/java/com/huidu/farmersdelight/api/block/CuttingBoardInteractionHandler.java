package com.huidu.farmersdelight.api.block;

import org.jetbrains.annotations.NotNull;

/**
 * An addon-supplied right-click handler for FarmersDelight cutting boards, registered via
 * FarmersDelightApi.registerCuttingBoardInteractionHandler. Invoked on the world region thread, after the
 * board's permission and protection checks and before FarmersDelight's own placement / cutting logic.
 */
@FunctionalInterface
public interface CuttingBoardInteractionHandler {

    boolean handle(@NotNull CuttingBoardInteractionContext context);
}
