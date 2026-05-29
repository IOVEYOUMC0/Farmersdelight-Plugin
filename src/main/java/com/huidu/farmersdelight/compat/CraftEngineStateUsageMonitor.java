package com.huidu.farmersdelight.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager;
import net.momirealms.craftengine.core.block.BlockManager;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.pack.allocator.IdAllocator;
import net.momirealms.craftengine.core.plugin.config.Config;

import java.util.HashMap;
import java.util.Map;

public final class CraftEngineStateUsageMonitor {
    private static final String FARMERS_DELIGHT_NAMESPACE = "farmersdelight:";
    private static final int DEFAULT_LOW_FREE_STATE_WARNING_THRESHOLD = 32;

    private CraftEngineStateUsageMonitor() {
    }

    public static void logRealStateUsage(FarmersDelightPlugin plugin, String reason) {
        try {
            BukkitBlockManager blockManager = plugin.getCraftEngine().blockManager();
            if (blockManager == null) {
                return;
            }

            Usage usage = inspect(blockManager);
            plugin.getLogger().info(I18n.formatConsole("craftengine_state.usage",
                    "reason", reason == null || reason.isBlank()
                            ? ""
                            : I18n.formatConsole("craftengine_state.reason_suffix", "reason", reason),
                    "used", usage.used(),
                    "total", usage.total(),
                    "free", usage.free(),
                    "fd_states", usage.farmersDelightStates()));

            if (usage.free() == 0) {
                plugin.getLogger().warning(I18n.formatConsole("craftengine_state.exhausted"));
            } else if (usage.free() <= lowFreeStateWarningThreshold(plugin)) {
                plugin.getLogger().warning(I18n.formatConsole("craftengine_state.low_free", "free", usage.free()));
            }
        } catch (Throwable throwable) {
            plugin.getLogger().fine(I18n.formatConsole("craftengine_state.inspect_failed",
                    "error", throwable.getMessage()));
        }
    }

    private static int lowFreeStateWarningThreshold(FarmersDelightPlugin plugin) {
        return Math.max(0, plugin.getConfigInt(DEFAULT_LOW_FREE_STATE_WARNING_THRESHOLD,
                "performance.craftengine-free-state-warning-threshold"));
    }

    private static Usage inspect(BukkitBlockManager blockManager) {
        int total = Config.serverSideBlocks();
        int vanillaOffset = blockManager.vanillaBlockStateCount();
        IdAllocator allocator = blockManager.internalIdAllocator();
        Map<String, Integer> cachedIds = allocator.cachedIdMap();
        Map<Integer, String> cachedOwners = new HashMap<>(cachedIds.size());
        for (Map.Entry<String, Integer> entry : cachedIds.entrySet()) {
            cachedOwners.put(entry.getValue(), entry.getKey());
        }

        int used = 0;
        int farmersDelightStates = 0;
        for (int i = 0; i < total; i++) {
            ImmutableBlockState state = blockManager.getImmutableBlockStateUnsafe(i + vanillaOffset);
            if (state != null && !state.isEmpty()) {
                used++;
                if (state.toString().startsWith(FARMERS_DELIGHT_NAMESPACE)) {
                    farmersDelightStates++;
                }
                continue;
            }

            String cachedOwner = cachedOwners.get(i);
            if (cachedOwner != null) {
                used++;
                if (cachedOwner.startsWith(FARMERS_DELIGHT_NAMESPACE)) {
                    farmersDelightStates++;
                }
            }
        }

        return new Usage(total, used, Math.max(0, total - used), farmersDelightStates);
    }

    private record Usage(int total, int used, int free, int farmersDelightStates) {
    }
}
