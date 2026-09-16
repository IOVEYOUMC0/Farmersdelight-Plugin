package com.huidu.farmersdelight.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarmersDelightCommandTest {

    @Test
    void completesEmptyPaperArgumentsAndPreservesFilteringAndPermissions() {
        FarmersDelightCommand command = new FarmersDelightCommand(
                new RecipeSubCommand(null), new ReloadSubCommand(null));
        CommandSender admin = sender("farmersdelight.command", "farmersdelight.command.recipe", "farmersdelight.admin");

        for (String alias : List.of("fd", "farmersdelight")) {
            assertEquals(List.of("recipe", "reload"), command.onTabComplete(admin, null, alias, new String[0]));
            assertEquals(List.of("recipe", "reload"), command.onTabComplete(admin, null, alias, new String[]{""}));
            assertEquals(List.of("reload"), command.onTabComplete(admin, null, alias, new String[]{"REL"}));
            assertEquals(List.of("config"), command.onTabComplete(admin, null, alias, new String[]{"reload", "con"}));
            assertEquals(List.of(), command.onTabComplete(admin, null, alias, new String[]{"unknown", ""}));
        }

        CommandSender player = sender("farmersdelight.command", "farmersdelight.command.recipe");
        assertEquals(List.of("recipe"), command.onTabComplete(player, null, "fd", new String[0]));
        assertEquals(List.of(), command.onTabComplete(player, null, "fd", new String[]{"reload", ""}));
        assertEquals(List.of(), command.onTabComplete(sender(), null, "fd", new String[0]));
    }

    private static CommandSender sender(String... permissions) {
        Set<String> allowed = Set.of(permissions);
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return allowed.contains(args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
