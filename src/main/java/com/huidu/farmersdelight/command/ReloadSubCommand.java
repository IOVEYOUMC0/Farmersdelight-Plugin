package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.ReloadTarget;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.huidu.farmersdelight.command.CommandSupport.MINI;
import static com.huidu.farmersdelight.command.CommandSupport.normalize;

final class ReloadSubCommand extends SubCommand {

    // Command tokens for tab-completion and usage, derived from ReloadTarget's primary aliases so a
    // new constant is offered automatically.
    private static final List<String> RELOAD_TARGETS = Arrays.stream(ReloadTarget.values())
            .map(ReloadTarget::eventReason)
            .toList();

    private final FarmersDelightPlugin plugin;

    ReloadSubCommand(FarmersDelightPlugin plugin) {
        super("reload", List.of(), "farmersdelight.admin", "command.help_reload");
        this.plugin = plugin;
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
        String token = args.length >= 2 ? normalize(args[1]) : "config";
        ReloadTarget target = ReloadTarget.fromCommand(token);
        if (target == null) {
            sendReloadUsage(sender);
            return;
        }
        switch (target) {
            case ALL -> plugin.reloadAll();
            case CONFIG -> plugin.reloadMainConfigOnly();
            case GUI -> plugin.reloadGuiConfig();
            case LANGUAGE -> plugin.reloadLanguageFiles();
            case RECIPES -> plugin.reloadRecipeFiles();
            case ADVANCEMENTS -> plugin.reloadAdvancements();
        }

        // Notify addons so they reload in sync. "all" already fires this inside reloadAll().
        if (!target.isAll()) {
            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new com.huidu.farmersdelight.api.event.FarmersDelightReloadEvent(target.eventReason()));
        }

        sender.sendMessage(I18n.getComponent("general.config_reloaded"));
        if (sender instanceof Player player) {
            sender.sendMessage(I18n.getComponent("general.hot_reload_warning", player));
        } else {
            sender.sendMessage(I18n.getComponent("general.hot_reload_warning"));
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

    private void sendReloadUsage(CommandSender sender) {
        sender.sendMessage(MINI.deserialize(
                "<yellow>/fd reload <" + String.join("|", RELOAD_TARGETS) + "></yellow>"
        ));
    }
}
