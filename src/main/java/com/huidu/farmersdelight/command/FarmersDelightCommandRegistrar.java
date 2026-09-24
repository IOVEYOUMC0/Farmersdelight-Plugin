package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.Collection;
import java.util.List;

/** Registers the Paper lifecycle command without coupling the plugin bootstrap to command details. */
public final class FarmersDelightCommandRegistrar {

    private FarmersDelightCommandRegistrar() {
    }

    public static void register(FarmersDelightPlugin plugin) {
        FarmersDelightCommand commandHandler = new FarmersDelightCommand(plugin);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("farmersdelight", "Main FarmersDelight command", List.of("fd"),
                        new BasicCommand() {
                            @Override
                            public void execute(CommandSourceStack source,
                                                String[] args) {
                                commandHandler.onCommand(source.getSender(), null, "farmersdelight", args);
                            }

                            @Override
                            public Collection<String> suggest(
                                    CommandSourceStack source,
                                    String[] args) {
                                List<String> completions = commandHandler.onTabComplete(
                                        source.getSender(), null, "farmersdelight", args);
                                return completions == null ? List.of() : completions;
                            }

                            @Override
                            public String permission() {
                                return "farmersdelight.command";
                            }
                        }));
    }
}
