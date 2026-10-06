package com.huidu.farmersdelight.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.pack.allocator.IdAllocator;
import net.momirealms.craftengine.core.plugin.config.Config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class CraftEngineStateUsageMonitor {
    private static final String FARMERS_DELIGHT_NAMESPACE = "farmersdelight:";
    private static final int DEFAULT_LOW_FREE_STATE_WARNING_THRESHOLD = 32;

    // Usage figures last reported at INFO. The plugin refuses to re-enable in the same JVM (see the reload
    // guard in the main class), so a static field tracks exactly one plugin lifecycle.
    private static volatile Usage lastReportedUsage;

    private CraftEngineStateUsageMonitor() {
    }

    public static void logRealStateUsage(FarmersDelightPlugin plugin, String reason) {
        try {
            BukkitBlockManager blockManager = plugin.getCraftEngine().blockManager();
            if (blockManager == null) {
                return;
            }

            Usage usage = inspect(blockManager);
            String message = I18n.formatConsole("craftengine_state.usage",
                    "reason", reason == null || reason.isBlank()
                            ? ""
                            : I18n.formatConsole("craftengine_state.reason_suffix", "reason", reason),
                    "used", usage.used(),
                    "total", usage.total(),
                    "free", usage.free(),
                    "fd_states", usage.farmersDelightStates(),
                    "addon_states", usage.addonStates());

            // Report the figures once per lifecycle, and again only when they actually move. Occupancy is a
            // diagnostic figure rather than an operator fact, so it stays under the startup debug category;
            // only exhaustion and a low free count are warnings.
            if (usage.equals(lastReportedUsage)) {
                plugin.getLogger().fine(message);
            } else {
                lastReportedUsage = usage;
                I18n.logDetailMessage("startup", message);
            }

            if (usage.free() == 0) {
                plugin.getLogger().warning(I18n.formatConsole("craftengine_state.exhausted"));
            } else if (usage.free() <= lowFreeStateWarningThreshold(plugin)) {
                plugin.getLogger().warning(I18n.formatConsole("craftengine_state.low_free", "free", usage.free()));
            }
        } catch (RuntimeException | LinkageError throwable) {
            plugin.getLogger().fine(I18n.formatConsole("craftengine_state.inspect_failed",
                    "error", throwable.getMessage()));
        }
    }

    private static int lowFreeStateWarningThreshold(FarmersDelightPlugin plugin) {
        return Math.max(0, plugin.getConfigInt(DEFAULT_LOW_FREE_STATE_WARNING_THRESHOLD,
                "performance.warnings.craftengine-free-state-threshold"));
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

        // Namespace prefixes of registered addons (e.g. "brewinandchewin:"), counted alongside FD's own.
        Set<String> addonPrefixes = new HashSet<>();
        for (String ns : FarmersDelightApi.get().addonBlockNamespaces()) {
            addonPrefixes.add(ns + ":");
        }

        int used = 0;
        int farmersDelightStates = 0;
        int addonStates = 0;
        for (int i = 0; i < total; i++) {
            ImmutableBlockState state = blockManager.getImmutableBlockStateUnsafe(i + vanillaOffset);
            String owner = null;
            if (state != null && !state.isEmpty()) {
                owner = state.toString();
            } else {
                owner = cachedOwners.get(i);
            }
            if (owner == null) {
                continue;
            }
            used++;
            if (owner.startsWith(FARMERS_DELIGHT_NAMESPACE)) {
                farmersDelightStates++;
            } else if (matchesAddon(owner, addonPrefixes)) {
                addonStates++;
            }
        }

        return new Usage(total, used, Math.max(0, total - used), farmersDelightStates, addonStates);
    }

    private static boolean matchesAddon(String owner, Set<String> addonPrefixes) {
        for (String prefix : addonPrefixes) {
            if (owner.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private record Usage(int total, int used, int free, int farmersDelightStates, int addonStates) {
    }
}
