package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent.Source;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.RecipeStationType;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.gui.editor.RecipeEditorView;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.recipe.RecipeIds;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
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

final class RecipeSubCommand extends SubCommand {

    // Primary subcommands plus the FD station keywords already handled before type resolution. A recipe
    // type whose short name collides with any of these is only reachable by its full prefixed id.
    private static final Set<String> RESERVED_SUBCOMMANDS = Set.of(
            "book", "addon", "addons", "recipebook", "special", "special_recipes",
            "edit", "discovery", "cooking_pot", "cutting_board");

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
                RecipeBookGui.openMenu(player, null);
                return;
            }
        }

        RecipeViewGui gui = new RecipeViewGui(plugin, player);
        if (args.length < 2) {
            gui.open(player);
            return;
        }

        String sub = normalize(args[1]);
        if (sub.equals("special") || sub.equals("special_recipes")) {
            gui.openSpecialRecipes(player);
        } else if (RecipeStationType.isCookingPot(sub)) {
            gui.openCookingPotRecipes(player);
        } else if (RecipeStationType.isCuttingBoard(sub)) {
            gui.openCuttingBoardRecipes(player);
        } else {
            RecipeType type = resolveRecipeType(sub);
            if (type != null) {
                // Hand-off to the addon recipe book so a named type (e.g.
                // barbequesdelight:grilling) opens straight into its own list, rendered with
                // FarmersDelight's recipe-list style rather than FD's cooking-pot list.
                RecipeBookGui.openType(player, type, null);
            } else {
                gui.open(player);
            }
        }
    }

    // Matches a recipe type by its short name (the segment after the namespace colon, e.g. "grilling")
    // or, when that short name is ambiguous, by the full prefixed id (e.g. "barbequesdelight:grilling").
    // The exact id always wins; a repeated short name resolves to null, forcing the caller to disambiguate.
    private RecipeType resolveRecipeType(String token) {
        if (token.isEmpty()) {
            return null;
        }
        List<RecipeType> types = FarmersDelightApi.get().recipeTypes();
        for (RecipeType type : types) {
            if (type.id().equalsIgnoreCase(token)) {
                return type;
            }
        }
        RecipeType unique = null;
        for (RecipeType type : types) {
            if (shortId(type.id()).equalsIgnoreCase(token)) {
                if (unique != null) {
                    return null;
                }
                unique = type;
            }
        }
        return unique;
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
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
        String id = args.length >= 4 ? normalize(args[3]) : null;
        if (id != null && !RecipeEditorView.isValidRecipeId(id)) {
            player.sendMessage(I18n.getComponent("gui.editor.feedback.invalid_id", player));
            return;
        }
        String group = args.length >= 5 ? normalize(args[4]) : null;
        RecipeEditorView.open(plugin, player, type, id, group);
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
        // A recipe answers to its stored id and to the namespaced spelling of it, so a line that writes out
        // farmersdelight:beef_stew unlocks the recipe the file calls beef_stew.
        String resolvedRecipe = everyRecipe ? null
                : RecipeIds.canonical(typeId, recipeToken, Set.copyOf(known.getOrDefault(typeId, List.of())));
        if (!everyRecipe && resolvedRecipe == null) {
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
                    ? manager.unlock(target.id(), typeId, resolvedRecipe, Source.COMMAND)
                    : manager.lock(target.id(), typeId, resolvedRecipe, Source.COMMAND);
            changed = moved ? 1 : 0;
        }
        sendDiscoveryChange(sender, unlock, changed, typeId, target.name());
    }

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
                // Shown namespaced so the plugin's own ids read like every other id in the same list; the
                // stored form is what the state and the unlock calls keep using.
                shown.add(RecipeIds.displayId(typeId, recipeId));
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

    private boolean canUseDiscovery(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(DISCOVERY_PERMISSION);
    }

    private String resolveDiscoveryType(Set<String> knownTypes, String token) {
        return RecipeStationType.resolveTypeId(token, knownTypes);
    }

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

    private record DiscoveryTarget(UUID id, String name) {
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);
        boolean discovery = canUseDiscovery(sender);
        if (args.length == 2) {
            String partial = normalize(args[1]);
            List<String> base = new ArrayList<>(List.of(
                    "farmersdelight:cooking_pot", "cooking_pot",
                    "farmersdelight:cutting_board", "cutting_board", "book", "special"));
            List<RecipeType> types = FarmersDelightApi.get().recipeTypes();
            Map<String, Integer> shortCount = new HashMap<>();
            for (RecipeType type : types) {
                shortCount.merge(shortId(type.id()), 1, Integer::sum);
            }
            for (RecipeType type : types) {
                String id = type.id();
                String shortName = shortId(id);
                // A short name is only offered when it is unambiguous and does not collide with a reserved
                // primary command; otherwise the type is reachable by its full prefixed id alone.
                if (shortCount.getOrDefault(shortName, 0) == 1
                        && !RESERVED_SUBCOMMANDS.contains(shortName)) {
                    base.add(shortName);
                }
                base.add(id);
            }
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
                        // The namespaced spelling leads; the stored one stays offered so an operator who
                        // learned the bare key keeps finding it.
                        for (String recipeId : known.getOrDefault(typeId, List.of())) {
                            String namespaced = RecipeIds.displayId(typeId, recipeId);
                            options.add(namespaced);
                            if (!namespaced.equals(recipeId)) {
                                options.add(recipeId);
                            }
                        }
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

    private List<String> discoveryTypeTokens(Map<String, List<String>> known) {
        // The namespaced spelling of the two built-in types leads their bare keywords, matching how every id
        // in the command is displayed; the bare keyword stays offered because it is what operators have used.
        List<String> tokens = new ArrayList<>(List.of(
                RecipeDiscoveryManager.TYPE_COOKING_POT, "cooking_pot",
                RecipeDiscoveryManager.TYPE_CUTTING_BOARD, "cutting_board"));
        for (String typeId : known.keySet()) {
            if (!typeId.equals(RecipeDiscoveryManager.TYPE_COOKING_POT)
                    && !typeId.equals(RecipeDiscoveryManager.TYPE_CUTTING_BOARD)) {
                tokens.add(typeId);
            }
        }
        return tokens;
    }
}
