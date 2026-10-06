package com.huidu.farmersdelight;

import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.listener.HorseFeedTemptListener;
import com.huidu.farmersdelight.loot.KnifeDropHandler;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
import com.huidu.farmersdelight.startup.StartupVersionBanner;
import com.huidu.farmersdelight.tool.ToolRegistry;

final class StartupSummary {

    private final FarmersDelightPlugin plugin;

    // Counts of the last report printed at INFO, used to suppress an unchanged repeat. Excludes the warmup
    // duration on purpose: it varies between passes and would defeat the comparison on every run.
    private volatile String lastReportedCounts;

    // Warmup results, recorded by the warmup pass because they are produced there and nowhere else.
    private volatile int warmedItems;
    private volatile long warmupMillis;
    // The warmup clause is carried by the first summary that has warmup results; later summaries (a
    // reload, an addon republish) report counts only, so stale timings are never re-presented.
    private boolean warmupReported;

    StartupSummary(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    void recordWarmup(int items, long millis) {
        this.warmedItems = items;
        this.warmupMillis = millis;
    }

    // Synchronized because the two call sites can run on different threads (plugin enable versus the
    // deferred CraftEngine readiness task), and the changed-since-last-report check is a read-then-write.
    synchronized void report() {
        CookingPotRecipeManager cookingPot = plugin.getCookingPotRecipesOrNull();
        CuttingBoardRecipeManager cuttingBoard = plugin.getCuttingBoardRecipesOrNull();
        KnifeDropHandler knifeDrops = plugin.getKnifeDropsOrNull();
        HorseFeedTemptListener petFoods = plugin.getHorseFeedTemptListener();
        AdvancementManager advancements = plugin.getAdvancementManager();

        int cookingPotRecipes = cookingPot == null ? 0 : cookingPot.getRecipeCount();
        int cookingPotExternal = cookingPot == null ? 0 : cookingPot.getExternalRecipeCount();
        int cookingPotPack = cookingPot == null ? 0 : cookingPot.getPackRecipeCount();
        // "FD" is the plugin's own recipe file; "addon" is everything an addon brings, whether it arrives as
        // pack content (the default route) or as a runtime API registration. Folding packs into the addon
        // figure keeps the two numbers adding up to the total on language files that predate the pack route.
        int addonCookingPot = cookingPotExternal + cookingPotPack;
        int fdCookingPot = cookingPotRecipes - addonCookingPot;
        int customPotRecipes = cookingPot == null ? 0 : cookingPot.getCustomRecipeCount();
        int cuttingBoardRecipes = cuttingBoard == null ? 0 : cuttingBoard.getRecipeCount();
        int cuttingBoardExternal = cuttingBoard == null ? 0 : cuttingBoard.getExternalRecipeCount();
        int cuttingBoardPack = cuttingBoard == null ? 0 : cuttingBoard.getPackRecipeCount();
        int addonCuttingBoard = cuttingBoardExternal + cuttingBoardPack;
        int fdCuttingBoard = cuttingBoardRecipes - addonCuttingBoard;
        int dropRules = knifeDrops == null ? 0 : knifeDrops.getDropRuleCount();
        int petFoodCount = petFoods == null ? 0 : petFoods.getTemptFoodCount();
        int advancementCount = advancements == null ? 0 : advancements.getLoadedCount();
        // Addon tabs are built by a separate registry, so their advancements are absent from the manager's
        // own count. Reported as their own figure and folded into the digest below, so a tab appearing or
        // disappearing both shows up in the line and re-triggers the report.
        int addonAdvancementCount = plugin.getAddonAdvancementRegistry().getLoadedAdvancementCount();
        int items = warmedItems;

        String counts = cookingPotRecipes + "/" + cookingPotExternal + "/" + cookingPotPack + "/"
                + customPotRecipes + "/" + cuttingBoardRecipes + "/" + cuttingBoardExternal + "/"
                + cuttingBoardPack + "/"
                + dropRules + "/" + petFoodCount + "/" + advancementCount + "/" + addonAdvancementCount
                + "/" + items;

        Object[] args = {
                "cooking_pot", cookingPotRecipes,
                "fd_pot", fdCookingPot,
                "cooking_pot_addon", addonCookingPot,
                "custom_pot", customPotRecipes,
                "cutting_board", cuttingBoardRecipes,
                "fd_board", fdCuttingBoard,
                "cutting_board_addon", addonCuttingBoard,
                "drop_rules", dropRules,
                "pet_foods", petFoodCount,
                "advancements", advancementCount,
                "addon_advancements", addonAdvancementCount,
                // The versions and the tool count are carried by the summary line as well, so a normal boot needs
                // no second line for either of them.
                "banner", versionBanner(),
                "tools", ToolRegistry.all().size()
        };
        // The split the summary line cannot show without changing an existing language string: how much of
        // the addon figure arrived as CraftEngine pack content. Detail level, so a normal boot stays quiet.
        if (cookingPotPack > 0 || cuttingBoardPack > 0) {
            I18n.logDetail("recipe", "recipe.pack_recipes", "pot", cookingPotPack, "board", cuttingBoardPack);
        }

        // The warmup runs on enable and on the CraftEngine readiness pass, never on a reload, so its timing is
        // only reported by the message that carries it. Repeating it after a reload would present the enable
        // pass's numbers as if the reload had just produced them.
        String key = items > 0 && !warmupReported ? "plugin.content_summary_warmed" : "plugin.content_summary";
        Object[] full = key.endsWith("_warmed")
                ? concat(args, "warm_items", items, "warm_ms", warmupMillis)
                : args;

        boolean firstReport = lastReportedCounts == null;
        if (!firstReport && counts.equals(lastReportedCounts)) {
            I18n.logDetail("startup", key, full);
            return;
        }
        lastReportedCounts = counts;
        if (key.endsWith("_warmed")) {
            warmupReported = true;
        }
        if (firstReport) {
            // The one line a normal boot prints: versions, the counts that matter, the tool count and the enabled
            // marker. A later report (a reload, an addon republish) keeps its numbers under startup detail.
            I18n.logInfo(key, full);
        } else {
            I18n.logDetail("startup", key, full);
        }
    }

    /**
     * The four version fields for the summary line. CraftEngine's version can be unreadable, which the banner
     * writes as unknown, and the warning that goes with it is emitted by the plugin's enable path.
     */
    private String versionBanner() {
        return StartupVersionBanner.format(plugin.getPluginMeta().getVersion(), plugin.resolveCraftEngineVersion(),
                plugin.getServer().getVersion(), plugin.getPluginMeta().getAPIVersion());
    }

    private static Object[] concat(Object[] base, Object... extra) {
        Object[] out = new Object[base.length + extra.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }
}
