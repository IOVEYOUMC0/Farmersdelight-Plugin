package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.i18n.I18n;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class FarmersDelightCommand implements CommandExecutor, TabCompleter {

    private final FarmersDelightPlugin plugin;

    public FarmersDelightCommand(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "recipe", "recipes" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(I18n.get("command.player_only"));
                    return true;
                }

                if (!player.hasPermission("farmersdelight.command.recipe")) {
                    player.sendMessage(I18n.get("general.no_permission", player));
                    return true;
                }

                RecipeViewGui gui = new RecipeViewGui(plugin, player);
                if (args.length >= 2) {
                    switch (args[1].toLowerCase()) {
                        case "cooking_pot", "pot", "cookingpot" -> gui.openCookingPotRecipes(player);
                        case "cutting_board", "board", "cuttingboard" -> gui.openCuttingBoardRecipes(player);
                        default -> gui.open(player);
                    }
                } else {
                    gui.open(player);
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("farmersdelight.admin")) {
                    if (sender instanceof Player player) {
                        sender.sendMessage(I18n.get("general.no_permission", player));
                    } else {
                        sender.sendMessage(I18n.get("general.no_permission"));
                    }
                    return true;
                }

                plugin.reloadConfigs();
                plugin.reloadRecipesWhenReady("Refreshing recipes after /fd reload...");
                sender.sendMessage(I18n.get("general.config_reloaded"));
                if (sender instanceof Player player) {
                    sender.sendMessage(I18n.get("general.hot_reload_warning", player));
                } else {
                    sender.sendMessage(I18n.get("general.hot_reload_warning"));
                }
            }
            case "help" -> {
                sendHelp(sender);
            }
            default -> {
                sendHelp(sender);
            }
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        MiniMessage mm = MiniMessage.miniMessage();
        sender.sendMessage(mm.deserialize(I18n.get("command.help_title")));
        sender.sendMessage(mm.deserialize("<yellow>/fd recipe</yellow> <gray>-</gray> " + I18n.get("command.help_recipe")));
        sender.sendMessage(mm.deserialize("<yellow>/fd recipe cooking_pot</yellow> <gray>-</gray> " + I18n.get("command.help_recipe")));
        sender.sendMessage(mm.deserialize("<yellow>/fd recipe cutting_board</yellow> <gray>-</gray> " + I18n.get("command.help_recipe")));
        sender.sendMessage(mm.deserialize("<yellow>/fd reload</yellow> <gray>-</gray> " + I18n.get("command.help_reload")));
        sender.sendMessage(mm.deserialize("<yellow>/fd help</yellow> <gray>-</gray> " + I18n.get("command.help_help")));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();

            if ("recipe".startsWith(partial) && sender.hasPermission("farmersdelight.command.recipe")) {
                completions.add("recipe");
            }
            if ("reload".startsWith(partial) && sender.hasPermission("farmersdelight.admin")) {
                completions.add("reload");
            }
            if ("help".startsWith(partial)) {
                completions.add("help");
            }
        } else if (args.length == 2 && ("recipe".equalsIgnoreCase(args[0]) || "recipes".equalsIgnoreCase(args[0]))
                && sender.hasPermission("farmersdelight.command.recipe")) {
            String partial = args[1].toLowerCase();
            for (String option : List.of("cooking_pot", "cutting_board")) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }
        }

        return completions;
    }
}
