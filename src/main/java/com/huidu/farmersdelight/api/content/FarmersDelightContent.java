package com.huidu.farmersdelight.api.content;

import com.huidu.farmersdelight.advancement.ContentRequirement;
import com.huidu.farmersdelight.util.ItemUtils;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

// CraftEngine content existence checks for addons, reusing the same ContentRequirement the
// advancement gate uses so every caller shares one detection implementation. Before CraftEngine has
// finished loading its item registry, absence cannot be trusted, so the checks report "present"
// (same conservative gate as the advancement system); a bare or malformed id also counts as present
// so a lookup quirk can never hide working content.
@ApiStatus.NonExtendable
public final class FarmersDelightContent {

    private FarmersDelightContent() {
    }

    // Whether CraftEngine has finished loading its item registry. CE parses items in a deferred pass
    // after its own onEnable, so this is the only reliable "absence is meaningful" signal.
    public static boolean isCraftEngineReady() {
        try {
            return ItemUtils.isAnyCustomItemLoaded();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isItemPresent(String itemId) {
        if (!isCraftEngineReady()) {
            return true;
        }
        return ContentRequirement.anyItem(itemId).isSatisfied();
    }

    public static boolean isBlockPresent(String blockId) {
        if (!isCraftEngineReady()) {
            return true;
        }
        return ContentRequirement.anyBlock(blockId).isSatisfied();
    }

    public static boolean isItemOrBlockPresent(String id) {
        if (!isCraftEngineReady()) {
            return true;
        }
        return ContentRequirement.anyItemOrBlock(List.of(id)).isSatisfied();
    }
}
