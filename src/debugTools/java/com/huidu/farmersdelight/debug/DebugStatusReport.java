package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.rule.CropRules;
import com.huidu.farmersdelight.api.rule.RecipeConditions;
import com.huidu.farmersdelight.api.rule.StationRules;
import com.huidu.farmersdelight.datapack.FarmersDelightDatapacks;
import com.huidu.farmersdelight.gui.GuiTickManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.scheduler.AsyncSnapshot;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import com.huidu.farmersdelight.visual.ProxyItemDisplayManager;
import io.papermc.paper.datapack.Datapack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.List;

/** One read-only performance/data snapshot for the debug build and the console. */
final class DebugStatusReport {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private DebugStatusReport() {
    }

    static void print(FarmersDelightPlugin plugin, CommandSender sender) {
        if (plugin == null) {
            sender.sendMessage(MINI_MESSAGE.deserialize("<red>FD debug status: plugin unavailable.</red>"));
            return;
        }
        line(sender, "<gold>FD debug status</gold> <gray>server=" + plugin.getServer().getName()
                + " players=" + plugin.getServer().getOnlinePlayers().size()
                + " folia=" + plugin.scheduler().isFolia() + "</gray>");

        TickManager tickManager = plugin.getTickManager();
        if (tickManager == null) {
            line(sender, "<gray>tick:</gray> unavailable");
        } else {
            TickManager.PerformanceSnapshot snapshot = tickManager.getPerformanceSnapshot();
            line(sender, "<gray>tick:</gray> active=" + snapshot.currentActiveBlocks()
                    + " pending=+" + snapshot.pendingAdditions() + "/-" + snapshot.pendingRemovals()
                    + " budget=" + snapshot.tickBudget() + " interval=" + snapshot.tickInterval()
                    + " last-pass=" + millis(snapshot.lastNanos())
                    + " stats=" + snapshot.statsEnabled());
        }

        line(sender, "<gray>gui:</gray> callbacks="
                + GuiTickManager.getInstance(plugin).getActiveCallbackCount());

        line(sender, "<gray>rules:</gray> stations=" + StationRules.ids().size()
                + " crops=" + CropRules.ids().size()
                + " recipe-conditions=" + RecipeConditions.ids().size());

        ItemDisplayManager displays = plugin.getItemDisplayManager();
        if (displays instanceof ProxyItemDisplayManager proxy) {
            List<String> stats = proxy.debugStats();
            for (String line : stats) {
                line(sender, "<gray>display:</gray> " + line);
            }
        } else {
            line(sender, "<gray>display:</gray> unavailable");
        }

        AsyncSnapshot pool = plugin.scheduler().asyncSnapshot();
        line(sender, "<gray>async:</gray> submitted=" + pool.submitted() + " completed=" + pool.completed()
                + " rejected=" + pool.rejected() + " in-flight=" + pool.inFlight()
                + " queued=" + pool.queueDepth());

        line(sender, "<gray>datapacks:</gray> " + pack(plugin, FarmersDelightDatapacks.TAG_PACK)
                + " | " + pack(plugin, FarmersDelightDatapacks.ENCHANT_PACK)
                + " | " + pack(plugin, FarmersDelightDatapacks.DAMAGE_PACK));
    }

    private static String pack(FarmersDelightPlugin plugin, String name) {
        String id = plugin.getName() + "/" + name;
        Datapack pack = Bukkit.getDatapackManager().getPack(id);
        boolean prepared = plugin.getDataFolder().toPath().resolve("datapacks").resolve(name)
                .resolve("pack.mcmeta").toFile().isFile();
        if (pack == null) {
            return name + (prepared ? " prepared, undiscovered" : " missing");
        }
        return name + (pack.isEnabled() ? " enabled" : " discovered, disabled");
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3fms", nanos / 1_000_000.0);
    }

    private static void line(CommandSender sender, String text) {
        Component component = MINI_MESSAGE.deserialize(text);
        sender.sendMessage(component);
    }
}
