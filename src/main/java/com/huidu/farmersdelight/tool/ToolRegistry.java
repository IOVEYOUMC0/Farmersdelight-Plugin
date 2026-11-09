package com.huidu.farmersdelight.tool;

import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.ItemManager;
import net.momirealms.craftengine.core.item.setting.CustomItemSettingType;
import net.momirealms.craftengine.core.item.setting.ItemSettings;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifier;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifierType;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.registry.Registries;
import net.momirealms.craftengine.core.registry.WritableRegistry;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.ResourceKey;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ToolRegistry {

    static final CustomItemSettingType<ToolData> KEY = CustomItemSettingType.newType(
            (data, consumer) -> consumer.accept(new ToolDataProcessor(data.maxDurability(), data.enchantability())),
            null
    );

    // farmersdelight:durable — the durability half of the sword setting with none of the combat: it stamps the
    // same vanilla max_damage / damage / max_stack_size:1 components (so the item is single-stack, damageable,
    // and shows the vanilla durability bar) but is stored under a DISTINCT key that the combat listener and the
    // knife-enchant filter never consult, so a durable item is not a weapon. Wear must be driven in code via
    // FarmersDelightItems.damage(...). Not added to the refresh() cache — nothing runtime reads it.
    static final CustomItemSettingType<ToolData> DURABLE_KEY = CustomItemSettingType.newType(
            (data, consumer) -> consumer.accept(new ToolDataProcessor(data.maxDurability(), data.enchantability())),
            null
    );

    private static boolean registered = false;
    private static volatile Map<Key, ToolData> cache = Map.of();
    // Durable-only items (farmersdelight:durable): tracked by id only, purely so the enchant filter can
    // apply their minimal whitelist. They never enter the weapon cache above.
    private static volatile Set<Key> durableIds = Set.of();

    private ToolRegistry() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }

        ItemSettingsModifierType<ItemSettingsModifier> type = new ItemSettingsModifierType<>(
                Key.of("farmersdelight", "sword"),
                value -> settings -> {
                            ToolData data = ToolData.fromConfig(value.getAsSection());
                            settings.addCustomData(KEY, data);
                        }
        );

        registerType(type);

        ItemSettingsModifierType<ItemSettingsModifier> durableType = new ItemSettingsModifierType<>(
                Key.of("farmersdelight", "durable"),
                value -> settings -> {
                            ToolData data = ToolData.fromConfig(value.getAsSection());
                            settings.addCustomData(DURABLE_KEY, data);
                        }
        );

        registerType(durableType);
        // Only now: a failure above (registry not ready, key already taken) used to leave the flag set, and the
        // two settings types were then never registered again for the rest of the run, silently dropping every
        // farmersdelight:sword/durable entry to "no durability, not enchantable".
        registered = true;
    }

    /**
     * Registers one settings type unless the registry already has that id.
     *
     * <p>An interrupted earlier attempt (one type in, one type out) must be resumable: CraftEngine's registry
     * throws on a duplicate key, so a plain retry would fail forever on the half that did register.
     */
    @SuppressWarnings("unchecked")
    private static void registerType(ItemSettingsModifierType<? extends ItemSettingsModifier> type) {
        WritableRegistry<ItemSettingsModifierType<? extends ItemSettingsModifier>> registry =
                (WritableRegistry<ItemSettingsModifierType<? extends ItemSettingsModifier>>)
                        BuiltInRegistries.ITEM_SETTINGS_TYPE;
        ResourceKey<ItemSettingsModifierType<? extends ItemSettingsModifier>> key =
                ResourceKey.create(Registries.ITEM_SETTINGS_TYPE.location(), type.id());
        if (registry.get(key).isEmpty()) {
            registry.register(key, type);
        }
    }

    public static void refresh() {
        CraftEngine ce = CraftEngine.instance();
        if (ce == null) return;

        ItemManager itemManager = ce.itemManager();
        Map<Key, ToolData> newCache = new ConcurrentHashMap<>();
        Set<Key> newDurableIds = ConcurrentHashMap.newKeySet();

        for (Map.Entry<Key, ItemDefinition> entry : itemManager.loadedItems().entrySet()) {
            Key id = entry.getKey();
            ItemSettings settings = entry.getValue().settings();

            ToolData durableData = settings.getCustomData(DURABLE_KEY);
            if (durableData != null && durableData.isValid()) {
                newDurableIds.add(id);
            }

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
        durableIds = Collections.unmodifiableSet(newDurableIds);
        // The count is carried by the startup summary line; this stays startup detail so one boot never prints
        // the same figure twice with two different values (an empty pass and the filled one).
        I18n.logDetail("startup", "plugin.tool.loaded", "count", newCache.size());
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

    public static boolean isDurable(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return durableIds.contains(Key.of(id));
    }
}
