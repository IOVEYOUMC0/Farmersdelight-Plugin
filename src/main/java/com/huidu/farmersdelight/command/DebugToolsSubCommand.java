package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

import static com.huidu.farmersdelight.command.CommandSupport.MINI;

/**
 * /fd debugtools — bridges to the optional performance/debug tooling. The tooling class is resolved
 * reflectively so a build that strips it still compiles and runs; when it is absent create returns
 * null and the dispatcher never registers this subcommand.
 */
final class DebugToolsSubCommand extends SubCommand {

    private static final String DEBUG_TOOLS_CLASS = "com.huidu.farmersdelight.debug.DebugToolsCommand";

    private final Object delegate;

    private DebugToolsSubCommand(Object delegate) {
        super("debugtools", List.of("debug", "perf"), "farmersdelight.admin", "literal:debug performance tools");
        this.delegate = delegate;
    }

    /** Instantiates the debug tools bridge, or returns null when the tooling class is absent from this build. */
    static DebugToolsSubCommand create(FarmersDelightPlugin plugin) {
        try {
            Class<?> type = Class.forName(DEBUG_TOOLS_CLASS);
            Object delegate = type.getConstructor(FarmersDelightPlugin.class).newInstance(plugin);
            return new DebugToolsSubCommand(delegate);
        } catch (ReflectiveOperationException e) {
            I18n.logWarning("plugin.debug_tools_missing");
            return null;
        }
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
        try {
            delegate.getClass()
                    .getMethod("execute", CommandSender.class, String.class, String[].class)
                    .invoke(delegate, sender, label, args);
        } catch (ReflectiveOperationException e) {
            sender.sendMessage(MINI.deserialize("<red>Debug tools are not available in this build.</red>"));
        }
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
        try {
            Object result = delegate.getClass()
                    .getMethod("tabComplete", CommandSender.class, String[].class)
                    .invoke(delegate, sender, args);
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
}
