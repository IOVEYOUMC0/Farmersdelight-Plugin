package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.BuildFlags;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.huidu.farmersdelight.command.CommandSupport.BASE_PERMISSION;
import static com.huidu.farmersdelight.command.CommandSupport.MINI;
import static com.huidu.farmersdelight.command.CommandSupport.normalize;
import static com.huidu.farmersdelight.command.CommandSupport.sendNoPermission;

/**
 * The /fd command. This class only dispatches: it resolves the first argument to a registered
 * SubCommand (by name or alias), applies the shared permission/availability gate, and hands off to
 * the subcommand. Each subcommand carries its own logic in its own class.
 */
public class FarmersDelightCommand implements CommandExecutor, TabCompleter {

    private final Map<String, SubCommand> commands = new LinkedHashMap<>();
    private final List<SubCommand> commandList = new ArrayList<>();

    public FarmersDelightCommand(FarmersDelightPlugin plugin) {
        registerCommands(plugin);
    }

    private void registerCommands(FarmersDelightPlugin plugin) {
        register(new RecipeSubCommand(plugin));
        register(new ReloadSubCommand(plugin));
        register(new CleanupSubCommand(plugin));
        register(new BuffSubCommand(plugin));
        register(new SubCommand("help", List.of("?"), null, "command.help_help") {
            @Override
            void execute(CommandSender sender, String label, String[] args) {
                sendHelp(sender);
            }

            @Override
            List<String> tabComplete(CommandSender sender, String[] args) {
                return List.of();
            }
        });
        if (BuildFlags.DEBUG_TOOLS) {
            DebugToolsSubCommand debugTools = DebugToolsSubCommand.create(plugin);
            if (debugTools != null) {
                register(debugTools);
            }
        }
    }

    private void register(SubCommand command) {
        commandList.add(command);
        commands.put(command.name(), command);
        for (String alias : command.aliases()) {
            commands.put(normalize(alias), command);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(BASE_PERMISSION)) {
            sendNoPermission(sender);
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        SubCommand subCommand = commands.get(normalize(args[0]));
        if (subCommand == null) {
            sendHelp(sender);
            return true;
        }

        if (!subCommand.canUse(sender)) {
            // A subcommand whose feature is switched off is refused as unknown rather than as a permission
            // problem: the sender may well hold the permission, the feature just is not running.
            if (!subCommand.isAvailable()) {
                sendHelp(sender);
            } else {
                sendNoPermission(sender);
            }
            return true;
        }

        subCommand.execute(sender, label, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(BASE_PERMISSION)) {
            return List.of();
        }

        if (args.length == 1) {
            String partial = normalize(args[0]);
            List<String> completions = new ArrayList<>();
            for (SubCommand subCommand : commandList) {
                if (subCommand.canUse(sender) && subCommand.name().startsWith(partial)) {
                    completions.add(subCommand.name());
                }
            }
            return completions;
        }

        SubCommand subCommand = commands.get(normalize(args[0]));
        if (subCommand == null || !subCommand.canUse(sender)) {
            return List.of();
        }
        return subCommand.tabComplete(sender, args);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MINI.deserialize(I18n.get("command.help_title")));
        for (SubCommand command : commandList) {
            if (!command.canUse(sender)) {
                continue;
            }
            sender.sendMessage(MINI.deserialize(
                    "<yellow>/fd " + command.name() + "</yellow> <gray>-</gray> " + resolveHelpText(command)
            ));
        }
    }

    private String resolveHelpText(SubCommand command) {
        String helpKey = command.helpKey();
        if (helpKey.startsWith("literal:")) {
            return helpKey.substring("literal:".length());
        }
        return I18n.get(helpKey);
    }
}
