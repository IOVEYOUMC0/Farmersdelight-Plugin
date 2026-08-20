package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.DebugToolExtension;
import com.huidu.farmersdelight.api.util.DebugToolRegistry;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import com.huidu.farmersdelight.visual.ProxyItemDisplayManager;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime statistics: live counters from TickManager, item-display proxies and addon extensions.
 * Lives in the main plugin so it works without a debug build; the heavy debug tools (place/activate/
 * inspect/...) stay behind the -PdebugTools=true build flag.
 */
final class StatsSubCommand extends SubCommand {

    private static final int DEFAULT_PROFILE_TICKS = 200;
    private static final int MAX_PROFILE_TICKS = 12_000;
    private static final List<String> PROFILE_DURATIONS = List.of("100", "200", "600", "1200");

    private final FarmersDelightPlugin plugin;

    StatsSubCommand(FarmersDelightPlugin plugin) {
        super("stats", List.of("perf"), "farmersdelight.admin", "command.help_stats");
        this.plugin = plugin;
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.getComponent("command.player_only"));
            return;
        }
        String action = args.length >= 2 ? normalize(args[1]) : "";
        switch (action) {
            case "profile", "sample" -> profile(player, args);
            case "addon", "addons" -> addon(player, args);
            default -> overview(player);
        }
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return prefixFilter(args[1], List.of("profile", "addon"));
        }
        if (args.length == 3) {
            String action = normalize(args[1]);
            if (isProfile(action)) {
                return prefixFilter(args[2], PROFILE_DURATIONS);
            }
            if (isAddonAction(action)) {
                return prefixFilter(args[2], new ArrayList<>(DebugToolRegistry.registeredNames()));
            }
        }
        return List.of();
    }

    private boolean isProfile(String action) {
        return "profile".equals(action) || "sample".equals(action);
    }

    private boolean isAddonAction(String action) {
        return "addon".equals(action) || "addons".equals(action);
    }

    private void overview(Player player) {
        TickManager tickManager = plugin.getTickManager();
        if (tickManager == null) {
            player.sendMessage(I18n.getComponent("command.stats_tickmanager_unavailable", player));
            return;
        }
        player.sendMessage(I18n.getComponent("command.stats_title", player));
        TickManager.PerformanceSnapshot snapshot = tickManager.getPerformanceSnapshot();
        // Live counters (active/snapshot/pending) are always valid, but the per-tick samples are only
        // collected while a profile is running; without one the avg/max lines read 0 and mislead.
        if (!snapshot.statsEnabled() && snapshot.samples() == 0) {
            player.sendMessage(I18n.getComponent("command.stats_sampling_off", player));
        }
        sendPerformanceSnapshot(player, snapshot, 0);
        appendProxyDisplayStats(player);
        // List each registered addon as a clickable name that drills into /fd stats addon <name>;
        // each addon's own status lines are shown only on demand to keep the overview readable.
        List<DebugToolExtension> extensions = new ArrayList<>(DebugToolRegistry.all());
        player.sendMessage(I18n.getComponent("command.stats_addons_title", player));
        if (extensions.isEmpty()) {
            player.sendMessage(I18n.getComponent("command.stats_addon_empty", player));
        } else {
            for (DebugToolExtension extension : extensions) {
                player.sendMessage(I18n.getComponent("command.stats_addon_link", player,
                        Map.of("name", extension.name())));
            }
        }
    }

    private void addon(Player player, String[] args) {
        if (args.length < 3) {
            sendUsage(player);
            return;
        }
        DebugToolExtension extension = DebugToolRegistry.find(args[2]);
        if (extension == null) {
            player.sendMessage(I18n.getComponent("command.stats_addon_unknown", player,
                    Map.of("name", args[2])));
            return;
        }
        player.sendMessage(I18n.getComponent("command.stats_addon_title", player,
                Map.of("name", extension.name())));
        List<String> lines = extension.status(player);
        if (lines == null || lines.isEmpty()) {
            player.sendMessage(I18n.getComponent("command.stats_addon_empty_state", player,
                    Map.of("name", extension.name())));
            return;
        }
        for (String line : lines) {
            player.sendMessage(I18n.getComponent("command.stats_addon_line", player,
                    Map.of("name", extension.name(), "line", line)));
        }
    }

    private void appendProxyDisplayStats(Player player) {
        ItemDisplayManager manager = plugin.getItemDisplayManager();
        if (!(manager instanceof ProxyItemDisplayManager proxy)) {
            return;
        }
        for (String line : proxy.debugStats()) {
            player.sendMessage(I18n.getComponent("command.stats_proxy_line", player, Map.of("line", line)));
        }
    }

    private void profile(Player player, String[] args) {
        TickManager tickManager = plugin.getTickManager();
        if (tickManager == null) {
            player.sendMessage(I18n.getComponent("command.stats_tickmanager_unavailable", player));
            return;
        }
        Integer requestedTicks = parseOptionalInt(args, 2, DEFAULT_PROFILE_TICKS);
        if (requestedTicks == null) {
            player.sendMessage(I18n.getComponent("command.stats_profile_invalid_ticks", player));
            return;
        }
        int durationTicks = clamp(requestedTicks, 20, MAX_PROFILE_TICKS);
        Location anchor = ManagerSupport.normalize(player.getLocation());
        tickManager.resetPerformanceStats();
        resetProxyDisplayStats();
        player.sendMessage(I18n.getComponent("command.stats_profile_started", player,
                Map.of("ticks", String.valueOf(durationTicks),
                        "pots", String.valueOf(countCookingPots(player.getWorld())))));

        plugin.scheduler().runLaterAt(anchor, () -> {
            if (!player.isOnline()) {
                tickManager.setPerformanceStatsEnabled(false);
                return;
            }
            TickManager.PerformanceSnapshot snapshot = tickManager.getPerformanceSnapshot();
            tickManager.setPerformanceStatsEnabled(false);
            player.sendMessage(I18n.getComponent("command.stats_title", player));
            sendPerformanceSnapshot(player, snapshot, durationTicks);
            appendProxyDisplayStats(player);
        }, durationTicks);
    }

    private void resetProxyDisplayStats() {
        ItemDisplayManager manager = plugin.getItemDisplayManager();
        if (manager instanceof ProxyItemDisplayManager proxy) {
            proxy.resetDebugStats();
        }
    }

    private void sendPerformanceSnapshot(Player player, TickManager.PerformanceSnapshot snapshot, int durationTicks) {
        int worldPots = countCookingPots(player.getWorld());
        player.sendMessage(I18n.getComponent("command.stats_overview", player, Map.of(
                "samples", String.valueOf(snapshot.samples()),
                "duration", String.valueOf(durationTicks),
                "interval", String.valueOf(snapshot.tickInterval()),
                "budget", String.valueOf(snapshot.tickBudget()),
                "sampling", snapshot.statsEnabled() ? "on" : "off")));
        player.sendMessage(I18n.getComponent("command.stats_active", player, Map.of(
                "current", String.valueOf(snapshot.currentActiveBlocks()),
                "snapshot", String.valueOf(snapshot.snapshotActiveBlocks()),
                "last", String.valueOf(snapshot.lastActiveBlocks()),
                "add", String.valueOf(snapshot.pendingAdditions()),
                "rem", String.valueOf(snapshot.pendingRemovals()),
                "pots", String.valueOf(worldPots))));
        player.sendMessage(I18n.getComponent("command.stats_tick", player, Map.of(
                "avg", formatMillis(snapshot.averageNanos()),
                "max", formatMillis(snapshot.maxNanos()),
                "last", formatMillis(snapshot.lastNanos()),
                "processed", String.valueOf(snapshot.lastProcessedBlocks()))));
        appendSparkStyleStats(player, snapshot);
    }

    // Spark-style profile lines appended after the totals: duration percentiles (P50/P95/P99) from the
    // rolling history, plus the most expensive cooking pots by total time to pinpoint lag hotspots.
    private void appendSparkStyleStats(Player player, TickManager.PerformanceSnapshot snapshot) {
        long[] history = snapshot.historyNanos();
        if (history != null && history.length > 0) {
            long[] sorted = history.clone();
            Arrays.sort(sorted);
            player.sendMessage(I18n.getComponent("command.stats_percentile", player, Map.of(
                    "p50", formatMillis(percentile(sorted, 50)),
                    "p95", formatMillis(percentile(sorted, 95)),
                    "p99", formatMillis(percentile(sorted, 99)),
                    "max", formatMillis(sorted[sorted.length - 1]))));
        }
        Map<BlockPosKey, Long> blockNanos = snapshot.blockNanos();
        if (blockNanos == null || blockNanos.isEmpty()) {
            return;
        }
        List<Map.Entry<BlockPosKey, Long>> tops = new ArrayList<>(blockNanos.entrySet());
        tops.sort(Map.Entry.<BlockPosKey, Long>comparingByValue().reversed());
        int shown = Math.min(5, tops.size());
        player.sendMessage(I18n.getComponent("command.stats_hotspot_title", player,
                Map.of("count", String.valueOf(shown))));
        for (int i = 0; i < shown; i++) {
            Map.Entry<BlockPosKey, Long> entry = tops.get(i);
            BlockPosKey key = entry.getKey();
            player.sendMessage(I18n.getComponent("command.stats_hotspot_line", player, Map.of(
                    "rank", String.valueOf(i + 1),
                    "x", String.valueOf(key.x()),
                    "y", String.valueOf(key.y()),
                    "z", String.valueOf(key.z()),
                    "time", formatMillis(entry.getValue()))));
        }
    }

    private static double percentile(long[] sorted, int p) {
        int index = Math.min(sorted.length - 1, (sorted.length * p) / 100);
        return sorted[index];
    }

    private int countCookingPots(org.bukkit.World world) {
        return world == null ? 0 : CookingPotBlockBehavior.getAllBlockEntities(world).size();
    }

    private Integer parseOptionalInt(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatMillis(double nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private List<String> prefixFilter(String partial, List<String> options) {
        String normalized = normalize(partial);
        List<String> completions = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(normalized)) {
                completions.add(option);
            }
        }
        return completions;
    }

    private void sendUsage(Player player) {
        player.sendMessage(I18n.getComponent("command.stats_usage_status", player));
        player.sendMessage(I18n.getComponent("command.stats_usage_addon", player));
        player.sendMessage(I18n.getComponent("command.stats_usage_profile", player,
                Map.of("ticks", String.valueOf(DEFAULT_PROFILE_TICKS))));
    }
}
