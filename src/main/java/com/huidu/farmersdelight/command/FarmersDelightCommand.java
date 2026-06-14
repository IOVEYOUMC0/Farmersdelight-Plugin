package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.BuildFlags;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.i18n.I18n;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FarmersDelightCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String BASE_PERMISSION = "farmersdelight.command";
    private static final List<String> RELOAD_TARGETS = List.of(
            "all",
            "config",
            "gui",
            "lang",
            "recipes",
            "advancements"
    );
    private static final String DEBUG_TOOLS_CLASS = "com.huidu.farmersdelight.debug.DebugToolsCommand";

    private final FarmersDelightPlugin plugin;
    private final Map<String, SubCommand> commands = new LinkedHashMap<>();
    private final List<SubCommand> commandList = new ArrayList<>();
    private Object debugToolsCommand;

    public FarmersDelightCommand(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        registerCommands();
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
            sendNoPermission(sender);
            return true;
        }

        subCommand.executor().execute(sender, label, args);
        return true;
    }

    private void registerCommands() {
        register(new SubCommand(
                "recipe",
                List.of("recipes"),
                "farmersdelight.command.recipe",
                "command.help_recipe",
                this::executeRecipe,
                this::completeRecipe
        ));
        register(new SubCommand(
                "reload",
                List.of(),
                "farmersdelight.admin",
                "command.help_reload",
                this::executeReload,
                this::completeReload
        ));
        register(new SubCommand(
                "cleanup",
                List.of(),
                "farmersdelight.admin",
                "command.help_cleanup",
                this::executeCleanup,
                (sender, args) -> List.of()
        ));
        register(new SubCommand(
                "help",
                List.of("?"),
                null,
                "command.help_help",
                (sender, label, args) -> sendHelp(sender),
                (sender, args) -> List.of()
        ));
        if (BuildFlags.DEBUG_TOOLS) {
            debugToolsCommand = createDebugToolsCommand();
            if (debugToolsCommand != null) {
                register(new SubCommand(
                        "debugtools",
                        List.of("debug", "perf"),
                        "farmersdelight.admin",
                        "literal:debug performance tools",
                        this::executeDebugTools,
                        this::completeDebugTools
                ));
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

    private void executeRecipe(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.getComponent("command.player_only"));
            return;
        }

        if (args.length >= 2 && normalize(args[1]).equals("edit")) {
            executeRecipeEdit(player, args);
            return;
        }

        RecipeViewGui gui = new RecipeViewGui(plugin, player);
        if (args.length < 2) {
            gui.open(player);
            return;
        }

        switch (normalize(args[1])) {
            case "cooking_pot", "pot", "cookingpot" -> gui.openCookingPotRecipes(player);
            case "cutting_board", "board", "cuttingboard" -> gui.openCuttingBoardRecipes(player);
            default -> gui.open(player);
        }
    }

    private void executeRecipeEdit(Player player, String[] args) {
        if (!player.hasPermission("farmersdelight.admin")) {
            sendNoPermission(player);
            return;
        }
        if (args.length < 3) {
            player.sendMessage(I18n.getComponent("gui.editor.usage", player));
            return;
        }
        String type = normalize(args[2]);
        if (args.length < 4) {
            RecipeViewGui gui = new RecipeViewGui(plugin, player);
            switch (type) {
                case "pot", "cooking_pot", "cookingpot" -> gui.openCookingPotRecipesForEdit(player);
                case "board", "cutting_board", "cuttingboard" -> gui.openCuttingBoardRecipesForEdit(player);
                default -> player.sendMessage(I18n.getComponent("gui.editor.usage", player));
            }
            return;
        }
        String id = normalize(args[3]);
        if (!id.matches("[a-z0-9_]+")) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.invalid_id", player));
            return;
        }
        switch (type) {
            case "pot", "cooking_pot", "cookingpot" -> {
                String group = args.length >= 5 ? normalize(args[4]) : null;
                com.huidu.farmersdelight.gui.RecipeViewGuiConfig.BaseConfig editorConfig =
                        plugin.getRecipeEditorGuiConfig().getCookingPotConfig(group);
                if (editorConfig == null) {
                    player.sendMessage(I18n.getComponent("gui.editor.feedback.not_configured", player));
                    return;
                }
                com.huidu.farmersdelight.recipe.CookingPotRecipe existing = (group == null || group.isBlank())
                        ? plugin.getCookingPotRecipes().getRecipe(id)
                        : plugin.getCookingPotRecipes().getRecipe(group, id);
                new com.huidu.farmersdelight.gui.editor.CookingPotEditorGui(plugin, player, id, group, existing, editorConfig).open();
            }
            case "board", "cutting_board", "cuttingboard" -> {
                com.huidu.farmersdelight.gui.RecipeViewGuiConfig.BaseConfig boardConfig =
                        plugin.getRecipeEditorGuiConfig().getCuttingBoardConfig();
                if (boardConfig == null) {
                    player.sendMessage(I18n.getComponent("gui.editor.feedback.not_configured", player));
                    return;
                }
                com.huidu.farmersdelight.recipe.CuttingBoardRecipe existing = plugin.getCuttingBoardRecipes().getRecipe(id);
                new com.huidu.farmersdelight.gui.editor.CuttingBoardEditorGui(plugin, player, id, existing, boardConfig).open();
            }
            default -> player.sendMessage(I18n.getComponent("gui.editor.usage", player));
        }
    }

    private void executeReload(CommandSender sender, String label, String[] args) {
        String target = args.length >= 2 ? normalize(args[1]) : "config";
        switch (target) {
            case "all" -> plugin.reloadAll();
            case "config" -> plugin.reloadMainConfigOnly();
            case "gui" -> plugin.reloadGuiConfig();
            case "lang", "language", "languages" -> plugin.reloadLanguageFiles();
            case "recipes", "recipe" -> plugin.reloadRecipeFiles();
            case "advancements", "advancement" -> plugin.reloadAdvancements();
            default -> {
                sendReloadUsage(sender);
                return;
            }
        }

        sender.sendMessage(I18n.getComponent("general.config_reloaded"));
        if (sender instanceof Player player) {
            sender.sendMessage(I18n.getComponent("general.hot_reload_warning", player));
        } else {
            sender.sendMessage(I18n.getComponent("general.hot_reload_warning"));
        }
    }

    private void executeCleanup(CommandSender sender, String label, String[] args) {
        int removed = 0;

        var displayManager = plugin.getItemDisplayManager();
        if (displayManager != null) {
            removed += displayManager.cleanup();
        }

        var trayManager = plugin.getTrayManager();
        if (trayManager != null) {
            removed += trayManager.cleanupInvalidAutoTrays();
        }

        sender.sendMessage(I18n.getComponent("command.cleanup_done", Map.of("count", String.valueOf(removed))));
    }

    private void executeDebugTools(CommandSender sender, String label, String[] args) {
        try {
            debugToolsCommand.getClass()
                    .getMethod("execute", CommandSender.class, String.class, String[].class)
                    .invoke(debugToolsCommand, sender, label, args);
        } catch (ReflectiveOperationException e) {
            sender.sendMessage(MINI_MESSAGE.deserialize("<red>Debug tools are not available in this build.</red>"));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize(I18n.get("command.help_title")));
        for (SubCommand command : commandList) {
            if (!command.canUse(sender)) {
                continue;
            }
            sender.sendMessage(MINI_MESSAGE.deserialize(
                    "<yellow>/fd " + command.name() + "</yellow> <gray>-</gray> " + resolveHelpText(command)
            ));
        }
    }

    private void sendDebugUsage(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize("<yellow>/fd debugtools <place|activate|status|profile|undo> ...</yellow>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(BASE_PERMISSION)) {
            return List.of();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = normalize(args[0]);
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
        return subCommand.tabCompleter().complete(sender, args);
    }

    private List<String> completeRecipe(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission("farmersdelight.admin");
        if (args.length == 2) {
            String partial = normalize(args[1]);
            List<String> base = new ArrayList<>(List.of("cooking_pot", "cutting_board"));
            if (admin) {
                base.add("edit");
            }
            List<String> completions = new ArrayList<>();
            for (String option : base) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }
            return completions;
        }

        if (admin && args.length >= 3 && normalize(args[1]).equals("edit")) {
            if (args.length == 3) {
                return prefixFilter(normalize(args[2]), List.of("pot", "board"));
            }
            if (args.length == 4) {
                String type = normalize(args[2]);
                if (type.equals("pot") || type.equals("cooking_pot") || type.equals("cookingpot")) {
                    return prefixFilter(normalize(args[3]), new ArrayList<>(plugin.getCookingPotRecipes().getRecipes().keySet()));
                }
                if (type.equals("board") || type.equals("cutting_board") || type.equals("cuttingboard")) {
                    return prefixFilter(normalize(args[3]), new ArrayList<>(plugin.getCuttingBoardRecipes().getRecipes().keySet()));
                }
            }
        }
        return List.of();
    }

    private List<String> prefixFilter(String partial, List<String> options) {
        List<String> completions = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(option);
            }
        }
        return completions;
    }

    private List<String> completeReload(CommandSender sender, String[] args) {
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

    private List<String> completeDebugTools(CommandSender sender, String[] args) {
        try {
            Object result = debugToolsCommand.getClass()
                    .getMethod("tabComplete", CommandSender.class, String[].class)
                    .invoke(debugToolsCommand, sender, args);
            if (result instanceof List<?> list) {
                List<String> completions = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String text) {
                        completions.add(text);
                    }
                }
                return completions;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return List.of();
    }

    private Object createDebugToolsCommand() {
        try {
            Class<?> type = Class.forName(DEBUG_TOOLS_CLASS);
            return type.getConstructor(FarmersDelightPlugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException e) {
            I18n.logWarning("plugin.debug_tools_missing");
            return null;
        }
    }

    private void sendReloadUsage(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd reload <all|config|gui|lang|recipes|advancements></yellow>"
        ));
    }

    private void sendNoPermission(CommandSender sender) {
        if (sender instanceof Player player) {
            sender.sendMessage(I18n.getComponent("general.no_permission", player));
        } else {
            sender.sendMessage(I18n.getComponent("general.no_permission"));
        }
    }

    private String resolveHelpText(SubCommand command) {
        String helpKey = command.helpKey();
        if (helpKey.startsWith("literal:")) {
            return helpKey.substring("literal:".length());
        }
        return I18n.get(helpKey);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface SubCommandExecutor {
        void execute(CommandSender sender, String label, String[] args);
    }

    @FunctionalInterface
    private interface SubCommandTabCompleter {
        List<String> complete(CommandSender sender, String[] args);
    }

    private record SubCommand(
            String name,
            List<String> aliases,
            String permission,
            String helpKey,
            SubCommandExecutor executor,
            SubCommandTabCompleter tabCompleter
    ) {

        private boolean canUse(CommandSender sender) {
            return permission == null || sender.hasPermission(permission);
        }
    }
}

