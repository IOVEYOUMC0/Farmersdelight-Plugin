package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.PerformanceMonitor;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

final class DebugProfileRunner {
    private record Profile(long id, PluginTask task) { }
    private final FarmersDelightPlugin plugin;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();

    DebugProfileRunner(FarmersDelightPlugin plugin) { this.plugin = plugin; }

    void start(Player player, int ticks, String featureId) {
        var manager = plugin.getTickManager();
        var feature = Arrays.stream(PerformanceMonitor.Feature.values())
                .filter(value -> value.id().equals(featureId)).findFirst().orElse(null);
        if (feature == null) {
            // An addon/unknown target has no FD performance feature, so this samples every FD tick feature
            // instead. Say so up front; otherwise the numbers read as if the addon's own work was measured.
            player.sendMessage(I18n.getComponent("command.debug_test_addon_metrics", player,
                    Map.of("target", featureId)));
        }
        long id = manager.startPerformanceProfile(feature);
        if (id == 0L) {
            player.sendMessage(I18n.getComponent("command.stats_profile_busy", player));
            return;
        }
        try {
            PluginTask task = plugin.scheduler().runLater(() -> {
                Profile profile = profiles.get(player.getUniqueId());
                if (profile == null || profile.id() != id || !profiles.remove(player.getUniqueId(), profile)) return;
                var snapshot = manager.finishPerformanceProfile(id);
                if (snapshot == null) return;
                plugin.scheduler().runForEntity(player, () -> {
                    if (!player.isOnline()) return;
                    if (feature == PerformanceMonitor.Feature.SERIALIZATION) {
                        player.sendMessage(I18n.getComponent("command.debug_serialization_finished", player));
                        for (var entry : snapshot.features().entrySet()) {
                            var timing = entry.getValue();
                            player.sendMessage(I18n.getComponent("command.stats_feature_line", player, Map.of(
                                    "feature", I18n.get("command.stats_feature_" + entry.getKey().id(), player),
                                    "calls", String.valueOf(timing.calls()),
                                    "rate", String.format(Locale.ROOT, "%.1f", snapshot.elapsedNanos() <= 0 ? 0
                                            : timing.calls() * 1_000_000_000.0 / snapshot.elapsedNanos()),
                                    "total", millis(timing.totalNanos()), "avg", millis(timing.averageNanos()),
                                    "p95", millis(timing.percentile(95)), "max", millis(timing.maxNanos()))));
                        }
                        return;
                    }
                    player.sendMessage(I18n.getComponent("command.stats_dispatch", player, Map.of(
                            "avg", String.format(Locale.ROOT, "%.3f", snapshot.averageNanos() / 1_000_000.0),
                            "max", String.format(Locale.ROOT, "%.3f", snapshot.maxNanos() / 1_000_000.0),
                            "last", String.format(Locale.ROOT, "%.3f", snapshot.lastNanos() / 1_000_000.0),
                            "processed", String.valueOf(snapshot.lastProcessedBlocks()))));
                });
            }, ticks);
            profiles.put(player.getUniqueId(), new Profile(id, task));
            if (feature == PerformanceMonitor.Feature.SERIALIZATION) {
                player.sendMessage(I18n.getComponent("command.debug_serialization_started", player,
                        Map.of("ticks", String.valueOf(ticks))));
            }
        } catch (RuntimeException failure) {
            manager.finishPerformanceProfile(id);
            throw failure;
        }
    }

    private static String millis(double nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    boolean stop(Player player) {
        Profile profile = profiles.remove(player.getUniqueId());
        if (profile == null) return false;
        profile.task().cancel();
        plugin.getTickManager().finishPerformanceProfile(profile.id());
        return true;
    }
}
