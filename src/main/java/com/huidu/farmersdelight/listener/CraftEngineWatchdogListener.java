package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.momirealms.craftengine.bukkit.util.KeyUtils;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

import java.util.logging.Level;

public final class CraftEngineWatchdogListener implements Listener {

    private static final String CRAFT_ENGINE = "CraftEngine";

    private final FarmersDelightPlugin plugin;
    private PluginTask pendingRecipeRestore;

    public CraftEngineWatchdogListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcesReload(ServerResourcesReloadedEvent event) {
        if (!plugin.isEnabled() || plugin.getServer().isStopping() || plugin.scheduler().isFolia()) {
            return;
        }
        if (pendingRecipeRestore == null || pendingRecipeRestore.isCancelled()) {
            pendingRecipeRestore = plugin.scheduler().runLater(this::restoreRecipes, 1L);
        }
    }

    private void restoreRecipes() {
        pendingRecipeRestore = null;
        if (!plugin.isEnabled() || plugin.getServer().isStopping()) {
            return;
        }
        var engine = plugin.getCraftEngine();
        if (engine.isReloading()) {
            pendingRecipeRestore = plugin.scheduler().runLater(this::restoreRecipes, 20L);
            return;
        }
        if (!ItemUtils.isAnyCustomItemLoaded()) {
            return;
        }
        try {
            // A data-pack reload replaces the native registry, but CE keeps its parsed recipes.
            var recipes = engine.recipeManager();
            for (RecipeType type : RecipeType.values()) {
                if (type == RecipeType.BREWING) continue;
                for (var recipe : recipes.recipesByType(type)) {
                    if (!recipes.isDataPackRecipe(recipe.id())) {
                        Bukkit.removeRecipe(KeyUtils.toNamespacedKey(recipe.id()));
                    }
                }
            }
            recipes.delayedLoad();
            recipes.runDelayedSyncTasks();
            ItemUtils.clearItemCache();
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING,
                    I18n.formatConsole("plugin.datapack_recipe_restore_failed"), error);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!CRAFT_ENGINE.equals(event.getPlugin().getName())) {
            return;
        }
        if (plugin.getServer().isStopping() || !plugin.isEnabled()) {
            return; // normal shutdown, or FD is already going down; nothing to do
        }
        I18n.logSevere("plugin.missing_dependency", "name", "FarmersDelight",
                "dependency", event.getPlugin().getName());
        // Folia sets isEnabled(false) before onDisable and its schedulers then refuse every task, so the
        // buff/bossbar flush has to run while we are still enabled. Harmless on Paper (main-thread save).
        plugin.flushBeforeRuntimeDisable();
        plugin.getServer().getPluginManager().disablePlugin(plugin);
    }
}
