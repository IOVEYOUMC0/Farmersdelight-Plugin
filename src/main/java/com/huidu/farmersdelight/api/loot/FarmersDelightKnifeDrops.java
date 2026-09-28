package com.huidu.farmersdelight.api.loot;

import com.huidu.farmersdelight.api.PluginAccess;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.loot.KnifeDropHandler;
import com.huidu.farmersdelight.loot.KnifeDropRule;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@ApiStatus.NonExtendable
public final class FarmersDelightKnifeDrops {

    private FarmersDelightKnifeDrops() {
    }

    public static boolean register(String entityType, String normalItemId, String burningItemId,
                                   double chance, double lootingMultiplier,
                                   List<String> toolItems, List<String> toolTags) {
        KnifeDropHandler handler = handler();
        if (handler == null || entityType == null || entityType.isBlank()) {
            return false;
        }
        String type = entityType.trim().toLowerCase(Locale.ROOT);
        handler.registerExternalDropRule(type, new KnifeDropRule(
                type, normalItemId, burningItemId, chance, lootingMultiplier,
                toolItems == null ? List.of() : List.copyOf(toolItems),
                toolTags == null ? List.of() : List.copyOf(toolTags)));
        return true;
    }

    public static boolean register(String entityType, String normalItemId, String burningItemId,
                                   double chance, double lootingMultiplier) {
        return register(entityType, normalItemId, burningItemId, chance, lootingMultiplier, null, null);
    }

    public static boolean unregister(String entityType) {
        KnifeDropHandler handler = handler();
        return handler != null && entityType != null
                && handler.unregisterExternalDropRule(entityType.trim().toLowerCase(Locale.ROOT));
    }

    public static Set<String> entityTypesWithRules() {
        KnifeDropHandler handler = handler();
        return handler == null ? Set.of() : Set.copyOf(handler.getDropRules().keySet());
    }

    public static boolean hasRule(String entityType) {
        KnifeDropHandler handler = handler();
        return handler != null && entityType != null
                && handler.getDropRules().containsKey(entityType.trim().toLowerCase(Locale.ROOT));
    }

    private static KnifeDropHandler handler() {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        if (plugin == null) {
            return null;
        }
        return plugin.getKnifeDrops();
    }
}
