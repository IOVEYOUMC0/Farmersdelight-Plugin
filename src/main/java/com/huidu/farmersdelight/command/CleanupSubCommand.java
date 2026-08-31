package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

final class CleanupSubCommand extends SubCommand {

    private final FarmersDelightPlugin plugin;

    CleanupSubCommand(FarmersDelightPlugin plugin) {
        super("cleanup", List.of(), "farmersdelight.admin", "command.help_cleanup");
        this.plugin = plugin;
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
        int displays = 0;
        var displayManager = plugin.getItemDisplayManager();
        if (displayManager != null) {
            // Orphan-only: remove just the proxy displays no live block still owns, keeping legitimate
            // in-use visuals (a stove/skillet/cutting board/cooking pot that's still there). The old
            // full wipe removed valid displays too — and the stove ones did not re-appear.
            java.util.Set<Integer> liveIds = plugin.collectLiveDisplayIds();
            // Let addons mark their own packet-display handles as live (e.g. the items shown on a coaster)
            // so the orphan sweep doesn't wipe them.
            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new com.huidu.farmersdelight.api.event.FarmersDelightCollectLiveDisplaysEvent(liveIds));
            displays = displayManager.cleanupOrphans(liveIds);
        }

        // Same hook style as FarmersDelightReloadEvent — addons (BAC etc.) clean their own orphan
        // state in step and report counts back via event.addRemoved().
        com.huidu.farmersdelight.api.event.FarmersDelightCleanupEvent cleanupEvent =
                new com.huidu.farmersdelight.api.event.FarmersDelightCleanupEvent();
        org.bukkit.Bukkit.getPluginManager().callEvent(cleanupEvent);
        int addon = cleanupEvent.getRemoved();

        sender.sendMessage(I18n.getComponent("command.cleanup_done_displays", Map.of(
                "displays", String.valueOf(displays),
                "addon", String.valueOf(addon),
                "count", String.valueOf(displays + addon))));
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
