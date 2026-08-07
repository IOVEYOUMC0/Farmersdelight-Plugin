package com.huidu.farmersdelight.api.util;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class TooltipUtils {

    private TooltipUtils() {}

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
