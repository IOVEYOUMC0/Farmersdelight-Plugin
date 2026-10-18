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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FarmersDelightCommand implements CommandExecutor, TabCompleter {

    private final Map<String, SubCommand> commands = new LinkedHashMap<>();
    private final List<SubCommand> commandList = new ArrayList<>();

    public FarmersDelightCommand(FarmersDelightPlugin plugin) {
        registerCommands(plugin);
    }

    FarmersDelightCommand(SubCommand... commands) {
        for (SubCommand command : commands) {
            register(command);
        }
    }

    private void registerCommands(FarmersDelightPlugin plugin) {
        register(new RecipeSubCommand(plugin));
        register(new ReloadSubCommand(plugin));
        register(new CleanupSubCommand(plugin));
        register(new BuffSubCommand(plugin));
        register(new StatsSubCommand(plugin));
        register(new SubCommand("help", List.of("?"), null, "command.help_help") {
            @Override
            void execute(CommandSender sender, String label, String[] args) {
                sendHelp(sender, label);
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
    public boolean onCommand(@NotNull CommandSender sender, @Nullable Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(BASE_PERMISSION)) {
            sendNoPermission(sender);
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        SubCommand subCommand = commands.get(normalize(args[0]));
        if (subCommand == null) {
            sendHelp(sender, label);
            return true;
        }

        if (!subCommand.canUse(sender)) {
            // A subcommand whose feature is switched off is refused as unknown rather than as a permission
            // problem: the sender may well hold the permission, the feature just is not running.
            if (!subCommand.isAvailable()) {
                sendHelp(sender, label);
            } else {
                sendNoPermission(sender);
            }
            return true;
        }

        subCommand.execute(sender, label, args);
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @Nullable Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(BASE_PERMISSION)) {
            return List.of();
        }

        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : normalize(args[0]);
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

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(MINI.deserialize(I18n.get("command.help_title")));
        for (SubCommand command : commandList) {
            if (!command.canUse(sender)) {
                continue;
            }
            // Show the label the sender actually typed and list the aliases, so a player who knows
            // /fd recipebook can find the entry instead of guessing from the bare subcommand name.
            StringBuilder line = new StringBuilder("<yellow>/").append(label).append(' ').append(command.name())
                    .append("</yellow>");
            if (!command.aliases().isEmpty()) {
                line.append(" <dark_gray>(").append(String.join(", ", command.aliases()))
                        .append(")</dark_gray>");
            }
            line.append(" <gray>-</gray> ").append(resolveHelpText(command));
            sender.sendMessage(MINI.deserialize(line.toString()));
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
