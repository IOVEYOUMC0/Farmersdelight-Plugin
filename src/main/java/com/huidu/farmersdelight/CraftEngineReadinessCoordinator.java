package com.huidu.farmersdelight;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent;
import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import com.huidu.farmersdelight.compat.CraftEngineStateUsageMonitor;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.gui.RecipeIngredientIcons;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
import com.huidu.farmersdelight.recipe.RecipeContentEpoch;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipePublicationRollback;
import com.huidu.farmersdelight.recipe.RecipePublicationWatch;
import com.huidu.farmersdelight.tool.ToolRegistry;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
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
    // Bumped by every readiness pass. The work a pass runs once its recipes published compares against this:
    // a pass that a newer one has overtaken leaves the newer pass's state alone instead of reporting on it.
    private final AtomicLong readinessPassGeneration = new AtomicLong();
    private final AtomicBoolean contentSummaryRequested = new AtomicBoolean();
    private volatile boolean active = true;
    private PluginTask pendingReloadTask;
    private PluginTask pendingReadinessTask;
    private int readinessAttempt;
    /** Whether the pending readiness retry is the start-up pass rather than a reload pass. */
    private boolean readinessRetryIsStartup;

    /** First readiness re-check delay, in ticks; the schedule widens from here. */
    private static final long READINESS_RETRY_INITIAL_TICKS = 20L;
    /** Delay ceiling for the readiness re-checks. */
    private static final long READINESS_RETRY_MAX_TICKS = 100L;
    /**
     * Attempts allowed, covering about 20 seconds (20+20+40+40+60+60+80+80 ticks).
     *
     *
     * CraftEngine fires the reload event one tick after its own enable completes
     * (CraftEngine.callReloadEvent() is scheduled right after isEnabling is cleared) while its
     * pack contents finish loading asynchronously afterwards. Waiting a fixed handful of ticks therefore
     * probes readiness before any custom item exists, and without a re-check the warm-up and the content
     * summary never ran at all on a normal startup. Gating on readiness instead of on a fixed delay is what
     * keeps this correct on slower servers: the observed gap on the test server was about 12 seconds, so the
     * window is deliberately wider than that.
     */
    private static final int READINESS_RETRY_ATTEMPTS = 8;

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

    /**
     * Coalesces the summary requests the recipe managers raise as their content registers, so the report is
     * written once per burst instead of once per recipe.
     */
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

    /**
     * Runs the readiness-gated start-up work, or defers it until CraftEngine has content.
     *
     *
     * FarmersDelight normally enables before CraftEngine has finished loading its packs, so every
     * readiness-gated call made from onEnable is a no-op there. The CraftEngine reload event is the
     * intended follow-up pass, but it is not something the start-up path can require: CraftEngine fires it
     * from a delayed task rather than after its packs finish, and a listener that never reaches this
     * coordinator would otherwise leave the recipe managers empty until an explicit /fd reload.
     * Scheduling the retry here makes the warm-up independent of that event.
     *
     *
     * Safe to call after the work already ran: runStartupReadinessWork() reports the summary
     * rather than accumulating it. Unlike the reload event, this pass is not a config reload, so it leaves
     * the language files and the config-backed caches alone.
     */
    void startupReadinessWork() {
        if (isReady()) {
            runStartupReadinessWork();
            return;
        }
        readinessAttempt = 0;
        readinessRetryIsStartup = true;
        scheduleReadinessRetry();
    }

    void queueReloadProcessing() {
        if (!active || !plugin.isEnabled()) {
            return;
        }

        long generation = reloadGeneration.incrementAndGet();
        if (pendingReloadTask != null && !pendingReloadTask.isCancelled()) {
            pendingReloadTask.cancel();
        }
        // A fresh reload supersedes a readiness retry still waiting from an earlier event: that pass would
        // run the same gated work a second time.
        if (pendingReadinessTask != null) {
            pendingReadinessTask.cancel();
            pendingReadinessTask = null;
        }
        readinessAttempt = 0;
        readinessRetryIsStartup = false;
        pendingReloadTask = plugin.scheduler().runLater(() -> processReload(generation), 5L);
    }

    void cancelPendingTasks() {
        active = false;
        if (pendingReloadTask != null) {
            pendingReloadTask.cancel();
            pendingReloadTask = null;
        }
        if (pendingReadinessTask != null) {
            pendingReadinessTask.cancel();
            pendingReadinessTask = null;
        }
    }

    private void processReload(long generation) {
        if (!active || generation != reloadGeneration.get()) {
            return;
        }
        try {
            pendingReloadTask = null;
            // A CraftEngine reload means its content may have changed: parses cached against the previous
            // content generation are not reused for this pass.
            RecipeContentEpoch.advance();
            // Everything below reads CraftEngine content. When the reload event arrives before that content
            // finished loading, wait for it instead of doing the work against empty registries.
            if (!isReady()) {
                readinessRetryIsStartup = false;
                scheduleReadinessRetry();
                return;
            }
            readinessAttempt = 0;
            I18n.reload();
            I18n.logDetail("startup", "plugin.craftengine_reload");
            plugin.refreshAfterCraftEngineReload();
            if (!contentWarmupCompleted.get()) {
                loadRecipesThenPublish("plugin.refreshing_recipes_after_ce",
                        () -> refreshAdvancementsWhenReady(false),
                        () -> {
                            warmUpWhenReady("reload");
                            indexLoadedChunkContentWhenReady();
                            ToolRegistry.refresh();
                            reportContentSummaryWhenReady();
                        });
                return;
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
    /**
     * Re-checks readiness on a widening delay, then runs the pending readiness work once it is true.
     *
     *
     * Used when CraftEngine's reload event arrives before its pack contents finished loading; see
     * READINESS_RETRY_ATTEMPTS. Gives up with a visible line rather than retrying forever, so a
     * CraftEngine that never finishes loading does not leave a task spinning behind it.
     */
    private void scheduleReadinessRetry() {
        if (!active) {
            return;
        }
        if (readinessAttempt >= READINESS_RETRY_ATTEMPTS) {
            I18n.logWarning("plugin.craftengine_not_ready");
            return;
        }
        long delay = Math.min(READINESS_RETRY_MAX_TICKS,
                READINESS_RETRY_INITIAL_TICKS * (1L + readinessAttempt / 2L));
        readinessAttempt++;
        if (pendingReadinessTask != null) {
            pendingReadinessTask.cancel();
        }
        pendingReadinessTask = plugin.scheduler().runLater(this::retryWhenReady, delay);
    }

    private void retryWhenReady() {
        pendingReadinessTask = null;
        if (!active) {
            return;
        }
        if (!isReady()) {
            scheduleReadinessRetry();
            return;
        }
        readinessAttempt = 0;
        if (readinessRetryIsStartup) {
            runStartupReadinessWork();
            return;
        }
        runDeferredReadinessWork();
    }

    /**
     * The start-up variant of the readiness pass. It repeats the pack-dependent half of onEnable
     * that the readiness gate skipped, without the reload-only steps: the config and language files were read
     * from disk a moment ago and have not changed.
     */
    private void runStartupReadinessWork() {
        // This pass first sees the loaded CraftEngine content, so parses cached against anything earlier are
        // not reused.
        RecipeContentEpoch.advance();
        loadRecipesThenPublish("plugin.loading_recipes",
                () -> {
                    refreshAdvancementsWhenReady(false);
                    // Pet food, special recipes and the stove/skillet recipe caches are all read out of
                    // CraftEngine content, so the pass that first sees that content has to refresh them here.
                    plugin.refreshAfterCraftEngineReload();
                    // Explicit rather than left to warmUp's own gate, so an operator debugging start-up
                    // ordering can see whether this pass ran.
                    indexLoadedChunkContentWhenReady();
                },
                () -> {
                    warmUp("enable");
                    ToolRegistry.refresh();
                    reportContentSummaryWhenReady();
                });
    }

    /**
     * The work gated on CraftEngine readiness, run once it is actually available. Kept in one place so the
     * event path and its readiness retry cannot drift apart.
     */
    private void runDeferredReadinessWork() {
        RecipeContentEpoch.advance();
        loadRecipesThenPublish("plugin.refreshing_recipes_after_ce",
                () -> refreshAdvancementsWhenReady(false),
                () -> {
                    warmUpWhenReady("enable");
                    indexLoadedChunkContentWhenReady();
                    ToolRegistry.refresh();
                    reportContentSummaryWhenReady();
                });
    }

    /**
     * Loads the recipe managers and runs this pass's recipe-dependent work once their rounds have published.
     *
     *
     * The managers register their sets across ticks, so the work that reads them — the warm-up with its
     * ingredient icons and the recipe index, and the content summary with its counts — has to wait for the
     * publication rather than run against the set of the previous pass. A load whose files fit the budget
     * publishes during the call below and the work still runs in the same tick.
     *
     *
     * A pass whose publication failed, or that a newer pass replaced, keeps the catalog published before it and
     * skips the work entirely: a failure is reported, the captured sets are put back so no component stays on
     * the new files alone, and nothing is ever read out of a set that is only half registered.
     */
    private void loadRecipesThenPublish(String logKey, Runnable contentWork, Runnable recipeWork) {
        long pass = readinessPassGeneration.incrementAndGet();
        RecipePublicationRollback rollback = captureRecipePublication();
        RecipePublicationWatch watch = plugin.recipeRegistrations().beginPublicationWatch();
        // Registered before the load runs: a registration that fails halfway has to report and roll back too,
        // and the watch cannot settle before the window closes below, so the order of the work is unchanged.
        watch.whenPublished(outcome -> {
            try {
                onRecipesPublished(pass, outcome, rollback, contentWork, recipeWork);
            } catch (RuntimeException | LinkageError error) {
                plugin.getLogger().log(Level.SEVERE,
                        "Error during CraftEngine reload processing in " + plugin.getClass().getSimpleName(), error);
            }
        });
        try {
            loadRecipesWhenReady(logKey);
        } finally {
            plugin.recipeRegistrations().endPublicationWatch();
        }
    }

    /**
     * Runs this pass's work once its recipes published, or puts the captured sets back when they did not.
     *
     *
     * Work that reads recipe state waits for the publication; work that only reads CraftEngine content runs
     * here as well, so one pass never leaves a step running against a set another step is replacing. A pass a
     * newer one has overtaken does neither, and a pass that failed rolls back and reports instead of touching
     * recipe state at all.
     */
    private void onRecipesPublished(long pass, RecipePublicationWatch.Outcome outcome,
                                    RecipePublicationRollback rollback, Runnable contentWork, Runnable recipeWork) {
        if (!active || pass != readinessPassGeneration.get()) {
            return;
        }
        if (!outcome.published()) {
            for (Throwable restoreFailure : rollback.restore()) {
                plugin.getLogger().log(Level.WARNING,
                        I18n.formatConsole("plugin.recipe_rollback_failed"), restoreFailure);
            }
            if (outcome.failure() != null) {
                plugin.getLogger().log(Level.WARNING,
                        I18n.formatConsole("plugin.recipe_publication_failed"), outcome.failure());
            }
            return;
        }
        if (contentWork != null) {
            contentWork.run();
        }
        recipeWork.run();
    }

    /**
     * Captures what a failed pass has to put back: the pot and the cutting board sets, and the indexes derived
     * from them.
     *
     *
     * The derived indexes are not captured: they are rebuilt from the managers, so putting the managers back
     * and asking for a refresh restores them. That refresh runs last, after the sets it reads are in place.
     */
    private RecipePublicationRollback captureRecipePublication() {
        List<Runnable> restores = new ArrayList<>(3);
        CookingPotRecipeManager pot = plugin.getCookingPotRecipes();
        if (pot != null) {
            CookingPotRecipeManager.PublishedState before = pot.capturePublished();
            restores.add(() -> pot.restorePublished(before));
        }
        CuttingBoardRecipeManager board = plugin.getCuttingBoardRecipes();
        if (board != null) {
            CuttingBoardRecipeManager.PublishedState before = board.capturePublished();
            restores.add(() -> board.restorePublished(before));
        }
        restores.add(() -> {
            RecipeDiscoveryManager discovery = plugin.getRecipeDiscoveryManager();
            if (discovery != null) {
                discovery.invalidateIndex();
            }
            FarmersDelightApi.get().refreshRecipeIndex();
        });
        return new RecipePublicationRollback(restores);
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

    /**
     * Rebuilds the resolved ingredient-option caches after a change that invalidated them (common-tag
     * membership) so the next player to open the recipe book does not pay for the whole resolution.
     *
     *
     * Warming is normally part of warmUp(String), which only runs once per enable; this is the
     * targeted entry point for a tag change at runtime.
     */
    void rewarmRecipeIngredientIcons() {
        if (!isReady()) {
            return;
        }
        warmRecipeIngredientIcons();
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
