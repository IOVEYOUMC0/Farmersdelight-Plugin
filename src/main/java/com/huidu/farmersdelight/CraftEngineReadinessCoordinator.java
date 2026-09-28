package com.huidu.farmersdelight;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent;
import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import com.huidu.farmersdelight.compat.CraftEngineStateUsageMonitor;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.gui.RecipeIngredientIcons;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.tool.ToolRegistry;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.Bukkit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Coordinates work that must wait for CraftEngine's deferred custom-item load. */
final class CraftEngineReadinessCoordinator {

    private final FarmersDelightPlugin plugin;
    private final StartupSummary startupSummary;
    private final AtomicBoolean loadedChunkContentIndexStarted = new AtomicBoolean();
    private final AtomicBoolean contentWarmupCompleted = new AtomicBoolean();
    private final AtomicLong reloadGeneration = new AtomicLong();
    private final AtomicBoolean contentSummaryRequested = new AtomicBoolean();
    private volatile boolean active = true;
    private PluginTask pendingReloadTask;

    CraftEngineReadinessCoordinator(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.startupSummary = new StartupSummary(plugin);
    }

    boolean isReady() {
        try {
            return ItemUtils.isAnyCustomItemLoaded();
        } catch (Exception ignored) {
            return false;
        }
    }

    void loadRecipesWhenReady(String logKey) {
        if (isReady()) {
            plugin.loadRecipeManagers(logKey);
        }
    }

    void refreshAdvancementsWhenReady(boolean reloading) {
        if (!plugin.advancementSystemUsable()) {
            plugin.disableAdvancementSystem();
            return;
        }
        if (isReady()) {
            plugin.refreshAdvancementSystem(reloading);
        }
    }

    void indexLoadedChunkContentWhenReady() {
        if (!isReady()) {
            return;
        }
        RopeBlockListener listener = plugin.getRopeBlockListener();
        if (listener != null && loadedChunkContentIndexStarted.compareAndSet(false, true)) {
            listener.indexRopesInLoadedChunks();
        }
    }

    void warmUpWhenReady(String reason) {
        if (isReady()) {
            warmUp(reason);
        }
    }

    void reportContentSummaryWhenReady() {
        if (isReady()) {
            startupSummary.report();
            // Addons register their namespaces during enable, after FD's first summary. Recount here so
            // the addon bucket reflects the complete loaded set without a polling task.
            CraftEngineStateUsageMonitor.logRealStateUsage(
                    plugin, I18n.formatConsole("plugin.startup_reason"));
        }
    }

    void requestContentSummary() {
        if (!active || !contentSummaryRequested.compareAndSet(false, true)) {
            return;
        }
        plugin.scheduler().runLater(() -> {
            contentSummaryRequested.set(false);
            if (active) {
                reportContentSummaryWhenReady();
            }
        }, 1L);
    }

    void queueReloadProcessing() {
        if (!active || !plugin.isEnabled()) {
            return;
        }

        long generation = reloadGeneration.incrementAndGet();
        if (pendingReloadTask != null && !pendingReloadTask.isCancelled()) {
            pendingReloadTask.cancel();
        }
        pendingReloadTask = plugin.scheduler().runLater(() -> processReload(generation), 5L);
    }

    void cancelPendingTasks() {
        active = false;
        if (pendingReloadTask != null) {
            pendingReloadTask.cancel();
            pendingReloadTask = null;
        }
    }

    private void processReload(long generation) {
        if (!active || generation != reloadGeneration.get()) {
            return;
        }
        try {
            pendingReloadTask = null;
            I18n.reload();
            I18n.logDetail("startup", "plugin.craftengine_reload");
            plugin.refreshAfterCraftEngineReload();
            if (!contentWarmupCompleted.get()) {
                loadRecipesWhenReady("plugin.refreshing_recipes_after_ce");
                refreshAdvancementsWhenReady(false);
            }
            // Outside the first-warm-up guard on purpose: refreshAfterCraftEngineReload just emptied the
            // item, sound and GUI caches this fills. Leaving it to the guard meant that after the first
            // warm-up every later reload dropped the caches and never rebuilt them, so the work was paid
            // again lazily, one item at a time, during play instead of once here.
            warmUpWhenReady("reload");
            indexLoadedChunkContentWhenReady();
            ToolRegistry.refresh();
            // Report the content counts once the readiness pass has loaded everything. Runtime API
            // registrations used to trigger this a tick later through their republish; recipes that arrive
            // as CraftEngine pack content have no republish, so the pass itself has to report.
            reportContentSummaryWhenReady();
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE,
                    "Error during CraftEngine reload processing in " + plugin.getClass().getSimpleName(), e);
        }
    }

    private void warmUp(String reason) {
        try {
            long start = System.nanoTime();
            int items = ItemUtils.warmItems("farmersdelight");
            long itemNanos = System.nanoTime() - start;
            long mark = System.nanoTime();
            TomatoVineBlockBehavior.warmAll();
            CookingPotGui.warm(plugin);
            long guiNanos = System.nanoTime() - mark;
            mark = System.nanoTime();
            warmRecipeIngredientIcons();
            long iconNanos = System.nanoTime() - mark;
            mark = System.nanoTime();
            FarmersDelightApi.get().refreshRecipeIndex();
            long indexNanos = System.nanoTime() - mark;
            long ms = (System.nanoTime() - start) / 1_000_000L;
            startupSummary.recordWarmup(items, ms);
            I18n.logDetail("startup", "plugin.warmup_done", "items", items, "ms", ms);
            // Per-phase split: this whole block runs synchronously on a tick thread, so knowing which
            // phase dominates is what decides whether it is worth splitting across ticks.
            I18n.logDetail("startup", "plugin.warmup_breakdown",
                    "items", itemNanos / 1_000_000L,
                    "gui", guiNanos / 1_000_000L,
                    "icons", iconNanos / 1_000_000L,
                    "index", indexNanos / 1_000_000L);
        } catch (RuntimeException | LinkageError t) {
            plugin.getLogger().log(Level.WARNING, I18n.formatConsole("plugin.warmup_failed"), t);
        }
        contentWarmupCompleted.set(true);
        Bukkit.getPluginManager().callEvent(
                new FarmersDelightWarmupEvent(reason));
    }

    private void warmRecipeIngredientIcons() {
        var cookingPotRecipes = plugin.getCookingPotRecipesOrNull();
        if (cookingPotRecipes != null) {
            for (var recipe : cookingPotRecipes.getAllRecipes()) {
                for (RecipeIngredient ingredient : recipe.getIngredients()) {
                    RecipeIngredientIcons.resolveIngredientOptions(ingredient);
                }
            }
        }
        var cuttingBoardRecipes = plugin.getCuttingBoardRecipesOrNull();
        if (cuttingBoardRecipes != null) {
            for (var recipe : cuttingBoardRecipes.getRecipes().values()) {
                if (recipe.getInput() != null) {
                    RecipeIngredientIcons.resolveIngredientOptions(recipe.getInput());
                }
                for (var tool : recipe.getTools()) {
                    if (tool != null && tool.getKey() != null) {
                        RecipeIngredientIcons.createItemFromKey(tool.getKey());
                    }
                }
            }
        }
    }
}
