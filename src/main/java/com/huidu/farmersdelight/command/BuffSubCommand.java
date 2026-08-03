package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.buff.CustomBuff;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.huidu.farmersdelight.command.CommandSupport.MINI;
import static com.huidu.farmersdelight.command.CommandSupport.isInteger;
import static com.huidu.farmersdelight.command.CommandSupport.normalize;
import static com.huidu.farmersdelight.command.CommandSupport.onlinePlayerNames;
import static com.huidu.farmersdelight.command.CommandSupport.parsePositiveInt;
import static com.huidu.farmersdelight.command.CommandSupport.prefixFilter;

/** /fd buff — grants and clears registered custom buffs. Hidden entirely when the buff system is off. */
final class BuffSubCommand extends SubCommand {

    BuffSubCommand(FarmersDelightPlugin plugin) {
        super("buff", List.of("effect"), "farmersdelight.admin", "command.help_buff", plugin::isBuffSystemEnabled);
    }

    // /fd buff give <buffId> [level] [seconds] [player]  — grant a registered custom buff
    // /fd buff clear [buffId|all] [player]                — remove one / all (all reuses the milk-wipe path)
    @Override
    void execute(CommandSender sender, String label, String[] args) {
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
        return String.join(", ", buffSuffixes());
    }

    private void sendBuffUsage(CommandSender sender) {
        sender.sendMessage(MINI.deserialize(I18n.get("command.buff_usage")));
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
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
}
