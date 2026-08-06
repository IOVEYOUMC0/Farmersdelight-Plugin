package com.huidu.farmersdelight.command;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.function.BooleanSupplier;

abstract class SubCommand {

    private final String name;
    private final List<String> aliases;
    // null = no permission node required.
    private final String permission;
    // An I18n key, or a "literal:" prefixed fixed string, describing the command in help output.
    private final String helpKey;
    // Re-checked on each use so config reload toggles take effect immediately. null means always available.
    private final BooleanSupplier availability;

    protected SubCommand(String name, List<String> aliases, String permission, String helpKey,
                         BooleanSupplier availability) {
        this.name = name;
        this.aliases = aliases;
        this.permission = permission;
        this.helpKey = helpKey;
        this.availability = availability;
    }

    protected SubCommand(String name, List<String> aliases, String permission, String helpKey) {
        this(name, aliases, permission, helpKey, null);
    }

    abstract void execute(CommandSender sender, String label, String[] args);

    abstract List<String> tabComplete(CommandSender sender, String[] args);

    final String name() {
        return name;
    }

    final List<String> aliases() {
        return aliases;
    }

    final String helpKey() {
        return helpKey;
    }

    final boolean isAvailable() {
        return availability == null || availability.getAsBoolean();
    }

    final boolean canUse(CommandSender sender) {
        return isAvailable() && (permission == null || sender.hasPermission(permission));
    }
}
