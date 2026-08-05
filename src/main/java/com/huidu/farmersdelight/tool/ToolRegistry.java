package com.huidu.farmersdelight.tool;

import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.ItemManager;
import net.momirealms.craftengine.core.item.setting.CustomItemSettingType;
import net.momirealms.craftengine.core.item.setting.ItemSettings;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifier;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifierFactory;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifierType;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.ConfigValue;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.registry.Registries;
import net.momirealms.craftengine.core.registry.WritableRegistry;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.ResourceKey;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers the farmersdelight:tool CE ItemSettings modifier and caches per-item config.
 */
public final class ToolRegistry {

    static final CustomItemSettingType<ToolData> KEY = CustomItemSettingType.newType(
            (data, consumer) -> consumer.accept(new ToolDataProcessor(data.maxDurability(), data.enchantability())),
            null
    );

    private static boolean registered = false;
    private static volatile Map<Key, ToolData> cache = Map.of();

    private ToolRegistry() {}

    @SuppressWarnings("unchecked")
    public static void register() {
        if (registered) return;
        registered = true;

        ItemSettingsModifierType<ItemSettingsModifier> type = new ItemSettingsModifierType<>(
                Key.of("farmersdelight", "tool"),
                (ItemSettingsModifierFactory<ItemSettingsModifier>) (ConfigValue value) ->
                        (ItemSettingsModifier) settings -> {
                            ToolData data = ToolData.fromConfig(value.getAsSection());
                            settings.addCustomData(KEY, data);
                        }
        );

        ((WritableRegistry<ItemSettingsModifierType<? extends ItemSettingsModifier>>)
                BuiltInRegistries.ITEM_SETTINGS_TYPE)
                .register(ResourceKey.create(Registries.ITEM_SETTINGS_TYPE.location(), type.id()), type);
    }

    public static void refresh() {
        CraftEngine ce = CraftEngine.instance();
        if (ce == null) return;

        ItemManager itemManager = ce.itemManager();
        Map<Key, ToolData> newCache = new ConcurrentHashMap<>();

        for (Map.Entry<Key, ItemDefinition> entry : itemManager.loadedItems().entrySet()) {
            Key id = entry.getKey();
            ItemSettings settings = entry.getValue().settings();
            ToolData data = settings.getCustomData(KEY);
            if (data == null) continue;

            if (!data.isValid()) {
                I18n.logWarning("plugin.tool.invalid_durability", "id", id.toString());
                continue;
            }
            if (data.enchantability() <= 0) {
                I18n.logInfo("plugin.tool.zero_enchantability", "id", id.toString());
            }
            newCache.put(id, data);
        }

        cache = Collections.unmodifiableMap(newCache);
        I18n.logInfo("plugin.tool.loaded", "count", newCache.size());
    }

    public static Optional<ToolData> get(Key id) {
        return Optional.ofNullable(cache.get(id));
    }

    public static Optional<ToolData> get(String id) {
        return get(Key.of(id));
    }

    public static Map<Key, ToolData> all() {
        return cache;
    }
}
