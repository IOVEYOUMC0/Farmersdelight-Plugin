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
import com.huidu.farmersdelight.util.CraftEngineTagBridge;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Coordinates work that must wait for CraftEngine's deferred custom-item load. */
final class CraftEngineReadinessCoordinator {

    private final FarmersDelightPlugin plugin;
    private final StartupSummary startupSummary;
    private final CraftEngineResourceLease resourceLease;
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
     */
    private static final int READINESS_RETRY_ATTEMPTS = 8;

    CraftEngineReadinessCoordinator(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.startupSummary = new StartupSummary(plugin);
        this.resourceLease = new CraftEngineResourceLease(plugin.getCraftEngine(), plugin.getLogger());
    }

    boolean isReady() {
        try {
            var craftEngine = plugin.getCraftEngine();
            return craftEngine != null && !craftEngine.isEnabling() && !craftEngine.isReloading()
                    && ItemUtils.isAnyCustomItemLoaded();
        } catch (Exception ignored) {
            return false;
        }
    }

    void runWhenReady(Runnable notification) {
        withReadyResources(lease -> {
            try {
                notification.run();
            } finally {
                closeResourceLease(lease);
            }
        }, () -> { }, 0);
    }

    private void withReadyResources(Consumer<AutoCloseable> work, Runnable unavailable, int attempt) {
        if (!active || !plugin.isEnabled()) {
            unavailable.run();
            return;
        }
        AutoCloseable lease = null;
        try {
            if (isReady()) {
                lease = resourceLease.tryAcquire();
                if (lease != null && isReady()) {
                    work.accept(lease);
                    return;
                }
            }
        } catch (Exception failure) {
            closeResourceLease(lease);
            plugin.getLogger().log(Level.SEVERE, "Error during CraftEngine resource operation", failure);
            unavailable.run();
            return;
        } catch (Error failure) {
            closeResourceLease(lease);
            throw failure;
        }
        closeResourceLease(lease);
        if (attempt >= READINESS_RETRY_ATTEMPTS) {
            I18n.logWarning("plugin.content_reload_not_ready");
            unavailable.run();
            return;
        }
        long delay = Math.min(READINESS_RETRY_MAX_TICKS,
                READINESS_RETRY_INITIAL_TICKS * (1L + attempt / 2L));
        plugin.scheduler().runLater(() -> withReadyResources(work, unavailable, attempt + 1), delay);
    }

    private void closeResourceLease(AutoCloseable lease) {
        if (lease == null) {
            return;
        }
        try {
            lease.close();
        } catch (Exception failure) {
            plugin.getLogger().log(Level.SEVERE, "Cannot release CraftEngine resource exclusion", failure);
        }
    }

    void loadRecipesWhenReady(String logKey) {
        loadRecipesWhenReady(logKey, null);
    }

    void loadRecipesWhenReady(String logKey, Consumer<Boolean> completion) {
        loadRecipesThenPublish(logKey, null, () -> { }, completion);
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
            // Outside the first-warm-up guard on purpose: refreshAfterCraftEngineReload just emptied the item,
            // sound and GUI caches this fills.
            warmUpWhenReady("reload");
            indexLoadedChunkContentWhenReady();
            ToolRegistry.refresh();
            // Report the content counts once the readiness pass has loaded everything.
            reportContentSummaryWhenReady();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Error during CraftEngine reload processing in " + plugin.getClass().getSimpleName(), e);
        }
    }
    /**
     * Re-checks readiness on a widening delay, then runs the pending readiness work once it is true.
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
     */
    private void loadRecipesThenPublish(String logKey, Runnable contentWork, Runnable recipeWork) {
        loadRecipesThenPublish(logKey, contentWork, recipeWork, null);
    }

    private void loadRecipesThenPublish(String logKey, Runnable contentWork, Runnable recipeWork,
                                        Consumer<Boolean> completion) {
        withReadyResources(lease -> {
            long pass = readinessPassGeneration.incrementAndGet();
            RecipePublicationRollback rollback = captureRecipePublication();
            RecipePublicationWatch watch = plugin.recipeRegistrations().beginPublicationWatch();
            // Keep CE exclusion through the last registration slice, including cancellation and failure.
            watch.whenPublished(outcome -> {
                try {
                    onRecipesPublished(pass, outcome, rollback, contentWork, recipeWork);
                    if (completion != null) {
                        completion.accept(active && plugin.isEnabled() && outcome.published()
                                && pass == readinessPassGeneration.get());
                    }
                } catch (RuntimeException | LinkageError error) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Error during CraftEngine reload processing in " + plugin.getClass().getSimpleName(), error);
                } finally {
                    closeResourceLease(lease);
                }
            });
            try {
                plugin.loadRecipeManagers(logKey);
            } finally {
                plugin.recipeRegistrations().endPublicationWatch();
            }
        }, () -> {
            if (completion != null) {
                completion.accept(false);
            }
        }, 0);
    }

    /**
     * Runs this pass's work once its recipes published, or puts the captured sets back when they did not.
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
            // CE content is loaded at this point, so the c: members declared in item settings can be merged
            // into the family tag registry before the icon caches below resolve their tag ingredients.
            CraftEngineTagBridge.refresh(plugin);
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
