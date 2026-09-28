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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

import static com.huidu.farmersdelight.command.CommandSupport.MINI;
import static com.huidu.farmersdelight.command.CommandSupport.isInteger;
import static com.huidu.farmersdelight.command.CommandSupport.normalize;
import static com.huidu.farmersdelight.command.CommandSupport.onlinePlayerNames;
import static com.huidu.farmersdelight.command.CommandSupport.parsePositiveInt;
import static com.huidu.farmersdelight.command.CommandSupport.prefixFilter;

final class BuffSubCommand extends SubCommand {
    private final FarmersDelightPlugin plugin;

    BuffSubCommand(FarmersDelightPlugin plugin) {
        super("buff", List.of("effect"), "farmersdelight.admin", "command.help_buff", plugin::isBuffSystemEnabled);
        this.plugin = plugin;
    }

    // /fd buff give <player> <buffId> <time> <level>  — grant a registered custom buff (fixed order)
    // /fd buff clear <player> [buffId]                — remove one buff, or all when omitted
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
        // Fixed order: /fd buff give <player> <buffId> <time> <level>
        // time and level are optional and fall back to defaults when omitted; only the player and
        // buffId positions get tab completion, so time/level are typed by hand.
        if (args.length < 4) {
            sendBuffUsage(sender);
            return;
        }
        List<Player> targets = resolveTargets(sender, args[2]);
        if (targets.isEmpty()) {
            return;
        }
        CustomBuff buff = resolveBuff(args[3]);
        if (buff == null) {
            sender.sendMessage(I18n.getComponent("command.buff_unknown",
                    Map.of("buff", args[3], "buffs", buffIdList())));
            return;
        }
        int seconds = (args.length >= 5 && isInteger(args[4])) ? parsePositiveInt(args[4], 30) : 30;
        int level = (args.length >= 6 && isInteger(args[5])) ? parsePositiveInt(args[5], 1) : 1;
        dispatchTargets(targets, target -> CustomBuffRegistry.apply(target, buff.id(), level, seconds) ? 1 : 0,
                granted -> plugin.scheduler().run(() -> {
                    if (granted == 0) {
                        sender.sendMessage(I18n.getComponent("command.buff_not_grantable", Map.of("buff", buff.id())));
                        return;
                    }
                    sender.sendMessage(I18n.getComponent("command.buff_given", Map.of(
                            "buff", buff.id(),
                            "level", String.valueOf(level),
                            "seconds", String.valueOf(seconds),
                            "player", describeTargets(targets))));
                }));
    }

    private void executeBuffClear(CommandSender sender, String[] args) {
        // Fixed order: /fd buff clear <player> [buffId] — player required, buff optional (all when omitted).
        if (args.length < 3) {
            sendBuffUsage(sender);
            return;
        }
        List<Player> targets = resolveTargets(sender, args[2]);
        if (targets.isEmpty()) {
            return;
        }
        CustomBuff one = args.length >= 4 ? resolveBuff(args[3]) : null;
        if (args.length >= 4 && one == null) {
            sender.sendMessage(I18n.getComponent("command.buff_unknown",
                    Map.of("buff", args[3], "buffs", buffIdList())));
            return;
        }
        if (one == null) {
            dispatchTargets(targets, target -> CustomBuffRegistry.clearAll(target),
                    removed -> plugin.scheduler().run(() -> sender.sendMessage(I18n.getComponent(
                            "command.buff_cleared_all", Map.of(
                                    "count", String.valueOf(removed), "player", describeTargets(targets))))));
            return;
        }
        dispatchTargets(targets, target -> CustomBuffRegistry.clear(target, one.id()) ? 1 : 0,
                removed -> plugin.scheduler().run(() -> sender.sendMessage(I18n.getComponent(
                        "command.buff_cleared_one", Map.of(
                                "buff", one.id(), "player", describeTargets(targets),
                                "count", String.valueOf(removed))))));
    }

    /** Executes player mutations on each entity scheduler, then reports the aggregate on the global scheduler. */
    private void dispatchTargets(List<Player> targets, ToIntFunction<Player> action, IntConsumer complete) {
        AtomicInteger remaining = new AtomicInteger(targets.size());
        AtomicInteger result = new AtomicInteger();
        for (Player target : targets) {
            Runnable finished = () -> {
                if (remaining.decrementAndGet() == 0) complete.accept(result.get());
            };
            try {
                plugin.scheduler().runForEntity(target, () -> {
                    try {
                        result.addAndGet(action.applyAsInt(target));
                    } finally {
                        finished.run();
                    }
                }, finished);
            } catch (RuntimeException ignored) {
                finished.run();
            }
        }
    }

    // Resolves a player argument to one or more targets. Supports the @a / @p / @s selectors plus a
    // literal player name. Returns an empty list (after messaging the sender) when nothing matched.
    private List<Player> resolveTargets(CommandSender sender, String token) {
        String selector = normalize(token);
        switch (selector) {
            case "@a" -> {
                return new ArrayList<>(Bukkit.getOnlinePlayers());
            }
            case "@p", "@s" -> {
                if (sender instanceof Player self) {
                    return List.of(self);
                }
                sender.sendMessage(I18n.getComponent("command.player_only"));
                return List.of();
            }
            default -> {
                Player player = Bukkit.getPlayerExact(token);
                if (player == null) {
                    sender.sendMessage(I18n.getComponent("command.buff_player_not_found",
                            Map.of("player", token)));
                    return List.of();
                }
                return List.of(player);
            }
        }
    }

    private static String describeTargets(List<Player> targets) {
        if (targets.size() == 1) {
            return targets.get(0).getName();
        }
        List<String> names = new ArrayList<>();
        for (Player target : targets) {
            names.add(target.getName());
        }
        return "@a(" + String.join(", ", names) + ")";
    }

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
            switch (args.length) {
                case 3 -> {
                    // Player slot — selectors + names only.
                    return prefixFilter(normalize(args[2]), targetOptions());
                }
                case 4 -> {
                    // Buff slot.
                    return prefixFilter(normalize(args[3]), buffSuffixes());
                }
                // args.length >= 5 — time/level slots, intentionally no completion.
                default -> {
                    return List.of();
                }
            }
        } else if (mode.equals("clear") || mode.equals("remove")) {
            switch (args.length) {
                case 3 -> {
                    // Player slot (required) — selectors + names only, never the buff list.
                    return prefixFilter(normalize(args[2]), targetOptions());
                }
                case 4 -> {
                    // Optional buff slot — buff ids only.
                    return prefixFilter(normalize(args[3]), buffSuffixes());
                }
                default -> {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    // Slot placeholder sources kept distinct so tab completion never leaks one argument range into
    // another: targetOptions() is only used for the player position, buffSuffixes() for the buff one.
    private List<String> targetOptions() {
        List<String> options = new ArrayList<>(List.of("@a", "@p", "@s"));
        options.addAll(onlinePlayerNames());
        return options;
    }

    private List<String> buffSuffixes() {
        List<String> ids = new ArrayList<>();
        for (CustomBuff buff : CustomBuffRegistry.all()) {
            ids.add(shortId(buff.id()));
        }
        return ids;
    }
}
