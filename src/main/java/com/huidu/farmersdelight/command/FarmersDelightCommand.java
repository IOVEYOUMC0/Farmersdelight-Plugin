package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.BuildFlags;
import com.huidu.farmersdelight.api.buff.CustomBuff;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent.Source;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public class FarmersDelightCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String BASE_PERMISSION = "farmersdelight.command";
    private static final String ADMIN_PERMISSION = "farmersdelight.admin";
    private static final String DISCOVERY_PERMISSION = "farmersdelight.command.recipe.discovery";
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
            // A subcommand whose feature is switched off is refused as unknown rather than as a permission
            // problem: the sender may well hold the permission, the feature just is not running.
            if (!subCommand.isAvailable()) {
                sendHelp(sender);
            } else {
                sendNoPermission(sender);
            }
            return true;
        }

        subCommand.executor().execute(sender, label, args);
        return true;
    }

    private void registerCommands() {
        register(SubCommand.builder("recipe", this::executeRecipe, this::completeRecipe)
                .aliases(List.of("recipes"))
                .permission("farmersdelight.command.recipe")
                .helpKey("command.help_recipe")
                .build());
        register(SubCommand.builder("reload", this::executeReload, this::completeReload)
                .permission("farmersdelight.admin")
                .helpKey("command.help_reload")
                .build());
        register(SubCommand.builder("cleanup", this::executeCleanup, (sender, args) -> List.of())
                .permission("farmersdelight.admin")
                .helpKey("command.help_cleanup")
                .build());
        register(SubCommand.builder("buff", this::executeBuff, this::completeBuff)
                .aliases(List.of("effect"))
                .permission("farmersdelight.admin")
                .helpKey("command.help_buff")
                .availability(plugin::isBuffSystemEnabled)
                .build());
        register(SubCommand.builder("help", (sender, label, args) -> sendHelp(sender), (sender, args) -> List.of())
                .aliases(List.of("?"))
                .helpKey("command.help_help")
                .build());
        if (BuildFlags.DEBUG_TOOLS) {
            debugToolsCommand = createDebugToolsCommand();
            if (debugToolsCommand != null) {
                register(SubCommand.builder("debugtools", this::executeDebugTools, this::completeDebugTools)
                        .aliases(List.of("debug", "perf"))
                        .permission("farmersdelight.admin")
                        .helpKey("literal:debug performance tools")
                        .build());
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
        // Checked ahead of the player-only gate: the discovery verb names its target explicitly, so it is
        // usable from the console, unlike the GUI-opening verbs below.
        if (args.length >= 2 && normalize(args[1]).equals("discovery")) {
            executeRecipeDiscovery(sender, args);
            return;
        }

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

    // /fd recipe discovery unlock|lock <type> <recipeId|all> [player]   — one recipe or a whole type
    // /fd recipe discovery unlock|lock all [player]                     — every type at once
    // /fd recipe discovery list [type] [player]                         — the unlocked ids of one type
    // /fd recipe discovery status [player]                              — unlocked/total per type
    private void executeRecipeDiscovery(CommandSender sender, String[] args) {
        if (!canUseDiscovery(sender)) {
            sendNoPermission(sender);
            return;
        }
        RecipeDiscoveryManager manager = plugin.getRecipeDiscoveryManager();
        if (manager == null) {
            sender.sendMessage(I18n.getComponent("command.recipe_discovery_unavailable"));
            return;
        }
        if (args.length < 3) {
            sendRecipeDiscoveryUsage(sender);
            return;
        }
        switch (normalize(args[2])) {
            case "unlock" -> executeRecipeDiscoverySet(sender, manager, args, true);
            case "lock" -> executeRecipeDiscoverySet(sender, manager, args, false);
            case "list" -> executeRecipeDiscoveryList(sender, manager, args);
            case "status" -> executeRecipeDiscoveryStatus(sender, manager, args);
            default -> sendRecipeDiscoveryUsage(sender);
        }
    }

    private void executeRecipeDiscoverySet(CommandSender sender, RecipeDiscoveryManager manager,
                                           String[] args, boolean unlock) {
        if (args.length < 4) {
            sendRecipeDiscoveryUsage(sender);
            return;
        }
        Map<String, List<String>> known = manager.allRecipeKeysByType();
        String typeToken = normalize(args[3]);
        if (typeToken.equals("all")) {
            DiscoveryTarget target = resolveDiscoveryTarget(sender, args, 4);
            if (target == null) {
                return;
            }
            warnIfDiscoveryDisabled(sender, manager);
            int changed = unlock
                    ? manager.unlockAll(target.id(), Source.COMMAND)
                    : manager.lockAll(target.id(), Source.COMMAND);
            sendDiscoveryChange(sender, unlock, changed, "all", target.name());
            return;
        }

        String typeId = resolveDiscoveryType(known.keySet(), typeToken);
        if (typeId == null) {
            sender.sendMessage(I18n.getComponent("command.recipe_discovery_unknown_type", Map.of(
                    "type", args[3],
                    "types", String.join(", ", known.keySet()))));
            return;
        }
        if (args.length < 5) {
            sendRecipeDiscoveryUsage(sender);
            return;
        }
        String recipeToken = args[4];
        DiscoveryTarget target = resolveDiscoveryTarget(sender, args, 5);
        if (target == null) {
            return;
        }
        boolean everyRecipe = normalize(recipeToken).equals("all");
        if (!everyRecipe && !manager.isKnownRecipe(typeId, recipeToken)) {
            sender.sendMessage(I18n.getComponent("command.recipe_discovery_unknown_recipe", Map.of(
                    "recipe", recipeToken,
                    "type", typeId)));
            return;
        }
        warnIfDiscoveryDisabled(sender, manager);
        int changed;
        if (everyRecipe) {
            changed = unlock
                    ? manager.unlockAllOfType(target.id(), typeId, Source.COMMAND)
                    : manager.lockAllOfType(target.id(), typeId, Source.COMMAND);
        } else {
            boolean moved = unlock
                    ? manager.unlock(target.id(), typeId, recipeToken, Source.COMMAND)
                    : manager.lock(target.id(), typeId, recipeToken, Source.COMMAND);
            changed = moved ? 1 : 0;
        }
        sendDiscoveryChange(sender, unlock, changed, typeId, target.name());
    }

    /** Notes that an edit is being stored while the feature itself is switched off. Only the unlock/lock path
     *  calls this, and only once its own arguments have validated: the read-only verbs change nothing, and a
     *  mistyped verb should get the usage line alone. */
    private void warnIfDiscoveryDisabled(CommandSender sender, RecipeDiscoveryManager manager) {
        if (!manager.isEnabled()) {
            // The stored state is still edited and persisted; it just has no visible effect until the
            // feature is switched on, so say so rather than letting the operator think nothing happened.
            sender.sendMessage(I18n.getComponent("command.recipe_discovery_feature_off"));
        }
    }

    private void executeRecipeDiscoveryList(CommandSender sender, RecipeDiscoveryManager manager, String[] args) {
        Map<String, List<String>> known = manager.allRecipeKeysByType();
        // The type is optional, so the token after "list" may be either a type or a player name. When it is
        // the last token and names an online player, the player wins: type tokens like "pot" or "board" are
        // legal player names, and resolving the type first would make such a player unreachable and silently
        // report the sender's own data instead. A type can still be selected explicitly by its full id, which
        // is not a legal name. A further token after this one means the first must be the type.
        int playerIndex = 3;
        String typeId = null;
        if (args.length > playerIndex) {
            String token = args[playerIndex];
            boolean namesOnlinePlayer = args.length == playerIndex + 1 && Bukkit.getPlayerExact(token) != null;
            if (!namesOnlinePlayer) {
                typeId = resolveDiscoveryType(known.keySet(), normalize(token));
                if (typeId != null) {
                    playerIndex++;
                }
            }
        }
        if (typeId == null) {
            typeId = RecipeDiscoveryManager.TYPE_COOKING_POT;
        }
        DiscoveryTarget target = resolveDiscoveryTarget(sender, args, playerIndex);
        if (target == null) {
            return;
        }
        List<String> all = known.getOrDefault(typeId, List.of());
        Set<String> unlockedIds = manager.unlockedOf(target.id(), typeId);
        List<String> shown = new ArrayList<>();
        for (String recipeId : all) {
            if (unlockedIds.contains(recipeId)) {
                shown.add(recipeId);
            }
        }
        if (shown.isEmpty()) {
            sender.sendMessage(I18n.getComponent("command.recipe_discovery_list_empty", Map.of(
                    "type", typeId,
                    "player", target.name())));
            return;
        }
        sender.sendMessage(I18n.getComponent("command.recipe_discovery_list", Map.of(
                "type", typeId,
                "player", target.name(),
                "count", String.valueOf(shown.size()),
                "total", String.valueOf(all.size()),
                "recipes", String.join(", ", shown))));
    }

    private void executeRecipeDiscoveryStatus(CommandSender sender, RecipeDiscoveryManager manager, String[] args) {
        DiscoveryTarget target = resolveDiscoveryTarget(sender, args, 3);
        if (target == null) {
            return;
        }
        Map<String, List<String>> known = manager.allRecipeKeysByType();
        int unlockedTotal = 0;
        int total = 0;
        List<String[]> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : known.entrySet()) {
            List<String> ids = entry.getValue();
            Set<String> unlockedIds = manager.unlockedOf(target.id(), entry.getKey());
            int count = 0;
            for (String recipeId : ids) {
                if (unlockedIds.contains(recipeId)) {
                    count++;
                }
            }
            unlockedTotal += count;
            total += ids.size();
            lines.add(new String[]{entry.getKey(), String.valueOf(count), String.valueOf(ids.size())});
        }
        sender.sendMessage(I18n.getComponent("command.recipe_discovery_status", Map.of(
                "player", target.name(),
                "unlocked", String.valueOf(unlockedTotal),
                "total", String.valueOf(total))));
        for (String[] line : lines) {
            sender.sendMessage(I18n.getComponent("command.recipe_discovery_status_line", Map.of(
                    "type", line[0],
                    "unlocked", line[1],
                    "total", line[2])));
        }
    }

    /** Discovery rides the same admin gate the recipe editor uses, plus a dedicated node so the ability can
     *  be delegated without granting full admin. */
    private boolean canUseDiscovery(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(DISCOVERY_PERMISSION);
    }

    /** Maps a type token to a registered type id: the short aliases for FarmersDelight's own two types, or
     *  an exact addon type id. Returns null when nothing matches. */
    private String resolveDiscoveryType(Set<String> knownTypes, String token) {
        String alias = switch (token) {
            case "pot", "cooking_pot", "cookingpot" -> RecipeDiscoveryManager.TYPE_COOKING_POT;
            case "board", "cutting_board", "cuttingboard" -> RecipeDiscoveryManager.TYPE_CUTTING_BOARD;
            default -> null;
        };
        if (alias != null) {
            return alias;
        }
        for (String known : knownTypes) {
            if (known.toLowerCase(Locale.ROOT).equals(token)) {
                return known;
            }
        }
        return null;
    }

    /**
     * Resolves the target player, defaulting to the sender when no name is given. Offline targets are
     * supported because the discovery state is keyed by UUID, every stored entry is held in memory, and the
     * save path merges into the data file instead of rewriting it — so editing an absent player is durable.
     * Only cached identities are accepted: resolving an unknown name would block the calling thread on a
     * Mojang profile lookup.
     */
    private DiscoveryTarget resolveDiscoveryTarget(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            if (sender instanceof Player self) {
                return new DiscoveryTarget(self.getUniqueId(), self.getName());
            }
            sender.sendMessage(I18n.getComponent("command.player_only"));
            return null;
        }
        String token = args[index];
        Player online = Bukkit.getPlayerExact(token);
        if (online != null) {
            return new DiscoveryTarget(online.getUniqueId(), online.getName());
        }
        try {
            return new DiscoveryTarget(UUID.fromString(token), token);
        } catch (IllegalArgumentException notAUuid) {
            // Fall through to the name cache below.
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(token);
        if (cached != null) {
            String name = cached.getName();
            return new DiscoveryTarget(cached.getUniqueId(), name == null ? token : name);
        }
        sender.sendMessage(I18n.getComponent("command.recipe_discovery_player_not_found",
                Map.of("player", token)));
        return null;
    }

    private void sendDiscoveryChange(CommandSender sender, boolean unlock, int changed,
                                     String typeId, String playerName) {
        sender.sendMessage(I18n.getComponent(
                unlock ? "command.recipe_discovery_unlocked" : "command.recipe_discovery_locked",
                Map.of(
                        "count", String.valueOf(changed),
                        "type", typeId,
                        "player", playerName)));
    }

    private void sendRecipeDiscoveryUsage(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize(I18n.get("command.recipe_discovery_usage")));
    }

    /** A resolved discovery target: the UUID the state is keyed by, plus a name for feedback messages. */
    private record DiscoveryTarget(UUID id, String name) {
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
            java.util.Set<Integer> liveIds = plugin.collectLiveDisplayIds();
            // Let addons mark their own packet-display handles as live (e.g. the items shown on a coaster)
            // so the orphan sweep doesn't wipe them.
            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new com.huidu.farmersdelight.api.event.FarmersDelightCollectLiveDisplaysEvent(liveIds));
            displays = displayManager.cleanupOrphans(liveIds);
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
        // level and seconds are optional and identified by TYPE, not position: leading numeric tokens are the
        // level then the seconds, and the first non-numeric token is the player name. So every form works —
        // `/fd buff give <buff>`, `/fd buff give <buff> <player>`, `/fd buff give <buff> <level> <player>`,
        // `/fd buff give <buff> <level> <seconds> <player>` — without forcing a duration/level to target someone.
        int argIndex = 3;
        int level = 1;
        int seconds = 30;
        if (argIndex < args.length && isInteger(args[argIndex])) {
            level = parsePositiveInt(args[argIndex++], 1);
        }
        if (argIndex < args.length && isInteger(args[argIndex])) {
            seconds = parsePositiveInt(args[argIndex++], 30);
        }
        Player target = resolveTarget(sender, args, argIndex);
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

    /** True when the token is all digits — i.e. a level/seconds argument rather than a player name. */
    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
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
                // Next token is either the level or the player (type-based parsing) — suggest both.
                List<String> options = new ArrayList<>(List.of("1", "2", "3"));
                options.addAll(onlinePlayerNames());
                return prefixFilter(normalize(args[3]), options);
            }
            if (args.length == 5) {
                // Either the seconds or the player (when a level was given).
                List<String> options = new ArrayList<>(List.of("30", "60", "120", "300"));
                options.addAll(onlinePlayerNames());
                return prefixFilter(normalize(args[4]), options);
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
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);
        boolean discovery = canUseDiscovery(sender);
        if (args.length == 2) {
            String partial = normalize(args[1]);
            List<String> base = new ArrayList<>(List.of("cooking_pot", "cutting_board", "book"));
            if (admin) {
                base.add("edit");
            }
            if (discovery) {
                base.add("discovery");
            }
            List<String> completions = new ArrayList<>();
            for (String option : base) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }
            return completions;
        }

        if (discovery && args.length >= 3 && normalize(args[1]).equals("discovery")) {
            return completeRecipeDiscovery(args);
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

    private List<String> completeRecipeDiscovery(String[] args) {
        RecipeDiscoveryManager manager = plugin.getRecipeDiscoveryManager();
        if (manager == null) {
            return List.of();
        }
        if (args.length == 3) {
            return prefixFilter(normalize(args[2]), List.of("unlock", "lock", "list", "status"));
        }
        // allRecipeKeysByType is uncached and walks every default recipe, every custom-group recipe and every
        // addon type's recipes into a fresh map, so it is looked up only in the branches that read it — not
        // once per keystroke for the branches that only complete player names.
        switch (normalize(args[2])) {
            case "unlock", "lock" -> {
                if (args.length == 4) {
                    List<String> options = new ArrayList<>(discoveryTypeTokens(manager.allRecipeKeysByType()));
                    options.add("all");
                    return prefixFilter(normalize(args[3]), options);
                }
                boolean everyType = normalize(args[3]).equals("all");
                if (args.length == 5) {
                    if (everyType) {
                        return prefixFilter(normalize(args[4]), onlinePlayerNames());
                    }
                    List<String> options = new ArrayList<>();
                    options.add("all");
                    Map<String, List<String>> known = manager.allRecipeKeysByType();
                    String typeId = resolveDiscoveryType(known.keySet(), normalize(args[3]));
                    if (typeId != null) {
                        options.addAll(known.getOrDefault(typeId, List.of()));
                    }
                    return prefixFilter(normalize(args[4]), options);
                }
                if (args.length == 6 && !everyType) {
                    return prefixFilter(normalize(args[5]), onlinePlayerNames());
                }
            }
            case "list" -> {
                if (args.length == 4) {
                    // The type is optional here, so both a type and a player name are valid next tokens.
                    List<String> options = new ArrayList<>(discoveryTypeTokens(manager.allRecipeKeysByType()));
                    options.addAll(onlinePlayerNames());
                    return prefixFilter(normalize(args[3]), options);
                }
                if (args.length == 5) {
                    return prefixFilter(normalize(args[4]), onlinePlayerNames());
                }
            }
            case "status" -> {
                if (args.length == 4) {
                    return prefixFilter(normalize(args[3]), onlinePlayerNames());
                }
            }
            default -> {
                return List.of();
            }
        }
        return List.of();
    }

    /** Type tokens offered for completion: the short aliases for FarmersDelight's own types, plus the id of
     *  every registered addon type. */
    private List<String> discoveryTypeTokens(Map<String, List<String>> known) {
        List<String> tokens = new ArrayList<>(List.of("cooking_pot", "cutting_board"));
        for (String typeId : known.keySet()) {
            if (!typeId.equals(RecipeDiscoveryManager.TYPE_COOKING_POT)
                    && !typeId.equals(RecipeDiscoveryManager.TYPE_CUTTING_BOARD)) {
                tokens.add(typeId);
            }
        }
        return tokens;
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
            SubCommandTabCompleter tabCompleter,
            // 每次使用时重新检查，确保配置重载后功能开关即时生效。null 表示始终可用。
            BooleanSupplier availability
    ) {

        /** 当该子命令驱动的功能已开启时返回 true。功能关闭的子命令会在帮助列表和 Tab 补全中隐藏，手动输入也会被拒绝。 */
        private boolean isAvailable() {
            return availability == null || availability.getAsBoolean();
        }

        /** 帮助、Tab 补全和执行共用的统一入口。 */
        private boolean canUse(CommandSender sender) {
            return isAvailable() && (permission == null || sender.hasPermission(permission));
        }

        /** 创建一个 Builder，name、executor 和 tabCompleter 为必填项。 */
        static Builder builder(String name, SubCommandExecutor executor, SubCommandTabCompleter tabCompleter) {
            return new Builder(name, executor, tabCompleter);
        }

        static final class Builder {
            private final String name;
            private final SubCommandExecutor executor;
            private final SubCommandTabCompleter tabCompleter;
            private List<String> aliases = List.of();
            private String permission;
            private String helpKey;
            private BooleanSupplier availability;

            Builder(String name, SubCommandExecutor executor, SubCommandTabCompleter tabCompleter) {
                this.name = name;
                this.executor = executor;
                this.tabCompleter = tabCompleter;
            }

            Builder aliases(List<String> aliases) { this.aliases = aliases; return this; }
            Builder permission(String permission) { this.permission = permission; return this; }
            Builder helpKey(String helpKey) { this.helpKey = helpKey; return this; }
            Builder availability(BooleanSupplier availability) { this.availability = availability; return this; }

            SubCommand build() {
                return new SubCommand(name, aliases, permission, helpKey, executor, tabCompleter, availability);
            }
        }
    }
}

