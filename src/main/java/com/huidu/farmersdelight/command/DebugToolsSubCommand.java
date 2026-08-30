package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

import static com.huidu.farmersdelight.command.CommandSupport.MINI;

final class DebugToolsSubCommand extends SubCommand {

    private static final String DEBUG_TOOLS_CLASS = "com.huidu.farmersdelight.debug.DebugToolsCommand";

    private final Object delegate;
    private final java.lang.reflect.Method executeMethod;
    private final java.lang.reflect.Method tabCompleteMethod;
    private final java.util.logging.Logger logger;

    private DebugToolsSubCommand(Object delegate, java.lang.reflect.Method executeMethod,
                                 java.lang.reflect.Method tabCompleteMethod, java.util.logging.Logger logger) {
        super("debugtools", List.of("debug", "perf"), "farmersdelight.admin", "literal:debug performance tools");
        this.delegate = delegate;
        this.executeMethod = executeMethod;
        this.tabCompleteMethod = tabCompleteMethod;
        this.logger = logger;
    }

    static DebugToolsSubCommand create(FarmersDelightPlugin plugin) {
        try {
            Class<?> type = Class.forName(DEBUG_TOOLS_CLASS);
            Object delegate = type.getConstructor(FarmersDelightPlugin.class).newInstance(plugin);
            java.lang.reflect.Method execute = type.getMethod("execute", CommandSender.class, String.class, String[].class);
            java.lang.reflect.Method tabComplete = type.getMethod("tabComplete", CommandSender.class, String[].class);
            return new DebugToolsSubCommand(delegate, execute, tabComplete, plugin.getLogger());
        } catch (ReflectiveOperationException e) {
            I18n.logWarning("plugin.debug_tools_missing");
            return null;
        }
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
        try {
            executeMethod.invoke(delegate, sender, label, args);
        } catch (ReflectiveOperationException e) {
            // Reflection wraps a throw from the target method in InvocationTargetException; unwrap it so
            // the real failure is logged with its stack instead of being masked as "not available".
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null)
                    ? ite.getCause() : e;
            logger.warning("Debug tools execution failed: " + cause);
            cause.printStackTrace();
            sender.sendMessage(MINI.deserialize("<red>Debug tools are not available in this build.</red>"));
        }
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
        try {
            Object result = tabCompleteMethod.invoke(delegate, sender, args);
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
