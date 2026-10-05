package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.ReloadTarget;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.huidu.farmersdelight.command.CommandSupport.normalize;

final class ReloadSubCommand extends SubCommand {

    /** The single separator between the total and the phases, for both languages, so it never varies. */
    private static final String SEPARATOR = ": ";

    // Command tokens for tab-completion and usage, derived from ReloadTarget's primary aliases so a
    // new constant is offered automatically.
    private static final List<String> RELOAD_TARGETS = Arrays.stream(ReloadTarget.values())
            .map(ReloadTarget::eventReason)
            .toList();

    private final FarmersDelightPlugin plugin;
    // One reload at a time plus a short cooldown. A full reload blocks the tick thread for FD and then for
    // every addon on the following tick, so a second request is refused instead of stacking another stall.
    // The cooldown is read per call, so editing config.yml takes effect without a reload of its own.
    private final ReloadBusyGuard busyGuard;
    // Swapped per command so the refusal goes to whoever asked; the guard's own state lives in busyGuard.
    private ReloadBusyGuard.RejectionSink rejectionSink = remainingSeconds -> {
    };

    ReloadSubCommand(FarmersDelightPlugin plugin) {
        super("reload", List.of(), "farmersdelight.admin", "command.help_reload");
        this.plugin = plugin;
        // The plugin may be null: tab-completion is constructible without a server (FarmersDelightCommandTest
        // builds the command tree that way). execute() is the only path that needs the plugin, and it is never
        // reached in that setup, so the guard reads the cooldown defensively.
        this.busyGuard = new ReloadBusyGuard(
                () -> plugin == null ? 0L : plugin.reloadCooldownMillis(),
                System::currentTimeMillis,
                remainingSeconds -> rejectionSink.reject(remainingSeconds));
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
        String token = args.length >= 2 ? normalize(args[1]) : "config";
        ReloadTarget target = ReloadTarget.fromCommand(token);
        if (target == null) {
            sendReloadUsage(sender);
            return;
        }

        // Refuse before doing any work: the caller is told to wait rather than being queued behind a pass that
        // is already occupying the tick thread.
        rejectionSink = ReloadBusyGuard.messageTo(sender);
        if (!busyGuard.begin()) {
            return;
        }
        // One pass, one set of figures: the previous pass's phases are dropped before any work starts.
        plugin.beginReloadPass();
        long startedNanos = System.nanoTime();
        try {
            switch (target) {
                case ALL -> plugin.reloadAll();
                case CONFIG -> plugin.reloadMainConfigOnly();
                case GUI -> plugin.reloadGuiConfig();
                case LANGUAGE -> plugin.reloadLanguageFiles();
                case RECIPES -> plugin.reloadRecipeFiles();
                case ADVANCEMENTS -> plugin.reloadAdvancements();
                case LOOT -> plugin.reloadLootDatapack();
                case ENCHANT -> plugin.refreshEnchantSystem();
                case DAMAGE -> plugin.reloadDamageTypeDatapack();
                case TAGS -> plugin.reloadTags();
            }

            // Notify addons so they reload in sync. "all" already fires this inside reloadAll().
            if (!target.isAll() && target != ReloadTarget.RECIPES) {
                plugin.notifyAddonsOfReload(target.eventReason());
            }

            // The targeted pass's own wall time: a full reload fills the phase figures itself, so this is only
            // the fallback the report needs, and it is measured here where the work actually happens.
            ReloadTiming.recordTargeted(System.nanoTime() - startedNanos);

            sender.sendMessage(I18n.getComponent("general.config_reloaded", placeholders()));
            // CraftEngine's shape: the success line always reports the elapsed time, and a problem is called
            // out only when there is one. A "no issues found" line on every reload is noise.
            Map<String, String> report = placeholders();
            long totalMillis = plugin.reloadTotalMillis();
            report.put("total", String.valueOf(totalMillis));
            // One renderer for every reload path: the total is always printed, the phases only when they belong
            // to this pass, in a fixed order, with one separator and no empty segment. A sharded pass may still
            // be running, so its i/N progress rides along on the same line.
            String phases = ReloadTiming.composeSegments(totalMillis, plugin.reloadPhaseNanos());
            String progress = plugin.recipeRegistrations().progress();
            report.put("split", ReloadTiming.split(phases, SEPARATOR)
                    + (progress.isEmpty() ? "" : (phases.isEmpty() ? "" : " ") + "registered " + progress));
            sender.sendMessage(I18n.getComponent("command.reload_report", report));

            int issues = plugin.reloadIssueCount();
            if (issues > 0) {
                sender.sendMessage(I18n.getComponent("command.reload_report_issues",
                        Map.of("prefix", report.get("prefix"), "issues", String.valueOf(issues))));
            }
        } finally {
            // Released even when a reload throws, so one failure cannot wedge every later reload.
            busyGuard.finish();
        }
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }

        String partial = normalize(args[1]);
        List<String> completions = new ArrayList<>();
        for (String target : RELOAD_TARGETS) {
            if (target.startsWith(partial)) {
                completions.add(target);
            }
        }
        return completions;
    }

    /**
     * The values every command message needs. The brand prefix lives in one lang key
     * (general.prefix) and is injected here, so messages write {prefix} instead of each
     * carrying its own copy of the markup.
     */
    private static Map<String, String> placeholders() {
        Map<String, String> values = new HashMap<>();
        values.put("prefix", I18n.get("general.prefix"));
        return values;
    }

    private void sendReloadUsage(CommandSender sender) {
        sender.sendMessage(I18n.getComponent("command.reload_usage",
                Map.of("targets", String.join("|", RELOAD_TARGETS))));
    }
}
