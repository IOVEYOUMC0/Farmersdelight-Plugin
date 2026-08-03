package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent.Source;
import com.huidu.farmersdelight.api.recipe.RecipeStationType;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.huidu.farmersdelight.command.CommandSupport.ADMIN_PERMISSION;
import static com.huidu.farmersdelight.command.CommandSupport.DISCOVERY_PERMISSION;
import static com.huidu.farmersdelight.command.CommandSupport.MINI;
import static com.huidu.farmersdelight.command.CommandSupport.normalize;
import static com.huidu.farmersdelight.command.CommandSupport.onlinePlayerNames;
import static com.huidu.farmersdelight.command.CommandSupport.prefixFilter;
import static com.huidu.farmersdelight.command.CommandSupport.sendNoPermission;

/** /fd recipe — opens the recipe view GUIs, the recipe editor, and drives recipe discovery unlock/lock. */
final class RecipeSubCommand extends SubCommand {

    private final FarmersDelightPlugin plugin;

    RecipeSubCommand(FarmersDelightPlugin plugin) {
        super("recipe", List.of("recipes"), "farmersdelight.command.recipe", "command.help_recipe");
        this.plugin = plugin;
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
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

        String sub = normalize(args[1]);
        if (RecipeStationType.isCookingPot(sub)) {
            gui.openCookingPotRecipes(player);
        } else if (RecipeStationType.isCuttingBoard(sub)) {
            gui.openCuttingBoardRecipes(player);
        } else {
            gui.open(player);
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
            if (RecipeStationType.isCookingPot(type)) {
                gui.openCookingPotRecipesForEdit(player);
            } else if (RecipeStationType.isCuttingBoard(type)) {
                gui.openCuttingBoardRecipesForEdit(player);
            } else {
                player.sendMessage(I18n.getComponent("gui.editor.usage", player));
            }
            return;
        }
        String id = normalize(args[3]);
        if (!id.matches("[a-z0-9_]+")) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.invalid_id", player));
            return;
        }
        if (RecipeStationType.isCookingPot(type)) {
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
        } else if (RecipeStationType.isCuttingBoard(type)) {
            com.huidu.farmersdelight.gui.RecipeViewGuiConfig.BaseConfig boardConfig =
                    plugin.getRecipeEditorGuiConfig().getCuttingBoardConfig();
            if (boardConfig == null) {
                player.sendMessage(I18n.getComponent("gui.editor.feedback.not_configured", player));
                return;
            }
            com.huidu.farmersdelight.recipe.CuttingBoardRecipe existing = plugin.getCuttingBoardRecipes().getRecipe(id);
            new com.huidu.farmersdelight.gui.editor.CuttingBoardEditorGui(plugin, player, id, existing, boardConfig).open();
        } else {
            player.sendMessage(I18n.getComponent("gui.editor.usage", player));
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

        Map<String, List<String>> known = manager.allRecipeKeysByType();
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
        return RecipeStationType.resolveTypeId(token, knownTypes);
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
        sender.sendMessage(MINI.deserialize(I18n.get("command.recipe_discovery_usage")));
    }

    /** A resolved discovery target: the UUID the state is keyed by, plus a name for feedback messages. */
    private record DiscoveryTarget(UUID id, String name) {
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
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
                if (RecipeStationType.isCookingPot(type)) {
                    return prefixFilter(normalize(args[3]), new ArrayList<>(plugin.getCookingPotRecipes().getRecipes().keySet()));
                }
                if (RecipeStationType.isCuttingBoard(type)) {
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
}
