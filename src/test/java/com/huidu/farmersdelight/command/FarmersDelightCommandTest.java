package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.manager.PerformanceMonitor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FarmersDelightCommandTest {

    @Test
    void statisticsMessagesHaveMatchingBundledTranslationsAndPlaceholders() throws Exception {
        var english = new YamlConfiguration();
        var chinese = new YamlConfiguration();
        english.loadFromString(Files.readString(Path.of("src/main/resources/lang/en_us.yml")));
        chinese.loadFromString(Files.readString(Path.of("src/main/resources/lang/zh_cn.yml")));
        var source = Files.readString(Path.of(
                "src/main/java/com/huidu/farmersdelight/command/StatsSubCommand.java"));
        var keys = new HashSet<>(Pattern.compile("command\\.stats_[a-z_]+(?=\")").matcher(source).results()
                .map(match -> match.group()).toList());
        keys.remove("command.stats_feature_");
        for (var feature : PerformanceMonitor.Feature.values()) {
            keys.add("command.stats_feature_" + feature.id());
        }
        var placeholder = Pattern.compile("\\{[a-z_]+}");
        for (String key : keys) {
            assertNotNull(english.getString(key), key);
            assertNotNull(chinese.getString(key), key);
            assertEquals(placeholder.matcher(english.getString(key)).results().map(match -> match.group()).sorted().toList(),
                    placeholder.matcher(chinese.getString(key)).results().map(match -> match.group()).sorted().toList(), key);
        }
    }

    @Test
    void perfAliasCompletesFeatureProfilesWithoutDebugTools() {
        FarmersDelightCommand command = new FarmersDelightCommand(new StatsSubCommand(null));
        CommandSender admin = sender("farmersdelight.command", "farmersdelight.admin");
        assertEquals(List.of("profile"), command.onTabComplete(admin, null, "fd",
                new String[]{"perf", "pro"}));
        assertEquals(List.of("handheld", "handheld_display"), command.onTabComplete(admin, null, "fd",
                new String[]{"perf", "profile", "200", "HAND"}));
        assertEquals(List.of("cooking_pot"), command.onTabComplete(admin, null, "fd",
                new String[]{"stats", "profile", "200", "cooking"}));
    }

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
