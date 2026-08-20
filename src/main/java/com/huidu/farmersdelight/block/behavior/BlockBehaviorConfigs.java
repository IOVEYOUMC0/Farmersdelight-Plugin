package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.util.Key;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Registry of block-behavior config list providers, keyed by the block id the behavior belongs to.
// Behaviors that expose ConfiguredBlockSet lists register here at load time, so static configs
// (like special recipes in yml) can read them back without holding a world/block instance.
public final class BlockBehaviorConfigs {

    private static final Map<Key, ConfiguredBlockSetProvider> PROVIDERS = new ConcurrentHashMap<>();

    private BlockBehaviorConfigs() {
    }

    public static void register(Key blockId, ConfiguredBlockSetProvider provider) {
        if (blockId != null && provider != null) {
            PROVIDERS.put(blockId, provider);
        }
    }

    public static void unregister(Key blockId) {
        if (blockId != null) {
            PROVIDERS.remove(blockId);
        }
    }

    public static ConfiguredBlockSet get(Key blockId, String key) {
        if (blockId == null || key == null) {
            return null;
        }
        ConfiguredBlockSetProvider provider = PROVIDERS.get(blockId);
        return provider == null ? null : provider.configuredBlockSet(key);
    }

    // Convenience: the list's concrete member ids for the given block behavior config key.
    public static List<String> getMemberIds(Key blockId, String key) {
        ConfiguredBlockSet set = get(blockId, key);
        return set == null ? List.of() : set.memberIds();
    }

    public static void clear() {
        PROVIDERS.clear();
    }
}
