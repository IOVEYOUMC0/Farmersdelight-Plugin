package com.huidu.farmersdelight.api.util;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Tooltip-related helpers for CraftEngine items. Addon-facing (kept stable in the api package), but
 *  signatures use CraftEngine's Item wrapper since callers are already in CE-land. */
public final class TooltipUtils {

    private TooltipUtils() {}

    /** Hides the advanced-tooltip "Durability: X / Y" text line on wrapped by adding
     *  minecraft:damage and minecraft:max_damage to
     *  minecraft:tooltip_display.hidden_components, merging with any pre-existing entries.
     *  Use when an item's damage value encodes something other than tool wear (a fill bar, a serving
     *  count, etc.) so the raw number isn't shown to the player. */
    @SuppressWarnings("unchecked")
    public static void hideDurabilityLine(Item wrapped) {
        List<String> hidden = List.of(
                DataComponentKeys.DAMAGE.asString(),
                DataComponentKeys.MAX_DAMAGE.asString()
        );
        Object existing = wrapped.getComponentAsJava(DataComponentKeys.TOOLTIP_DISPLAY);
        if (existing == null) {
            wrapped.setJavaComponent(DataComponentKeys.TOOLTIP_DISPLAY,
                    Map.of("hidden_components", hidden));
            return;
        }
        if (!(existing instanceof Map<?, ?> rawMap)) return;
        Map<String, Object> data = new HashMap<>((Map<String, Object>) rawMap);
        Object prev = data.get("hidden_components");
        if (prev instanceof List<?> list) {
            List<String> merged = Stream.concat(
                    list.stream().map(Object::toString),
                    hidden.stream()
            ).distinct().toList();
            data.put("hidden_components", merged);
        } else {
            data.put("hidden_components", hidden);
        }
        wrapped.setJavaComponent(DataComponentKeys.TOOLTIP_DISPLAY, data);
    }
}
