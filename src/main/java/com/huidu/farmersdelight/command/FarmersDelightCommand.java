package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.BuildFlags;
import com.huidu.farmersdelight.api.buff.CustomBuff;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.i18n.I18n;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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
                "buff",
                List.of("effect"),
                "farmersdelight.admin",
                "command.help_buff",
                this::executeBuff,
                this::completeBuff
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

        if (args.length >= 2) {
            String sub = normalize(args[1]);
            if (sub.equals("book") || sub.equals("addon") || sub.equals("addons") || sub.equals("recipebook")) {
                com.huidu.farmersdelight.gui.recipebook.RecipeBookGui.openMenu(player, null);
                return;
            }
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

        // Notify addons so they reload in sync. "all" already fires this inside reloadAll().
        if (!target.equals("all")) {
            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new com.huidu.farmersdelight.api.event.FarmersDelightReloadEvent(target));
        }

        sender.sendMessage(I18n.getComponent("general.config_reloaded"));
        if (sender instanceof Player player) {
            sender.sendMessage(I18n.getComponent("general.hot_reload_warning", player));
        } else {
            sender.sendMessage(I18n.getComponent("general.hot_reload_warning"));
        }
    }

    private void executeCleanup(CommandSender sender, String label, String[] args) {
        int displays = 0;
        var displayManager = plugin.getItemDisplayManager();
        if (displayManager != null) {
            // Orphan-only: remove just the proxy displays no live block still owns, keeping legitimate
            // in-use visuals (a stove/skillet/cutting board/cooking pot that's still there). The old
            // full wipe removed valid displays too — and the stove ones did not re-appear.
            displays = displayManager.cleanupOrphans(plugin.collectLiveDisplayIds());
        }

        int trays = 0;
        var trayManager = plugin.getTrayManager();
        if (trayManager != null) {
            trays = trayManager.cleanupInvalidAutoTrays();
        }

        // Same hook style as FarmersDelightReloadEvent — addons (BAC etc.) clean their own orphan
        // state in step and report counts back via event.addRemoved().
        com.huidu.farmersdelight.api.event.FarmersDelightCleanupEvent cleanupEvent =
                new com.huidu.farmersdelight.api.event.FarmersDelightCleanupEvent();
        org.bukkit.Bukkit.getPluginManager().callEvent(cleanupEvent);
        int addon = cleanupEvent.getRemoved();

        sender.sendMessage(I18n.getComponent("command.cleanup_done", Map.of(
                "displays", String.valueOf(displays),
                "trays", String.valueOf(trays),
                "addon", String.valueOf(addon),
                "count", String.valueOf(displays + trays + addon))));
    }

    // /fd buff give <buffId> [level] [seconds] [player]  — grant a registered custom buff
    // /fd buff clear [buffId|all] [player]                — remove one / all (all reuses the milk-wipe path)
    private void executeBuff(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sendBuffUsage(sender);
            return;
        }
        switch (normalize(args[1])) {
            case "give", "add", "grant" -> executeBuffGive(sender, args);
            case "clear", "remove" -> executeBuffClear(sender, args);
            default -> sendBuffUsage(sender);
        }
    }

    private void executeBuffGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendBuffUsage(sender);
            return;
        }
        CustomBuff buff = resolveBuff(args[2]);
        if (buff == null) {
            sender.sendMessage(I18n.getComponent("command.buff_unknown",
                    Map.of("buff", args[2], "buffs", buffIdList())));
            return;
        }
        int level = args.length >= 4 ? parsePositiveInt(args[3], 1) : 1;
        int seconds = args.length >= 5 ? parsePositiveInt(args[4], 30) : 30;
        Player target = resolveTarget(sender, args, 5);
        if (target == null) {
            return;
        }
        if (!CustomBuffRegistry.apply(target, buff.id(), level, seconds)) {
            sender.sendMessage(I18n.getComponent("command.buff_not_grantable", Map.of("buff", buff.id())));
            return;
        }
        sender.sendMessage(I18n.getComponent("command.buff_given", Map.of(
                "buff", buff.id(),
                "level", String.valueOf(level),
                "seconds", String.valueOf(seconds),
                "player", target.getName())));
    }

    private void executeBuffClear(CommandSender sender, String[] args) {
        CustomBuff one = null;
        int playerIndex = 3;
        if (args.length >= 3 && !normalize(args[2]).equals("all")) {
            one = resolveBuff(args[2]);
            if (one == null) {
                // Not a buff id — accept `/fd buff clear <player>` (clear all for that player).
                if (Bukkit.getPlayerExact(args[2]) != null) {
                    playerIndex = 2;
                } else {
                    sender.sendMessage(I18n.getComponent("command.buff_unknown",
                            Map.of("buff", args[2], "buffs", buffIdList())));
                    return;
                }
            }
        }
        Player target = resolveTarget(sender, args, playerIndex);
        if (target == null) {
            return;
        }
        if (one == null) {
            int removed = CustomBuffRegistry.clearAll(target);
            sender.sendMessage(I18n.getComponent("command.buff_cleared_all", Map.of(
                    "count", String.valueOf(removed), "player", target.getName())));
        } else {
            if (one.isActive(target)) {
                one.remove(target);
            }
            sender.sendMessage(I18n.getComponent("command.buff_cleared_one", Map.of(
                    "buff", one.id(), "player", target.getName())));
        }
    }

    /** Resolves a buff token to a registered buff: exact namespaced id first, then the short suffix
     *  (e.g. comfort → farmersdelight:comfort, tipsy → brewinandchewin:tipsy). */
    private CustomBuff resolveBuff(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        CustomBuff exact = CustomBuffRegistry.byId(normalized);
        if (exact != null) {
            return exact;
        }
        for (CustomBuff buff : CustomBuffRegistry.all()) {
            if (shortId(buff.id()).equals(normalized)) {
                return buff;
            }
        }
        return null;
    }

    /** Target = the named online player when given, else the sender when it's a player. Sends the right
     *  error (offline / player-only) and returns null when unresolved. */
    private Player resolveTarget(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player player = Bukkit.getPlayerExact(args[index]);
            if (player == null) {
                sender.sendMessage(I18n.getComponent("command.buff_player_not_found",
                        Map.of("player", args[index])));
            }
            return player;
        }
        if (sender instanceof Player self) {
            return self;
        }
        sender.sendMessage(I18n.getComponent("command.player_only"));
        return null;
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private String buffIdList() {
        List<String> ids = new ArrayList<>();
        for (CustomBuff buff : CustomBuffRegistry.all()) {
            ids.add(shortId(buff.id()));
        }
        return String.join(", ", ids);
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void sendBuffUsage(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize(I18n.get("command.buff_usage")));
    }

    private List<String> completeBuff(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return prefixFilter(normalize(args[1]), List.of("give", "clear"));
        }
        String mode = normalize(args[1]);
        if (mode.equals("give") || mode.equals("add") || mode.equals("grant")) {
            if (args.length == 3) {
                return prefixFilter(normalize(args[2]), buffSuffixes());
            }
            if (args.length == 4) {
                return prefixFilter(normalize(args[3]), List.of("1", "2", "3"));
            }
            if (args.length == 5) {
                return prefixFilter(normalize(args[4]), List.of("30", "60", "120", "300"));
            }
            if (args.length == 6) {
                return prefixFilter(normalize(args[5]), onlinePlayerNames());
            }
        } else if (mode.equals("clear") || mode.equals("remove")) {
            if (args.length == 3) {
                List<String> options = new ArrayList<>();
                options.add("all");
                options.addAll(buffSuffixes());
                options.addAll(onlinePlayerNames());
                return prefixFilter(normalize(args[2]), options);
            }
            if (args.length == 4) {
                return prefixFilter(normalize(args[3]), onlinePlayerNames());
            }
        }
        return List.of();
    }

    private List<String> buffSuffixes() {
        List<String> ids = new ArrayList<>();
        for (CustomBuff buff : CustomBuffRegistry.all()) {
            ids.add(shortId(buff.id()));
        }
        return ids;
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        return names;
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
            List<String> base = new ArrayList<>(List.of("cooking_pot", "cutting_board", "book"));
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

