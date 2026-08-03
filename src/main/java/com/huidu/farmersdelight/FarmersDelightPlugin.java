package com.huidu.farmersdelight;

import com.huidu.farmersdelight.advancement.AddonAdvancementRegistry;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionMode;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.MushroomColonyBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockEntity;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.WildRiceBlockBehavior;
import com.huidu.farmersdelight.listener.AchievementListener;
import com.huidu.farmersdelight.listener.AutoTrayFurnitureListener;
import com.huidu.farmersdelight.listener.BackstabListener;
import com.huidu.farmersdelight.listener.BlockBreakListener;
import com.huidu.farmersdelight.listener.BlockPlaceListener;
import com.huidu.farmersdelight.listener.ChunkLoadListener;
import com.huidu.farmersdelight.listener.CraftEngineWatchdogListener;
import com.huidu.farmersdelight.listener.CropInteractProtectionListener;
import com.huidu.farmersdelight.listener.CuttingBoardDispenseListener;
import com.huidu.farmersdelight.listener.CuttingBoardInteractListener;
import com.huidu.farmersdelight.listener.FoodEatListener;
import com.huidu.farmersdelight.listener.HorseFeedTemptListener;
import com.huidu.farmersdelight.listener.PetFoodListener;
import com.huidu.farmersdelight.listener.RecipeDiscoveryListener;
import com.huidu.farmersdelight.listener.RicePlantListener;
import com.huidu.farmersdelight.listener.RichSoilHoeListener;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.listener.RugListener;
import com.huidu.farmersdelight.listener.SkilletAttackSoundListener;
import com.huidu.farmersdelight.listener.SkilletPlaceListener;
import com.huidu.farmersdelight.listener.StrawDropListener;
import com.huidu.farmersdelight.listener.TatamiBreakListener;
import com.huidu.farmersdelight.listener.UpperHalfLootRelayListener;
import com.huidu.farmersdelight.command.FarmersDelightCommand;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.config.CookingPotExperienceRewardConfig;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.config.RugConfig;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.config.StrawDropConfig;
import com.huidu.farmersdelight.compat.AuraSkillsHook;
import com.huidu.farmersdelight.compat.CraftEngineStateUsageMonitor;
import com.huidu.farmersdelight.effect.EffectListener;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeEditorGuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.loot.KnifeDropHandler;
import com.huidu.farmersdelight.BuildFlags;
import com.huidu.farmersdelight.manager.BuffBossbarManager;
import com.huidu.farmersdelight.manager.HandleManager;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.InteractionDebouncer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import com.huidu.farmersdelight.visual.ProxyItemDisplayManager;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class FarmersDelightPlugin extends JavaPlugin implements Listener {


    private static volatile FarmersDelightPlugin instance;
    private static volatile boolean enabled = false;
    
    private volatile boolean startupSyncCompleted = false;
    private volatile boolean datapackSyncQueued = false;
    private volatile boolean datapackRemovalQueued = false;
    private PluginTask pendingCraftEngineReloadTask;
    private PluginTask pendingDatapackReloadTask;
    private PluginTask pendingDatapackSyncRetryTask;
    private String pendingDatapackReloadReason;
    private String pendingDatapackSyncRetryReason;

    private SchedulerAdapter scheduler;
    private TickManager tickManager;
    private TrayManager trayManager;
    private HandleManager handleManager;
    private BuffBossbarManager buffBossbarManager;
    private StoveManager stoveManager;
    private SkilletManager skilletManager;
    private ItemDisplayManager itemDisplayManager;
    private KnifeDropHandler knifeDropHandler;
    private CookingPotRecipeManager cookingPotRecipeManager;
    private CuttingBoardRecipeManager cuttingBoardRecipeManager;
    private volatile com.huidu.farmersdelight.recipe.RecipeEditorStore recipeEditorStore;
    private BlockBreakListener blockBreakListener;
    private BlockPlaceListener blockPlaceListener;
    private StrawDropListener strawDropListener;
    private ChunkLoadListener chunkLoadListener;
    private RopeBlockListener ropeBlockListener;
    private RugListener rugListener;
    private FoodEatListener foodEatListener;
    private PetFoodListener petFoodListener;
    private HorseFeedTemptListener horseFeedTemptListener;
    private AchievementListener achievementListener;
    private EffectListener effectListener;
    private BackstabListener backstabListener;
    private final com.huidu.farmersdelight.config.ConfigBootstrap configBootstrap = new com.huidu.farmersdelight.config.ConfigBootstrap(this);
    // Collects the per-subsystem content counts into the single summary line a healthy boot prints.
    private final StartupSummary startupSummary = new StartupSummary(this);
    // Set by requestContentSummary and cleared by the task it schedules; written from whichever thread a recipe
    // manager republishes on and read on the next tick, so it must be volatile (R-CONC-002).
    private volatile boolean contentSummaryRequested;

    // Lazy-loaded, may be accessed concurrently by multiple region threads (awarding XP when collecting cooking pot results); uses volatile + double-checked locking,
    // consistent with recipeEditorStore.
    private volatile AuraSkillsHook auraSkillsHook;

    // volatile: reassigned on reload and read by region threads.
    private volatile HeatSourceConfig heatSourceConfig;
    // volatile: rebuilt on reload, read by region threads in RugListener (block-physics/break events).
    private volatile RugConfig rugConfig;
    private GuiConfig cookingPotGuiConfig;
    private Map<String, GuiConfig> customCookingPotGuiConfigs = Map.of();
    private volatile RecipeEditorGuiConfig recipeEditorGuiConfig;
    private YamlConfiguration guiConfig;
    private volatile StrawDropConfig strawDropConfig;
    private volatile PetFoodConfig petFoodConfig;
    private volatile ContainerReturnConfig containerReturnConfig;
    private volatile boolean backstabEnchantmentEnabled;
    private volatile CuttingBoardDisplayConfig cuttingBoardDisplayConfig;
    private volatile CuttingBoardDisplayConfig skilletDisplayConfig;
    private volatile CuttingBoardDisplayConfig stoveDisplayConfig;
    private volatile CookingPotExperienceRewardConfig cookingPotExperienceRewardConfig;
    private AdvancementManager advancementManager;
    // Addon-defined advancement tabs. Definitions persist across /fd reload; the registry survives the FD-tab
    // dispose/rebuild cycle, so it is created once and kept for the plugin's whole life.
    private AddonAdvancementRegistry addonAdvancementRegistry;
    private RecipeDiscoveryManager recipeDiscoveryManager;
    private boolean advancementsEnabled;
    // Master switch for the whole buff system (config buff.enabled). Written by the reload path from the
    // command thread and read by region/tick threads (effect ticker, buff feeds, api entry points), so it
    // must be volatile. buff.display.enabled remains a separate, narrower switch that only silences the
    // display channels while the effects keep running.
    private volatile boolean buffSystemEnabled = true;
    private boolean debugEnabled;
    private boolean showRecipeNameInProgressDisplay;
    private boolean cookingPotProgressDisplayEnabled = true;
    private double cookingPotProgressDisplayYOffset = 1.2D;
    private float cookingPotProgressDisplayScale = 0.5F;
    private double cookingPotProgressDisplayVisibilityDistanceSquared = 100.0D;
    private double cookingPotProgressDisplayLookDotThreshold = 0.95D;
    private int cookingPotProgressDisplayUpdateIntervalTicks = 8;
    private int cookingPotProgressDisplayDisableAboveActivePots = 512;
    private CuttingBoardInteractionMode cuttingBoardInteractionMode;
    private boolean hopperInteractionsEnabled;
    private boolean cookingPotHopperInteractionsEnabled;
    private boolean cuttingBoardHopperInteractionsEnabled;
    private boolean skilletHopperInteractionsEnabled;
    private boolean cookingPotPackContentsOnBreak;
    private boolean skilletConductorsAllowed;
    private float skilletDisplayScale = 0.5F;
    private double skilletDisplayYOffset = 0.1D;
    private double skilletDisplaySpread = 0.15D;
    private float stoveDisplayScale = 0.375F;
    private Set<String> knifeItemIds = Set.of();
    private Set<String> knifeTagIds = Set.of();
    private Set<String> debugCategories = Set.of();

    private final Object primaryLevelNameLock = new Object();
    private volatile boolean primaryLevelNameResolved = false;
    private volatile String cachedPrimaryLevelName;

    public static FarmersDelightPlugin getInstance() {
        return instance;
    }

    public static boolean isEnabled0() {
        return enabled;
    }

    private boolean areCraftEngineItemsReady() {
        // Probe whether CraftEngine has finished its item-load pass, not a single item id. CE parses items in a
        // deferred pass after its onEnable (only listeners are registered there), so isPluginEnabled would flip
        // true too early; and keying on one specific item breaks the moment that item is deleted or the pack's
        // namespace is changed. isAnyCustomItemLoaded checks the registry itself, surviving both.
        try {
            return ItemUtils.isAnyCustomItemLoaded();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void loadRecipeManagers(String logKey) {
        I18n.logDetail("recipe", logKey);
        cookingPotRecipeManager.loadRecipes();
        cuttingBoardRecipeManager.loadRecipes();
        // Recipe set changed: drop the discovery obtain-trigger index so it rebuilds against the new recipes.
        if (recipeDiscoveryManager != null) {
            recipeDiscoveryManager.invalidateIndex();
        }
    }

    private void loadRecipeManagersWhenReady(String logKey) {
        if (areCraftEngineItemsReady()) {
            loadRecipeManagers(logKey);
        }
        // Otherwise: silently defer; CraftEngineReloadEvent retries once after CE items finish loading.
    }

    public boolean isAdvancementsEnabled() {
        return advancementsEnabled;
    }

    /** Master switch for the buff system: custom buff effects, their ticker, every display channel and the
     *  /fd buff subcommand. False means no buff is applied, ticked or drawn anywhere. */
    public boolean isBuffSystemEnabled() {
        return buffSystemEnabled;
    }

    private void disableAdvancementSystem() {
        if (achievementListener != null) {
            HandlerList.unregisterAll(achievementListener);
            achievementListener = null;
        }
        if (advancementManager != null) {
            advancementManager.dispose();
        }
        advancementManager = null;
        if (addonAdvancementRegistry != null) {
            addonAdvancementRegistry.onSystemDown();
        }
        queueAdvancementDatapackRemoval(I18n.formatConsole("plugin.datapack_reason_remove_disabled_advancements"));
    }

    // Only build/refresh advancements after CraftEngine items finish loading, otherwise icon() falls back to
    // vanilla Material icons instead of CE items.
    private void refreshAdvancementSystemWhenReady(boolean reloading) {
        if (!advancementsEnabled || !getServer().getPluginManager().isPluginEnabled("UltimateAdvancementAPI")) {
            disableAdvancementSystem();
            return;
        }
        if (!areCraftEngineItemsReady()) {
            return; // CraftEngineReloadEvent retries this after CE items finish loading.
        }
        refreshAdvancementSystem(reloading);
    }

    /**
     * Rebuilds the rope and rug position indexes for chunks that were already loaded when their listeners
     * registered — those chunks never fire the load events the indexes are normally filled from, and in
     * practice they are the spawn area, which often never unloads. Both indexes decide whether a rope or rug
     * is there at all, so a chunk missing from them loses rope texture refreshes and leaves orphaned rug
     * furniture behind when the block under it goes.
     *
     * Gated on CraftEngine readiness like every other content-dependent startup step: CraftEngine fills its
     * world and furniture registries in a deferred pass after its own enable, so both sweeps find nothing when
     * they run before it and the CraftEngine readiness pass runs them instead. Both sweeps re-add entries
     * idempotently, so running them again on a later pass is harmless.
     */
    private void indexLoadedChunkContentWhenReady() {
        if (!areCraftEngineItemsReady()) {
            return;
        }
        if (ropeBlockListener != null) {
            ropeBlockListener.indexRopesInLoadedChunks();
        }
        if (rugListener != null) {
            rugListener.indexRugsInLoadedChunks();
        }
    }

    /** Runs #warmUp(String) only once CE items are loaded; otherwise defers to the CE-reload path. */
    private void warmUpWhenReady(String reason) {
        if (areCraftEngineItemsReady()) {
            warmUp(reason);
        }
    }

    /**
     * Prints the consolidated content summary once every count in it is meaningful. Before CraftEngine has
     * loaded its items the recipe, advancement and warmup counts are all still zero, so the summary is
     * skipped entirely and the CraftEngine readiness pass reports instead. A later pass whose counts are
     * unchanged is demoted to the startup detail channel by the summary itself.
     *
     * Public so the recipe managers can re-report after a republish they drive themselves, such as the
     * coalesced batch that follows addon recipe registration.
     */
    public void reportContentSummaryWhenReady() {
        if (areCraftEngineItemsReady()) {
            startupSummary.report();
        }
    }

    /**
     * Requests one content summary after the current tick's recipe republishes have all finished. Each recipe
     * manager coalesces its own republish independently, so two managers reacting to the same addon
     * registration would otherwise each report and the second would print a line the first had already made
     * stale. Collapsing the request here means N managers in one tick produce one summary.
     */
    public void requestContentSummary() {
        if (contentSummaryRequested) {
            return;
        }
        contentSummaryRequested = true;
        scheduler().runLater(() -> {
            contentSummaryRequested = false;
            reportContentSummaryWhenReady();
        }, 1L);
    }

    /**
     * Pre-builds FarmersDelight's CraftEngine item stacks and primes the GUI / behavior caches so the first
     * in-game interaction does not pay CraftEngine's one-time global item-build inits (ASM proxies, MiniMessage
     * setup) or a burst of cold item builds. Pure computation — no world/entity/region access — so it is safe on
     * whichever (global) thread this runs. Best-effort: any failure is logged and never blocks enable/reload.
     * Ends by firing com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent so addons warm their own.
     */
    private void warmUp(String reason) {
        try {
            long start = System.nanoTime();
            int items = com.huidu.farmersdelight.util.ItemUtils.warmItems("farmersdelight");
            com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior.warmAll();
            CookingPotGui.warm(this);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            // The item count and duration are produced here and nowhere else; the consolidated summary
            // reads them back once the rest of the counts are final.
            startupSummary.recordWarmup(items, ms);
            I18n.logDetail("startup", "plugin.warmup_done", "items", items, "ms", ms);
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING, I18n.formatConsole("plugin.warmup_failed"), t);
        }
        // Addons prime their own caches now that FD's are warm (see FarmersDelightWarmupEvent).
        org.bukkit.Bukkit.getPluginManager().callEvent(
                new com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent(reason));
    }

    private void refreshAdvancementSystem(boolean reloading) {
        if (!advancementsEnabled || !getServer().getPluginManager().isPluginEnabled("UltimateAdvancementAPI")) {
            disableAdvancementSystem();
            return;
        }

        try {
            if (advancementManager == null) {
                advancementManager = new AdvancementManager(this);
                advancementManager.load();
            } else if (reloading) {
                advancementManager.reload();
            }
        } catch (Throwable t) {
            // UltimateAdvancementAPI missing at runtime or version incompatible -- run without the advancement system.
            advancementManager = null;
            I18n.logWarning("advancement.award_failed", "id", "init", "error", String.valueOf(t.getMessage()));
            return;
        }

        if (achievementListener == null) {
            achievementListener = new AchievementListener();
            getServer().getPluginManager().registerEvents(achievementListener, this);
        }

        // Remove any legacy advancement datapacks to avoid their vanilla advancement tree duplicating the UAA tab.
        queueAdvancementDatapackRemoval(I18n.formatConsole("plugin.datapack_reason_remove_legacy_advancements"));

        // FD's own tab is up: (re)build any addon-registered tabs now that UAA + CraftEngine items are ready.
        getAddonAdvancementRegistry().onSystemReady();

        // A rebuild (/ce reload) recreates the UAA tab, which drops it from online clients. Re-show FD's own
        // tab to online players (no-op on first load — no one is online yet). Addon tabs re-sync in onSystemReady.
        advancementManager.resyncOnlinePlayers();
    }

    @Override
    public void onLoad() {
        instance = this;
        configBootstrap.ensureConfigDefaults();
        // ensureConfigDefaults has just guaranteed config.yml exists, so the debug switch is readable this
        // early and the load-phase detail lines below can be surfaced by their category like the rest.
        loadDebugFlags();
        I18n.init(this);
        new com.huidu.farmersdelight.resource.ResourceInstaller(this, getFile()).installCraftEngineResourcesOnce();
        com.huidu.farmersdelight.registry.BehaviorRegistrar.registerBlockBehaviors();
        com.huidu.farmersdelight.registry.BehaviorRegistrar.registerItemBehaviors();
        com.huidu.farmersdelight.registry.BehaviorRegistrar.registerFunctions();
        // Register the WorldGuard custom region flag here (onLoad): WG locks its FlagRegistry once it
        // enables, so this must run during the load phase. No-op if WorldGuard is absent.
        ProtectionCompat.registerFlags();
    }

    /** JVM-lifetime guard against /reload + hot disable. System properties survive plugin classloader
     *  recreation, so re-enabling within the same JVM session can be detected and refused. */
    private static final String RELOAD_GUARD_PROPERTY = "farmersdelight.enabled.in.this.jvm";
    private boolean enabledSuccessfully = false;

    // bStats plugin id from https://bstats.org (register the plugin there, then paste its numeric id here).
    // TODO: replace the placeholder with FarmersDelight's real bStats id before publishing.
    private static final int BSTATS_PLUGIN_ID = 32571;

    @Override
    public void onEnable() {
        if (System.getProperty(RELOAD_GUARD_PROPERTY) != null) {
            getLogger().severe(" ");
            getLogger().severe(" ");
            getLogger().severe("==================================================================");
            getLogger().severe(" PLEASE DO NOT /reload OR HOT-DISABLE FarmersDelight.");
            getLogger().severe(" ");
            getLogger().severe(" This plugin hooks deep into CraftEngine block behaviors, the");
            getLogger().severe(" scheduler, and per-chunk block-entity state. Re-enabling at");
            getLogger().severe(" runtime leaves stale tasks/listeners/lambdas bound to the old");
            getLogger().severe(" classloader, which crash randomly with NoClassDefFoundError.");
            getLogger().severe(" ");
            getLogger().severe(" To apply config changes: /stop then start the server again.");
            getLogger().severe("==================================================================");
            getLogger().severe(" ");
            getLogger().severe(" ");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        System.setProperty(RELOAD_GUARD_PROPERTY, "1");

        enabled = true;

        if (BuildFlags.DEBUG_TOOLS) {
            I18n.logWarning("plugin.debug_tools_build");
        }

        configBootstrap.ensureConfigDefaults();
        configBootstrap.migrateConfigKeys();
        // Before I18n.init and the Folia line below, both of which route through the startup detail
        // channel: logDetail can only promote them to INFO once these fields hold the configured
        // categories, and the full config load that used to be their only reader runs further down.
        loadDebugFlags();
        I18n.init(this);

        scheduler = new SchedulerAdapter(this);
        if (scheduler.isFolia()) {
            // Reported as a field of the startup config summary rather than its own line.
            I18n.logDetail("startup", "plugin.folia_scheduler");
        }

        // Build the protection facade over all installed land plugins (softdepends are enabled by now);
        // WorldGuard flags were already registered in onLoad. Non-WG land plugins gate via AntiGriefLib.
        ProtectionCompat.init(this);

        loadConfigs();
        logStartupSummary();

        knifeDropHandler = new KnifeDropHandler(this);
        knifeDropHandler.loadConfig();
        getServer().getPluginManager().registerEvents(knifeDropHandler, this);

        cookingPotRecipeManager = new CookingPotRecipeManager(this);

        cuttingBoardRecipeManager = new CuttingBoardRecipeManager(this);

        loadRecipeManagersWhenReady("plugin.loading_recipes");

        // If CraftEngine is disabled while the server keeps running, FD cannot function; disable ourselves
        // cleanly instead of throwing from every CraftEngine-bound task. See CraftEngineWatchdogListener.
        getServer().getPluginManager().registerEvents(new CraftEngineWatchdogListener(this), this);

        recipeDiscoveryManager = new RecipeDiscoveryManager(this);
        recipeDiscoveryManager.load();
        getServer().getPluginManager().registerEvents(new RecipeDiscoveryListener(this), this);
        // Periodic flush so unlocks survive a crash (no-op while unchanged, ~5 min). File write runs async,
        // off the Folia global region thread.
        scheduler().runRepeating(() -> {
            if (recipeDiscoveryManager != null) {
                scheduler().runAsync(() -> {
                    RecipeDiscoveryManager manager = recipeDiscoveryManager;
                    if (manager != null) {
                        manager.save();
                    }
                });
            }
        }, 6000L, 6000L);

        blockBreakListener = new BlockBreakListener();
        getServer().getPluginManager().registerEvents(blockBreakListener, this);

        blockPlaceListener = new BlockPlaceListener();
        getServer().getPluginManager().registerEvents(blockPlaceListener, this);
        BlockPlaceListener.reloadMushroomSupportCache(this);
        getServer().getPluginManager().registerEvents(new SkilletPlaceListener(), this);
        getServer().getPluginManager().registerEvents(new SkilletAttackSoundListener(), this);
        getServer().getPluginManager().registerEvents(new CuttingBoardInteractListener(), this);
        getServer().getPluginManager().registerEvents(new CuttingBoardDispenseListener(this), this);

        strawDropListener = new StrawDropListener(this);
        getServer().getPluginManager().registerEvents(strawDropListener, this);

        getServer().getPluginManager().registerEvents(new RicePlantListener(this), this);
        getServer().getPluginManager().registerEvents(new UpperHalfLootRelayListener(), this);

        // Awards master_chef criteria when eating FD dishes, and applies comfort/nourishment effects when enabled in config.
        foodEatListener = new FoodEatListener(this);
        getServer().getPluginManager().registerEvents(foodEatListener, this);

        petFoodListener = new PetFoodListener(this);
        getServer().getPluginManager().registerEvents(petFoodListener, this);
        horseFeedTemptListener = new HorseFeedTemptListener(this);
        getServer().getPluginManager().registerEvents(horseFeedTemptListener, this);
        horseFeedTemptListener.start();
        effectListener = new EffectListener(this);
        getServer().getPluginManager().registerEvents(effectListener, this);
        effectListener.start();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(
                new com.huidu.farmersdelight.api.util.PluginManagerGuard(getName()), this);

        tickManager = new TickManager(this);
        tickManager.start();

        itemDisplayManager = new ProxyItemDisplayManager(this);
        if (itemDisplayManager.isAvailable()) {
            I18n.logDetail("startup", "plugin.proxy_display_enabled");
        } else {
            I18n.logWarning("plugin.proxy_display_unavailable");
        }

        stoveManager = new StoveManager(this);
        skilletManager = new SkilletManager(this);
        trayManager = new TrayManager(this);
        handleManager = new HandleManager(this);
        buffBossbarManager = new BuffBossbarManager(this);
        buffBossbarManager.applyConfig(getFirstConfigSection("buff.display", "bossbar"), buffSystemEnabled);
        com.huidu.farmersdelight.effect.EffectManager.applyBossbarStyles(
                getFirstConfigSection("buff.display.styles", "bossbar.styles"));
        getServer().getPluginManager().registerEvents(buffBossbarManager, this);
        buffBossbarManager.start();
        for (org.bukkit.World world : getServer().getWorlds()) {
            handleManager.trackWorld(world);
        }
        getServer().getPluginManager().registerEvents(new AutoTrayFurnitureListener(this), this);

        ropeBlockListener = new RopeBlockListener(this);
        getServer().getPluginManager().registerEvents(ropeBlockListener, this);
        getServer().getPluginManager().registerEvents(new TatamiBreakListener(), this);
        rugListener = new RugListener(this);
        getServer().getPluginManager().registerEvents(rugListener, this);
        getServer().getPluginManager().registerEvents(new RichSoilHoeListener(this), this);
        getServer().getPluginManager().registerEvents(new CropInteractProtectionListener(), this);

        // 背刺附魔：检测已安装的附魔插件，有冲突则禁用自己的版本
        backstabListener = new BackstabListener(this);
        if (backstabEnchantmentEnabled && isEnchantmentPluginPresent()) {
            backstabEnchantmentEnabled = false;
            I18n.logWarning("enchantment.backstabbing.auto_disabled");
        }
        backstabListener.setEnabled(backstabEnchantmentEnabled);
        getServer().getPluginManager().registerEvents(backstabListener, this);

        // 小刀附魔台过滤器：移除精准采集（mining_loot 标签带来的副作用）
        getServer().getPluginManager().registerEvents(
                new com.huidu.farmersdelight.listener.KnifeEnchantFilter(this), this);

        // 背刺附魔数据包——独立于战利品注入数据包
        com.huidu.farmersdelight.listener.EnchantmentDatapackInstaller enchantInstaller =
                new com.huidu.farmersdelight.listener.EnchantmentDatapackInstaller(this);
        enchantInstaller.installToAllWorlds();
        getServer().getPluginManager().registerEvents(enchantInstaller, this);

        // Composting chances, furnace burn times and villager / wandering trader trades (world-data section).
        getServer().getPluginManager().registerEvents(
                new com.huidu.farmersdelight.listener.worlddata.ComposterListener(this), this);
        getServer().getPluginManager().registerEvents(
                new com.huidu.farmersdelight.listener.worlddata.FurnaceFuelListener(), this);
        getServer().getPluginManager().registerEvents(
                new com.huidu.farmersdelight.listener.worlddata.VillagerTradeListener(), this);

        indexLoadedChunkContentWhenReady();

        chunkLoadListener = new ChunkLoadListener(this);
        getServer().getPluginManager().registerEvents(chunkLoadListener, this);
        chunkLoadListener.loadAlreadyLoadedChunks();

        com.huidu.farmersdelight.loot.LootDatapackInstaller lootInstaller =
                new com.huidu.farmersdelight.loot.LootDatapackInstaller(this);
        lootInstaller.installToAllWorlds();
        getServer().getPluginManager().registerEvents(lootInstaller, this);

        refreshAdvancementSystemWhenReady(false);
        // Warm CE item/GUI/behavior caches now IF CE is already up (FD enabled after CraftEngine). When CE
        // loads after FD, onCraftEngineReload runs the warmup instead — the readiness gate makes them exclusive.
        warmUpWhenReady("enable");

        scheduler.run(() -> startupSyncCompleted = true);

        FarmersDelightCommand commandHandler = new FarmersDelightCommand(this);
        org.bukkit.command.Command base = new org.bukkit.command.Command("farmersdelight",
                "Main FarmersDelight command", "/farmersdelight [recipe|reload|help]", List.of("fd")) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return commandHandler.onCommand(sender, this, label, args);
            }
            @Override
            public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                return commandHandler.onTabComplete(sender, this, alias, args);
            }
        };
        getServer().getCommandMap().register("farmersdelight", "FarmersDelight", base);

        // Both the content counts and the CraftEngine state figures are only meaningful once CraftEngine has
        // finished loading. When FarmersDelight enables first (the usual order) neither is reported here and
        // the CraftEngine readiness pass does it instead; the readiness gate keeps the two exclusive.
        reportContentSummaryWhenReady();
        if (areCraftEngineItemsReady()) {
            // The reason is spliced into "... usage after {reason}", so it needs the phrase form, not the
            // startup_config label the config summary is titled with. Mirrors craftengine_reload_reason.
            CraftEngineStateUsageMonitor.logRealStateUsage(this, I18n.formatConsole("plugin.startup_reason"));
        }

        // PlaceholderAPI bridge — registers iff PAPI is loaded so HUD plugins (BetterHud, MythicHud,
        // etc.) can read every CustomBuffRegistry entry per player. Soft-dep, no-op when absent.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new com.huidu.farmersdelight.compatibility.PlaceholderApiHook(this).register();
                I18n.logDetail("startup", "plugin.papi_bridge_registered");
            } catch (Throwable t) {
                I18n.logWarning("plugin.papi_bridge_failed", "error", t.getMessage());
            }
        }

        // bStats metrics: anonymous server/plugin stats. Opt out globally via plugins/bStats/config.yml.
        // bStats is bundled un-relocated (stays at org.bstats); Bukkit plugin classloaders are isolated so
        // the package cannot clash with another plugin's copy. Disable bStats' relocation self-check, which
        // would otherwise throw because the package still starts with org.bstats.
        if (BSTATS_PLUGIN_ID > 0) {
            try {
                System.setProperty("bstats.relocatecheck", "false");
                new org.bstats.bukkit.Metrics(this, BSTATS_PLUGIN_ID);
            } catch (Throwable t) {
                getLogger().warning("Failed to start bStats metrics: " + t.getMessage());
            }
        }

        // The server already prints "Enabling FarmersDelight vX" for us; the startup config and content
        // summary lines carry everything a second "enabled" line would not.
        I18n.logDetail("startup", "plugin.enabled");
        enabledSuccessfully = true;
    }

    @Override
    public void onDisable() {
        enabled = false;
        boolean folia = scheduler != null && scheduler.isFolia();

        // Runtime disable warning: CraftEngine + per-chunk block-entity state still hold references
        // to FD listeners, scheduler tasks, and block behaviors. Once this classloader closes, any
        // late-bound lambda / event delivery into FD throws NoClassDefFoundError. We can't unwind
        // CE's registrations, so do the best-effort cleanup below and tell the admin to restart.
        // Re-enabling FD in the same JVM is refused by onEnable's reload-guard system property, so
        // the worst case is "FD blocks misbehave until /stop", not double-registration chaos.
        if (enabledSuccessfully && !getServer().isStopping()) {
            getLogger().severe(" ");
            getLogger().severe("==================================================================");
            getLogger().severe(" FarmersDelight was disabled at runtime (e.g. via /reload or a");
            getLogger().severe(" plugin manager). CraftEngine still holds references to FD block");
            getLogger().severe(" behaviors and block entities, so further interactions may log");
            getLogger().severe(" NoClassDefFoundError. Restart the server (/stop) at your earliest");
            getLogger().severe(" convenience. Re-enabling FD in this JVM is refused.");
            getLogger().severe("==================================================================");
            getLogger().severe(" ");
        }

        // MUST be first: stop event delivery before tearing down listeners' state. Vanilla code
        // (piston ticks, neighbour updates, scheduled chunk tasks) keeps firing during onDisable,
        // and PaperPluginClassLoader is already draining — late-bound lambda metafactory calls
        // from listener code will hit NoClassDefFoundError. Pulling listeners off the bus first
        // makes the rest of the shutdown order independent of vanilla event timing.
        runDisableStep("plugin.disable_step_unregister_listeners", () -> HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this));

        runDisableStep("plugin.disable_step_stop_tick_manager", () -> {
            if (tickManager != null) {
                tickManager.stop();
                tickManager = null;
            }
        });

        runDisableStep("plugin.disable_step_save_recipe_discovery", () -> {
            if (recipeDiscoveryManager != null) {
                recipeDiscoveryManager.save();
            }
        });

        runDisableStep("plugin.disable_step_save_player_buffs", () -> {
            // A runtime disable (plugin manager, CE watchdog cascade) fires no quit events, so the
            // quit-time buff save never runs; persist every online player's buff state before the
            // effect listener stop below wipes the live maps and unregisters the buffs. On a normal
            // stop players were already kicked (and saved on quit), making this a no-op re-write.
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                try {
                    com.huidu.farmersdelight.api.buff.CustomBuffRegistry.saveAll(player);
                } catch (Throwable ignored) {
                    // Per-player isolation; on Folia a cross-region PDC write may fail — best effort.
                }
            }
        });

        runDisableStep("plugin.disable_step_stop_effect_listener", () -> {
            if (effectListener != null) {
                effectListener.stop();
                effectListener = null;
            }
        });

        runDisableStep("plugin.disable_step_stop_horse_feed_tempt_listener", () -> {
            if (horseFeedTemptListener != null) {
                horseFeedTemptListener.stop();
                horseFeedTemptListener = null;
            }
        });
        runDisableStep("plugin.disable_step_close_cooking_pot_guis", CookingPotGui::cleanupAll);
        runDisableStep("plugin.disable_step_close_recipe_view_guis", () -> {
            RecipeViewGui.cleanupAll();
            // The editor listener is unregistered below via HandlerList; reset its flag so a soft restart
            // re-registers a fresh listener.
            com.huidu.farmersdelight.gui.editor.RecipeEditorListener.reset();
            // Same for RecipeBookListener: reset its flag, otherwise after a soft restart click/drag events
            // are no longer cancelled and items can be duped.
            com.huidu.farmersdelight.gui.recipebook.RecipeBookListener.reset();
        });
        runDisableStep("plugin.disable_step_save_block_data", this::saveAllBlockData);

        runDisableStep("plugin.disable_step_clear_placement_cache", this::cleanupPlacementCache);
        runDisableStep("plugin.disable_step_clear_interaction_debounce_cache", this::cleanupInteractionDebouncer);

        runDisableStep("plugin.disable_step_shutdown_chunk_loader", () -> {
            if (chunkLoadListener != null) {
                chunkLoadListener.shutdown();
            }
        });

        runDisableStep("plugin.disable_step_cleanup_trays", () -> {
            if (trayManager != null) {
                trayManager.cleanupAll();
                trayManager = null;
            }
            if (handleManager != null) {
                handleManager.cleanupAll();
                handleManager = null;
            }
        });

        runDisableStep("plugin.disable_step_cleanup_stoves", () -> {
            if (stoveManager != null) {
                stoveManager.cleanup();
                stoveManager = null;
            }
        });

        runDisableStep("plugin.disable_step_cleanup_skillets", () -> {
            if (skilletManager != null) {
                skilletManager.cleanup();
                skilletManager = null;
            }
        });

        runDisableStep("plugin.disable_step_cleanup_bossbars", () -> {
            if (buffBossbarManager != null) {
                buffBossbarManager.stop();
                buffBossbarManager = null;
            }
        });

        runDisableStep("plugin.disable_step_cleanup_item_displays", () -> {
            if (itemDisplayManager != null) {
                itemDisplayManager.cleanup();
                itemDisplayManager = null;
            }
        });

        runDisableStep("plugin.disable_step_cleanup_cooking_pot_block_entities", () -> CookingPotBlockBehavior.cleanupAll(!folia));
        runDisableStep("plugin.disable_step_cleanup_cutting_board_block_entities", () -> CuttingBoardBlockBehavior.cleanupAll(!folia));
        runDisableStep("plugin.disable_step_cleanup_stove_block_entities", StoveCookingBlockBehavior::cleanupAll);
        runDisableStep("plugin.disable_step_cleanup_skillet_block_entities", SkilletBlockBehavior::cleanupAll);
        runDisableStep("plugin.disable_step_cleanup_tall_crops", TallCropBlockBehavior::cleanupAll);
        runDisableStep("plugin.disable_step_cleanup_mushroom_colonies", MushroomColonyBehavior::cleanupAll);
        runDisableStep("plugin.disable_step_cleanup_wild_rice_blocks", WildRiceBlockBehavior::cleanupAll);
        runDisableStep("plugin.disable_step_clear_stove_recipe_cache", StoveCookingBlockBehavior::clearRecipeCache);
        runDisableStep("plugin.disable_step_clear_skillet_recipe_cache", this::clearLegacySkilletRecipeCache);

        runDisableStep("plugin.disable_step_cancel_pending_tasks", () -> {
            if (pendingCraftEngineReloadTask != null) {
                pendingCraftEngineReloadTask.cancel();
                pendingCraftEngineReloadTask = null;
            }
            if (pendingDatapackReloadTask != null) {
                pendingDatapackReloadTask.cancel();
                pendingDatapackReloadTask = null;
            }
            if (pendingDatapackSyncRetryTask != null) {
                pendingDatapackSyncRetryTask.cancel();
                pendingDatapackSyncRetryTask = null;
            }
        });

        runDisableStep("plugin.disable_step_shutdown_scheduler", () -> {
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler = null;
            }
        });

        knifeDropHandler = null;
        cookingPotRecipeManager = null;
        cuttingBoardRecipeManager = null;
        blockBreakListener = null;
        strawDropListener = null;
        foodEatListener = null;
        auraSkillsHook = null;

        heatSourceConfig = null;
        rugConfig = null;
        cookingPotGuiConfig = null;
        cookingPotExperienceRewardConfig = null;
        strawDropConfig = null;

        if (advancementManager != null) {
            advancementManager.dispose();
        }
        advancementManager = null;

        I18n.logInfo("plugin.disabled");
        I18n.cleanup();
    }

    private void runDisableStep(String stepKey, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, I18n.formatConsole("plugin.disable_step_failed",
                    "step", I18n.formatConsole(stepKey)), throwable);
        }
    }

    @SuppressWarnings("deprecation")
    private void clearLegacySkilletRecipeCache() {
        SkilletBlockEntity.clearRecipeCache();
    }

    private void cleanupPlacementCache() {
        BlockPlaceListener.cleanup();
    }

    private void cleanupInteractionDebouncer() {
        InteractionDebouncer.cleanup();
    }

    private void saveAllBlockData() {
        if (scheduler != null && scheduler.isFolia()) {
            CookingPotBlockBehavior.markAllBlockEntitiesDirty();
            CuttingBoardBlockBehavior.markAllBlockEntitiesDirty();
        } else {
            CookingPotBlockBehavior.saveAllData();
            CuttingBoardBlockBehavior.saveAllData();
        }
        SkilletBlockBehavior.saveAllData();
        StoveCookingBlockBehavior.saveAllData();

        flushCraftEngineWorldData();
    }

    private void flushCraftEngineWorldData() {
        BukkitWorldManager worldManager = BukkitWorldManager.instance();
        if (worldManager == null) {
            return;
        }

        for (org.bukkit.World world : getServer().getWorlds()) {
            try {
                CEWorld ceWorld = worldManager.getWorld(world.getUID());
                if (ceWorld != null) {
                    ceWorld.saveChunks();
                    ceWorld.saveSettings();
                }
            } catch (Throwable throwable) {
                getLogger().log(Level.WARNING, I18n.formatConsole("plugin.craftengine_world_flush_failed",
                        "world", world.getName()), throwable);
            }
        }
    }

    // ignoreCancelled: WorldUnloadEvent is cancellable, and this handler passivates + drops skillet/stove
    // entries — running it for an already-cancelled unload would needlessly strip a live world's visuals.
    // A cancellation AFTER this priority still self-heals: the entry-creation flush hooks re-hydrate each
    // block from its controller snapshot on the first interaction.
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        saveWorldBlockData(event.getWorld());

        UUID worldId = event.getWorld().getUID();
        CookingPotBlockBehavior.cleanupWorld(worldId);
        CuttingBoardBlockBehavior.cleanupWorld(worldId);
        SkilletBlockBehavior.cleanupWorld(worldId);
        StoveCookingBlockBehavior.cleanupWorld(worldId);

        if (trayManager != null) {
            trayManager.cleanupWorld(worldId);
        }
        if (handleManager != null) {
            handleManager.cleanupWorld(worldId);
        }
        if (itemDisplayManager != null) {
            // Remove all proxy display entities for the unloaded world, so stale entries don't linger in the displays map
            // until a chunk unload event that may never fire.
            itemDisplayManager.cleanupWorld(worldId);
        }
    }

    private void saveWorldBlockData(org.bukkit.World world) {
        if (world == null) {
            return;
        }

        for (BlockPosKey posKey : CookingPotBlockBehavior.getAllBlockEntities(world).keySet()) {
            CookingPotBlockBehavior.saveBlockEntityData(world, posKey);
        }
        for (BlockPosKey posKey : CuttingBoardBlockBehavior.getAllBlockEntities(world).keySet()) {
            CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
        }
        if (skilletManager != null) {
            skilletManager.saveWorldData(world);
        }
        if (stoveManager != null) {
            stoveManager.saveWorldData(world);
        }
    }

    @org.bukkit.event.EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (handleManager != null) {
            handleManager.trackWorld(event.getWorld());
        }
        if (!startupSyncCompleted) {
            return;
        }
        org.bukkit.World primaryWorld = getPrimaryWorld();
        if (primaryWorld == null || !primaryWorld.getUID().equals(event.getWorld().getUID())) {
            return;
        }
        // Advancements are sent via datapack; no per-world datapack sync.
    }

    private void queueDatapackReload(String reason) {
        pendingDatapackReloadReason = reason;
        if (pendingDatapackReloadTask != null && !pendingDatapackReloadTask.isCancelled()) {
            return;
        }
        pendingDatapackReloadTask = scheduler.runLater(() -> {
            pendingDatapackReloadTask = null;
            String reloadReason = pendingDatapackReloadReason != null
                    ? pendingDatapackReloadReason
                    : I18n.formatConsole("plugin.datapack_reason_apply_advancement_changes");
            pendingDatapackReloadReason = null;
            reloadServerDataPacks(reloadReason);
        }, 10L);
    }

    private void reloadServerDataPacks(String reason) {
        if (scheduler != null && scheduler.isFolia()) {
            I18n.logWarning("plugin.datapack_reload_skipped_folia", "reason", reason);
            return;
        }
        try {
            I18n.logInfo("plugin.datapack_reload", "reason", reason);
            getServer().reloadData();
        } catch (UnsupportedOperationException e) {
            I18n.logWarning("plugin.datapack_reload_skipped_unsupported", "reason", reason);
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, I18n.formatConsole("plugin.datapack_reload_failed",
                    "reason", reason), throwable);
        }
    }

    private void queueDatapackSync(String reason) {
        if (!advancementsEnabled) {
            return;
        }
        if (datapackRemovalQueued) {
            scheduleDatapackSyncRetry(reason);
            return;
        }
        if (datapackSyncQueued) {
            return;
        }
        org.bukkit.World primaryWorld = getPrimaryWorld();
        if (primaryWorld == null) {
            return;
        }
        datapackSyncQueued = true;
        String worldName = primaryWorld.getName();
        Path datapackRoot = primaryWorld.getWorldFolder().toPath().resolve("datapacks").resolve("advancements");
        scheduler.runAsync(() -> {
            boolean updated = false;
            try {
                updated = new com.huidu.farmersdelight.advancement.AdvancementDatapackInstaller(this)
                        .sync(datapackRoot, worldName);
            } finally {
                datapackSyncQueued = false;
            }
            if (updated) {
                queueDatapackReload(reason);
            }
        });
    }

    private void scheduleDatapackSyncRetry(String reason) {
        pendingDatapackSyncRetryReason = reason;
        if (pendingDatapackSyncRetryTask != null && !pendingDatapackSyncRetryTask.isCancelled()) {
            return;
        }

        pendingDatapackSyncRetryTask = scheduler.runLater(() -> {
            pendingDatapackSyncRetryTask = null;
            String retryReason = pendingDatapackSyncRetryReason;
            pendingDatapackSyncRetryReason = null;
            if (retryReason != null) {
                queueDatapackSync(retryReason);
            }
        }, 20L);
    }

    private void queueAdvancementDatapackRemoval(String reason) {
        if (datapackRemovalQueued) {
            return;
        }
        org.bukkit.World primaryWorld = getPrimaryWorld();
        if (primaryWorld == null) {
            return;
        }
        datapackRemovalQueued = true;
        Path datapackRoot = primaryWorld.getWorldFolder().toPath().resolve("datapacks").resolve("advancements");
        scheduler.runAsync(() -> {
            boolean removed = false;
            try {
                removed = new com.huidu.farmersdelight.advancement.AdvancementDatapackInstaller(this)
                        .remove(datapackRoot);
            } finally {
                datapackRemovalQueued = false;
            }
            if (removed) {
                queueDatapackReload(reason);
            }
        });
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (pendingCraftEngineReloadTask != null && !pendingCraftEngineReloadTask.isCancelled()) {
            return;
        }

        pendingCraftEngineReloadTask = scheduler.runLater(() -> {
            pendingCraftEngineReloadTask = null;
            I18n.reload();
            I18n.logDetail("startup", "plugin.craftengine_reload");
            refreshAfterCraftEngineReload();
            loadRecipeManagersWhenReady("plugin.refreshing_recipes_after_ce");
            // CE items are now loaded: (re)build advancements so icons use CE items.
            refreshAdvancementSystemWhenReady(true);
            // Rebuild the item/GUI/behavior caches CE reload just invalidated so the next interaction is cheap.
            warmUpWhenReady("reload");
            // CE's world and furniture registries are populated now, so the loaded-chunk sweeps that found
            // nothing during enable can fill the rope and rug indexes.
            indexLoadedChunkContentWhenReady();
            // Last point of the pass: recipes, advancements and the warmup counts are all final here, and the
            // block-state pool has its real occupancy, so this is where both summary lines belong.
            reportContentSummaryWhenReady();
            CraftEngineStateUsageMonitor.logRealStateUsage(this, I18n.formatConsole("plugin.craftengine_reload_reason"));
        }, 1L);
    }

    public void reloadRecipesWhenReady(String reason) {
        loadRecipeManagersWhenReady(reason);
    }

    private void refreshAfterCraftEngineReload() {
        com.huidu.farmersdelight.util.ItemUtils.clearItemCache();
        com.huidu.farmersdelight.util.SoundUtils.clearCache();
        RecipeViewGui.clearConfigCache();
        com.huidu.farmersdelight.gui.recipebook.RecipeBookGui.clearConfigCache();
        StoveCookingBlockBehavior.clearRecipeCache();
        clearLegacySkilletRecipeCache();
        BlockPlaceListener.reloadMushroomSupportCache(this);

        if (stoveManager != null) {
            stoveManager.reloadConfig();
            stoveManager.reloadRecipeCache();
        }
        if (tickManager != null) {
            tickManager.reloadConfig();
        }
        if (itemDisplayManager instanceof ProxyItemDisplayManager proxyItemDisplayManager) {
            proxyItemDisplayManager.reload();
        }
        CuttingBoardBlockBehavior.refreshDisplayEntities();
        if (skilletManager != null) {
            skilletManager.reloadConfig();
            skilletManager.reloadRecipeCache();
        }
    }

    public void reloadConfigs() {
        reloadAll();
    }

    /** Shared reload body for reloadAll / reloadMainConfigOnly: config defaults, cache clears,
     * and every manager reload. The only differences the callers layer on are whether language files reload
     * (reloadLanguages) and whether recipe reload + the reload event follow. */
    private void reloadCommon(boolean reloadLanguages) {
        configBootstrap.ensureConfigDefaults();
        reloadConfig();
        configBootstrap.migrateConfigKeys();
        boolean previousAdvancementsEnabled = advancementsEnabled;
        loadConfigs();
        if (backstabListener != null) {
            backstabListener.setEnabled(backstabEnchantmentEnabled);
        }
        if (reloadLanguages) {
            I18n.reload();
        }
        com.huidu.farmersdelight.util.ItemUtils.clearItemCache();
        com.huidu.farmersdelight.util.SoundUtils.clearCache();
        RecipeViewGui.clearConfigCache();
        com.huidu.farmersdelight.gui.recipebook.RecipeBookGui.clearConfigCache();
        StoveCookingBlockBehavior.clearRecipeCache();
        clearLegacySkilletRecipeCache();
        BlockPlaceListener.reloadMushroomSupportCache(this);

        if (knifeDropHandler != null) {
            knifeDropHandler.loadConfig(false);
        }
        if (trayManager != null) {
            trayManager.reload();
        }
        if (handleManager != null) {
            handleManager.reload();
        }
        if (stoveManager != null) {
            stoveManager.reloadConfig();
            stoveManager.reloadRecipeCache();
        }
        if (tickManager != null) {
            tickManager.reloadConfig();
        }
        if (itemDisplayManager instanceof ProxyItemDisplayManager proxyItemDisplayManager) {
            proxyItemDisplayManager.reload();
        }
        CuttingBoardBlockBehavior.refreshDisplayEntities();
        if (skilletManager != null) {
            skilletManager.reloadConfig();
            skilletManager.reloadRecipeCache();
        }
        if (buffBossbarManager != null) {
            buffBossbarManager.applyConfig(getFirstConfigSection("buff.display", "bossbar"), buffSystemEnabled);
            com.huidu.farmersdelight.effect.EffectManager.applyBossbarStyles(
                    getFirstConfigSection("buff.display.styles", "bossbar.styles"));
        }
        // The buff ticker is armed on demand, so a reload that switches the system back on has to re-arm it
        // for players who still hold a buff, and a reload that switches it off has to stop the running pass.
        if (effectListener != null) {
            effectListener.applySystemEnabled(buffSystemEnabled);
        }
        if (foodEatListener != null) {
            foodEatListener.reload();
        }
        if (recipeDiscoveryManager != null) {
            recipeDiscoveryManager.reloadConfig();
        }
        if (horseFeedTemptListener != null) {
            horseFeedTemptListener.reload();
        }
        if (previousAdvancementsEnabled != advancementsEnabled) {
            refreshAdvancementSystem(true);
        }
    }

    public void reloadAll() {
        reloadCommon(true);
        reloadRecipesWhenReady("plugin.reloading_recipes");
        // The per-type recipe line is on the recipe detail channel, so the reloaded counts would otherwise
        // never reach the operator who just edited a recipe file. The summary dedupes on its counts digest,
        // so a reload that changed nothing stays silent.
        reportContentSummaryWhenReady();

        org.bukkit.Bukkit.getPluginManager().callEvent(
                new com.huidu.farmersdelight.api.event.FarmersDelightReloadEvent("reloadAll"));
        I18n.logInfo("plugin.configuration_reloaded");
    }

    public void reloadMainConfigOnly() {
        reloadCommon(false);
        I18n.logInfo("plugin.main_configuration_reloaded");
    }

    public void reloadGuiConfig() {
        configBootstrap.ensureConfigDefaults();
        guiConfig = loadGuiConfig();
        ConfigurationSection cookingPotSection = guiConfig.getConfigurationSection("cooking-pot-gui");
        cookingPotGuiConfig = cookingPotSection != null
                ? GuiConfig.fromConfig(cookingPotSection)
                : GuiConfig.createDefault();
        customCookingPotGuiConfigs = loadCustomCookingPotGuiConfigs(guiConfig);
        recipeEditorGuiConfig = RecipeEditorGuiConfig.fromConfig(guiConfig);
        RecipeViewGui.clearConfigCache();
        com.huidu.farmersdelight.gui.recipebook.RecipeBookGui.clearConfigCache();
        CookingPotGui.closeAllOpenGuis();
        RecipeViewGui.closeAllOpenGuis();
        I18n.logInfo("plugin.gui_configuration_reloaded", "file", "gui.yml");
    }

    public void reloadLanguageFiles() {
        I18n.reload();
        // GUI item names/lore come from language files and are cached, so invalidate those caches
        // and close open GUIs to force a rebuild in the new language.
        RecipeViewGui.clearConfigCache();
        com.huidu.farmersdelight.gui.recipebook.RecipeBookGui.clearConfigCache();
        CookingPotGui.closeAllOpenGuis();
        RecipeViewGui.closeAllOpenGuis();
        I18n.logInfo("plugin.language_files_reloaded");
    }

    public void reloadRecipeFiles() {
        refreshAfterCraftEngineReload();
        reloadRecipesWhenReady("plugin.reloading_recipes");
        // Same reason as reloadAll: the reloaded per-type counts are the only evidence the edited files
        // actually parsed, and the summary suppresses itself when they are unchanged.
        reportContentSummaryWhenReady();
        I18n.logInfo("plugin.recipe_files_reloaded");
    }

    public void reloadAdvancements() {
        refreshAdvancementSystem(true);
        I18n.logInfo("plugin.advancement_data_reloaded");
    }

    /**
     * Reads the debug switch and its category list into the fields logDetail consults. Separated from the
     * rest of loadConfigs because several demoted startup lines are emitted before the main config load
     * runs, and they can only reach the console through their debug category if these two fields are
     * already populated. Called from onLoad (after the config file is ensured on disk) and from both
     * paths that run the config bootstrap: plugin enable and the shared reload body.
     *
     * Reading only needs config.yml to exist on disk, which ensureConfigDefaults guarantees, so this is
     * safe at every call site including onLoad. No key migration touches the debug section, so running it
     * before migrateConfigKeys reads the same values as running it after.
     */
    private void loadDebugFlags() {
        debugEnabled = getConfig().getBoolean("debug", false)
                || getConfig().getBoolean("debug.enabled", false);
        debugCategories = getConfig().getStringList("debug.categories").stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void loadConfigs() {
        advancementsEnabled = getConfig().getBoolean("advancements.enabled", true);
        // No legacy path: buff.enabled is new, and a config that predates it has the buff system on.
        buffSystemEnabled = getConfigBoolean(true, "buff.enabled");
        // Mirror the switch into the addon-facing registry so its entry points can degrade to no-ops
        // without reaching back through the plugin singleton from an addon thread.
        com.huidu.farmersdelight.api.buff.CustomBuffRegistry.setSystemEnabled(buffSystemEnabled);
        // 背刺附魔：自动检测附魔插件，有冲突则默认关，用户可手动启用
        backstabEnchantmentEnabled = getConfig().getBoolean("enchantments.backstabbing.enabled", true);
        // Re-read on every reload so a debug switch edited in config.yml takes effect; the enable path
        // has already read it once, earlier, for the startup lines that precede this method.
        loadDebugFlags();
        knifeItemIds = getConfigStringList("knife-items.items", "drops.knife-items.items", "knife-config.items").stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        knifeTagIds = getConfigStringList("knife-items.tags", "drops.knife-items.tags", "knife-config.tags").stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(s -> s.startsWith("#") ? s.substring(1) : s)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (knifeTagIds.isEmpty()) {
            knifeTagIds = Set.of(Constants.TAG_KNIVES.toLowerCase(Locale.ROOT));
        }

        ConfigurationSection heatSourceSection = getConfig().getConfigurationSection("heat-sources");
        // R-CONC-002 safe publication: populate a local instance fully, then assign the volatile field
        // once. Region-thread readers (cooking-pot / skillet heat checks) must never observe a half-filled
        // config while /fd reload mutates it — assign-once gives them a happens-before edge to full state.
        HeatSourceConfig newHeatSourceConfig = new HeatSourceConfig();
        HeatSourceConfig.setLogger(getLogger());
        newHeatSourceConfig.loadDefaults();
        if (heatSourceSection != null) {
            newHeatSourceConfig.loadFromConfig(heatSourceSection);
        }
        heatSourceConfig = newHeatSourceConfig;

        // Rug underlying-block config lives beside the other FarmersDelight CraftEngine data
        // (plugins/CraftEngine/resources/farmersdelight/rugs.yml), not in this plugin's config.yml, so
        // admins tune all rug config in one place. Same R-CONC-002 safe-publication as heatSourceConfig:
        // RugListener reads it from region-thread block events.
        RugConfig newRugConfig = new RugConfig();
        RugConfig.setLogger(getLogger());
        newRugConfig.loadDefaults();
        ConfigurationSection rugSection = loadRugConfigSection();
        if (rugSection != null) {
            newRugConfig.loadFromConfig(rugSection);
        }
        rugConfig = newRugConfig;

        guiConfig = loadGuiConfig();
        ConfigurationSection cookingPotSection = guiConfig.getConfigurationSection("cooking-pot-gui");
        cookingPotGuiConfig = cookingPotSection != null
                ? GuiConfig.fromConfig(cookingPotSection)
                : GuiConfig.createDefault();
        customCookingPotGuiConfigs = loadCustomCookingPotGuiConfigs(guiConfig);
        recipeEditorGuiConfig = RecipeEditorGuiConfig.fromConfig(guiConfig);

        // R-CONC-002 safe publication (same as heatSourceConfig above): each is read from region-thread
        // events (grass-break straw / pet-feed / cooking-pot container) and rebuilt on /fd reload from the
        // global thread — populate a local, then assign the field once so readers never see partial state.
        ConfigurationSection strawDropSection = getFirstConfigSection("drops.straw", "straw-drops");
        StrawDropConfig newStrawDropConfig = new StrawDropConfig();
        newStrawDropConfig.loadDefaults();
        if (strawDropSection != null) {
            newStrawDropConfig.loadFromConfig(strawDropSection);
        }
        strawDropConfig = newStrawDropConfig;

        ConfigurationSection petFoodSection = getConfig().getConfigurationSection("pet-foods");
        PetFoodConfig newPetFoodConfig = new PetFoodConfig();
        if (petFoodSection != null) {
            newPetFoodConfig.loadFromConfig(petFoodSection);
        }
        petFoodConfig = newPetFoodConfig;

        ConfigurationSection containerReturnSection = getFirstConfigSection("container-returns", "cooking-pot.container-returns");
        ContainerReturnConfig newContainerReturnConfig = new ContainerReturnConfig();
        newContainerReturnConfig.loadDefaults();
        if (containerReturnSection != null) {
            newContainerReturnConfig.loadFromConfig(containerReturnSection);
        }
        containerReturnConfig = newContainerReturnConfig;

        CuttingBoardDisplayConfig newCuttingBoardDisplayConfig = new CuttingBoardDisplayConfig();
        newCuttingBoardDisplayConfig.loadFromConfig(getConfig().getConfigurationSection("cutting-board"));
        cuttingBoardDisplayConfig = newCuttingBoardDisplayConfig;
        CuttingBoardDisplayConfig newSkilletDisplayConfig = createSkilletDisplayConfig();
        newSkilletDisplayConfig.loadFromConfig(getFirstConfigSection("skillet.display", "display-visuals.skillet"));
        skilletDisplayConfig = newSkilletDisplayConfig;
        CuttingBoardDisplayConfig newStoveDisplayConfig = createStoveDisplayConfig();
        newStoveDisplayConfig.loadFromConfig(getFirstConfigSection("stove.display", "display-visuals.stove"));
        stoveDisplayConfig = newStoveDisplayConfig;

        cookingPotProgressDisplayEnabled = getConfigBoolean(true,
                "cooking-pot.progress-display.enabled",
                "cooking-pot-progress-display.enabled");
        showRecipeNameInProgressDisplay = getConfigBoolean(false,
                "cooking-pot.progress-display.show-recipe-name",
                "cooking-pot-progress-display.show-recipe-name");
        cookingPotProgressDisplayYOffset = getConfigDouble(1.2D,
                "cooking-pot.progress-display.y-offset",
                "cooking-pot-progress-display.y-offset");
        cookingPotProgressDisplayScale = Math.max(0.01F, (float) getConfigDouble(0.5D,
                "cooking-pot.progress-display.scale",
                "cooking-pot-progress-display.scale"));
        double progressDisplayDistance = Math.max(1.0D, getConfigDouble(10.0D,
                "cooking-pot.progress-display.visibility-distance",
                "cooking-pot-progress-display.visibility-distance"));
        cookingPotProgressDisplayVisibilityDistanceSquared = progressDisplayDistance * progressDisplayDistance;
        cookingPotProgressDisplayLookDotThreshold = Math.max(-1.0D, Math.min(1.0D, getConfigDouble(0.95D,
                "cooking-pot.progress-display.look-dot-threshold",
                "cooking-pot-progress-display.look-dot-threshold")));
        cookingPotProgressDisplayUpdateIntervalTicks = Math.max(1, getConfigInt(8,
                "cooking-pot.progress-display.update-interval-ticks",
                "cooking-pot-progress-display.update-interval-ticks"));
        cookingPotProgressDisplayDisableAboveActivePots = Math.max(0, getConfigInt(512,
                "cooking-pot.progress-display.disable-above-active-pots",
                "cooking-pot-progress-display.disable-above-active-pots"));
        cookingPotPackContentsOnBreak = getConfig().getBoolean("cooking-pot.pack-contents-on-break", true);
        cookingPotExperienceRewardConfig = new CookingPotExperienceRewardConfig();
        cookingPotExperienceRewardConfig.loadFromConfig(
                getFirstConfigSection("experience-reward", "cooking-pot.experience-reward"));
        cuttingBoardInteractionMode = CuttingBoardInteractionMode.parse(
                getConfig().getString("cutting-board.interaction-mode", "stacking")
        );
        hopperInteractionsEnabled = getConfig().getBoolean("hopper-interactions.enabled", true);
        cookingPotHopperInteractionsEnabled = getConfigBoolean(true,
                "cooking-pot.hopper-interactions",
                "hopper-interactions.cooking-pot");
        cuttingBoardHopperInteractionsEnabled = getConfigBoolean(true,
                "cutting-board.hopper-interactions",
                "hopper-interactions.cutting-board");
        skilletHopperInteractionsEnabled = getConfigBoolean(true,
                "skillet.hopper-interactions",
                "hopper-interactions.skillet");
        skilletConductorsAllowed = getConfigBoolean(true,
                "skillet.heat.allow-conductors",
                "heat-sources.skillet.allow-conductors");
        skilletDisplayScale = skilletDisplayConfig.getDefaultUniformScale(0.5F);
        skilletDisplayYOffset = skilletDisplayConfig.getDefaultOffset().y();
        skilletDisplaySpread = skilletDisplayConfig.getItemSpread();
        stoveDisplayScale = stoveDisplayConfig.getDefaultUniformScale(0.375F);
        com.huidu.farmersdelight.listener.worlddata.WorldDataConfig.reload(this);
    }

    /**
     * True when the admin's file itself sets the path.
     *
     * Bukkit attaches the jar's config.yml to getConfig() as the default configuration, and plain
     * contains(path) reports a path as present when only that default has it. Every getter below walks a
     * chain of paths for a setting that moved, so with plain contains the current path would always match
     * and the older paths would never be consulted: an admin whose file still uses the old name would
     * silently get the bundled default instead of the value they set. Passing ignoreDefault=true asks only
     * the loaded file, which is what makes the fallback chain mean anything. Same reason the config merge
     * uses contains(key, true).
     */
    private boolean configFileHas(String path) {
        return getConfig().contains(path, true);
    }

    /**
     * The section at the first of the given paths the admin's file actually has, or null when it has none of
     * them. Callers pass the current path first and every path the section previously lived at after it.
     */
    public ConfigurationSection getFirstConfigSection(String... paths) {
        for (String path : paths) {
            if (!configFileHas(path)) {
                continue;
            }
            ConfigurationSection section = getConfig().getConfigurationSection(path);
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    public boolean getConfigBoolean(boolean defaultValue, String... paths) {
        for (String path : paths) {
            if (configFileHas(path)) {
                return getConfig().getBoolean(path, defaultValue);
            }
        }
        return defaultValue;
    }

    public double getConfigDouble(double defaultValue, String... paths) {
        for (String path : paths) {
            if (configFileHas(path)) {
                return getConfig().getDouble(path, defaultValue);
            }
        }
        return defaultValue;
    }

    public int getConfigInt(int defaultValue, String... paths) {
        for (String path : paths) {
            if (configFileHas(path)) {
                return getConfig().getInt(path, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * String list read through the same current-path-first, old-path-fallback chain as the scalar getters,
     * for a setting whose path moved. Returns the list at the first path the file actually has, and an empty
     * list when it has none of them.
     */
    public List<String> getConfigStringList(String... paths) {
        for (String path : paths) {
            if (configFileHas(path)) {
                return getConfig().getStringList(path);
            }
        }
        return List.of();
    }

    public boolean isShowRecipeNameInProgressDisplay() {
        return showRecipeNameInProgressDisplay;
    }

    public boolean isCookingPotProgressDisplayEnabled() {
        return cookingPotProgressDisplayEnabled;
    }

    public double getCookingPotProgressDisplayYOffset() {
        return cookingPotProgressDisplayYOffset;
    }

    public float getCookingPotProgressDisplayScale() {
        return cookingPotProgressDisplayScale;
    }

    public double getCookingPotProgressDisplayVisibilityDistanceSquared() {
        return cookingPotProgressDisplayVisibilityDistanceSquared;
    }

    public double getCookingPotProgressDisplayLookDotThreshold() {
        return cookingPotProgressDisplayLookDotThreshold;
    }

    public int getCookingPotProgressDisplayUpdateIntervalTicks() {
        return cookingPotProgressDisplayUpdateIntervalTicks;
    }

    public int getCookingPotProgressDisplayDisableAboveActivePots() {
        return cookingPotProgressDisplayDisableAboveActivePots;
    }

    public boolean isCuttingBoardOffhandInteractionsAllowed() {
        return cuttingBoardInteractionMode == CuttingBoardInteractionMode.OFFHAND;
    }

    public boolean isCuttingBoardStackingEnabled() {
        return cuttingBoardInteractionMode == CuttingBoardInteractionMode.STACKING;
    }

    public CuttingBoardInteractionMode getCuttingBoardInteractionMode() {
        return cuttingBoardInteractionMode;
    }

    /** When true, only items with a cutting-board recipe (or tools) may be placed on the board. */
    public boolean isCuttingBoardRecipeOnlyPlacement() {
        return getConfig().getBoolean("cutting-board.recipe-only-placement", false);
    }

    /** When true, a dispenser facing a cutting board uses the dispensed item as a cutting tool on the stored
     *  item (the mod's cutting-board dispenser behavior). */
    public boolean isCuttingBoardDispenserBehaviorEnabled() {
        return getConfig().getBoolean("cutting-board.dispenser-behavior", true);
    }

    public boolean isCookingPotHopperInteractionsEnabled() {
        return hopperInteractionsEnabled && cookingPotHopperInteractionsEnabled;
    }

    public boolean isCookingPotPackContentsOnBreak() {
        return cookingPotPackContentsOnBreak;
    }

    public boolean shouldDropCookingPotVanillaExperience() {
        return getCookingPotExperienceRewardConfig().shouldDropVanillaExperience();
    }

    public boolean shouldAwardCookingPotAuraSkillsExperience() {
        return getCookingPotExperienceRewardConfig().shouldAwardAuraSkillsExperience();
    }

    public void awardCookingPotAuraSkillsExperience(Player player, double baseExperience) {
        if (player == null || baseExperience <= 0.0D || !shouldAwardCookingPotAuraSkillsExperience()) {
            return;
        }
        for (CookingPotExperienceRewardConfig.AuraSkillsReward reward
                : getCookingPotExperienceRewardConfig().auraSkillsRewards()) {
            if (!shouldApplyReward(reward.chance())) {
                continue;
            }
            var xpDefinition = reward.toXpDefinition(baseExperience);
            if (xpDefinition.amount() > 0.0D) {
                getAuraSkillsHook().addXp(player, xpDefinition);
            }
        }
    }

    public void callCookingPotExperienceEvent(Player player, org.bukkit.inventory.ItemStack result, double baseExperience) {
        if (player == null) {
            return;
        }
        getServer().getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                player.getUniqueId(),
                player.getName(),
                "cooking_pot",
                result,
                (float) Math.max(0.0D, baseExperience)
        ));
    }

    private boolean shouldApplyReward(double chance) {
        return chance >= 1.0D || (chance > 0.0D && ThreadLocalRandom.current().nextDouble() < chance);
    }

    public AuraSkillsHook getAuraSkillsHook() {
        AuraSkillsHook hook = auraSkillsHook;
        if (hook == null) {
            synchronized (this) {
                hook = auraSkillsHook;
                if (hook == null) {
                    hook = new AuraSkillsHook(this);
                    auraSkillsHook = hook;
                }
            }
        }
        return hook;
    }

    private CookingPotExperienceRewardConfig getCookingPotExperienceRewardConfig() {
        if (cookingPotExperienceRewardConfig == null) {
            cookingPotExperienceRewardConfig = new CookingPotExperienceRewardConfig();
        }
        return cookingPotExperienceRewardConfig;
    }

    public boolean isCuttingBoardHopperInteractionsEnabled() {
        return hopperInteractionsEnabled && cuttingBoardHopperInteractionsEnabled;
    }

    public boolean isSkilletHopperInteractionsEnabled() {
        return hopperInteractionsEnabled && skilletHopperInteractionsEnabled;
    }

    public boolean isSkilletConductorsAllowed() {
        return skilletConductorsAllowed;
    }

    public boolean isKnifeItemId(String itemId) {
        return itemId != null && knifeItemIds.contains(itemId.toLowerCase(Locale.ROOT));
    }

    public Set<String> getKnifeItemIds() {
        return knifeItemIds;
    }

    public Set<String> getKnifeTagIds() {
        return knifeTagIds;
    }

    private YamlConfiguration loadGuiConfig() {
        Path guiPath = getDataFolder().toPath().resolve("gui.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.notExists(guiPath)) {
            if (isDebugEnabled("config")) {
                I18n.logInfo("plugin.gui_missing_defaults");
            }
            return yaml;
        }
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(guiPath), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (Exception e) {
            I18n.logWarning("plugin.gui_load_failed", "error", e.getMessage());
        }
        return yaml;
    }

    public BukkitCraftEngine getCraftEngine() {
        return BukkitCraftEngine.instance();
    }

    public SchedulerAdapter scheduler() {
        if (scheduler == null) {
            throw new IllegalStateException("Scheduler is not available");
        }
        return scheduler;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public boolean isDebugEnabled(String category) {
        if (!debugEnabled) {
            return false;
        }
        if (category == null || category.isBlank()) {
            return false;
        }

        String normalized = category.trim().toLowerCase();
        return debugCategories.contains("*")
                || debugCategories.contains("all")
                || debugCategories.contains(normalized);
    }

    public KnifeDropHandler getKnifeDrops() {
        if (knifeDropHandler == null) {
            throw new IllegalStateException("Plugin is not enabled");
        }
        return knifeDropHandler;
    }

    // Null-tolerant accessors for the startup summary, which reads the managers while enable is still in
    // progress and must report a zero count rather than throw when one is not constructed yet.
    KnifeDropHandler getKnifeDropsOrNull() {
        return knifeDropHandler;
    }

    CookingPotRecipeManager getCookingPotRecipesOrNull() {
        return cookingPotRecipeManager;
    }

    CuttingBoardRecipeManager getCuttingBoardRecipesOrNull() {
        return cuttingBoardRecipeManager;
    }

    HorseFeedTemptListener getHorseFeedTemptListener() {
        return horseFeedTemptListener;
    }

    public CookingPotRecipeManager getCookingPotRecipes() {
        if (cookingPotRecipeManager == null) {
            throw new IllegalStateException("Plugin is not enabled");
        }
        return cookingPotRecipeManager;
    }

    public CuttingBoardRecipeManager getCuttingBoardRecipes() {
        if (cuttingBoardRecipeManager == null) {
            throw new IllegalStateException("Plugin is not enabled");
        }
        return cuttingBoardRecipeManager;
    }

    public com.huidu.farmersdelight.recipe.RecipeEditorStore getRecipeEditorStore() {
        com.huidu.farmersdelight.recipe.RecipeEditorStore store = this.recipeEditorStore;
        if (store == null) {
            synchronized (this) {
                store = this.recipeEditorStore;
                if (store == null) {
                    store = new com.huidu.farmersdelight.recipe.RecipeEditorStore(this);
                    this.recipeEditorStore = store;
                }
            }
        }
        return store;
    }

    public HeatSourceConfig getHeatSourceConfig() {
        if (heatSourceConfig == null) {
            heatSourceConfig = new HeatSourceConfig();
        }
        return heatSourceConfig;
    }

    /** Never null: falls back to a defaults-only config if #loadConfigs() hasn't run yet. */
    public RugConfig getRugConfig() {
        RugConfig config = rugConfig;
        if (config == null) {
            config = new RugConfig();
            config.loadDefaults();
            rugConfig = config;
        }
        return config;
    }

    /**
     * Loads rugs.yml from FarmersDelight's CraftEngine resource folder
     * (plugins/CraftEngine/resources/farmersdelight/rugs.yml) — it sits at the pack root, a
     * sibling of configuration/, so CraftEngine (which only scans configuration/) never
     * tries to parse it, yet admins find it right beside the other rug definitions. Returns null if the
     * file is absent (first startup before the bundled release, or deleted) — callers keep the defaults.
     */
    private ConfigurationSection loadRugConfigSection() {
        Path pluginsFolder = getDataFolder().toPath().getParent();
        if (pluginsFolder == null) return null;
        File rugsFile = pluginsFolder.resolve(com.huidu.farmersdelight.resource.ResourceInstaller.CRAFTENGINE_RESOURCE_TARGET).resolve("rugs.yml").toFile();
        if (!rugsFile.isFile()) return null;
        return YamlConfiguration.loadConfiguration(rugsFile);
    }

    public GuiConfig getCookingPotGuiConfig() {
        if (cookingPotGuiConfig == null) {
            cookingPotGuiConfig = GuiConfig.createDefault();
        }
        return cookingPotGuiConfig;
    }

    public GuiConfig getCookingPotGuiConfig(String customId) {
        if (customId == null || customId.isBlank()) {
            return getCookingPotGuiConfig();
        }
        GuiConfig customConfig = customCookingPotGuiConfigs.get(customId);
        return customConfig != null ? customConfig : getCookingPotGuiConfig();
    }

    public RecipeEditorGuiConfig getRecipeEditorGuiConfig() {
        RecipeEditorGuiConfig config = recipeEditorGuiConfig;
        if (config == null) {
            config = RecipeEditorGuiConfig.fromConfig(guiConfig);
            recipeEditorGuiConfig = config;
        }
        return config;
    }

    public ConfigurationSection getRecipeViewGuiSection() {
        if (guiConfig == null) {
            guiConfig = loadGuiConfig();
        }
        return guiConfig.getConfigurationSection("recipe-view-gui");
    }

    public ConfigurationSection getRecipeBookGuiSection() {
        if (guiConfig == null) {
            guiConfig = loadGuiConfig();
        }
        return guiConfig.getConfigurationSection("recipe-book-gui");
    }

    private Map<String, GuiConfig> loadCustomCookingPotGuiConfigs(YamlConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("cooking-pot-guis");
        if (section == null) {
            return Map.of();
        }
        Map<String, GuiConfig> configs = new HashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection guiSection = section.getConfigurationSection(id);
            if (guiSection == null) {
                continue;
            }
            try {
                configs.put(id, GuiConfig.fromConfig(guiSection));
            } catch (Exception e) {
                I18n.logWarning("plugin.custom_cooking_pot_gui_load_failed", "id", id, "error", e.getMessage());
            }
        }
        return Collections.unmodifiableMap(configs);
    }

    public StrawDropConfig getStrawDropConfig() {
        if (strawDropConfig == null) {
            strawDropConfig = new StrawDropConfig();
        }
        return strawDropConfig;
    }

    public PetFoodConfig getPetFoodConfig() {
        if (petFoodConfig == null) {
            petFoodConfig = new PetFoodConfig();
            PetFoodConfig.setLogger(getLogger());
        }
        return petFoodConfig;
    }

    public ContainerReturnConfig getContainerReturnConfig() {
        if (containerReturnConfig == null) {
            containerReturnConfig = new ContainerReturnConfig();
        }
        return containerReturnConfig;
    }

    public CuttingBoardDisplayConfig getCuttingBoardDisplayConfig() {
        if (cuttingBoardDisplayConfig == null) {
            cuttingBoardDisplayConfig = new CuttingBoardDisplayConfig();
        }
        return cuttingBoardDisplayConfig;
    }

    public CuttingBoardDisplayConfig getSkilletDisplayConfig() {
        if (skilletDisplayConfig == null) {
            skilletDisplayConfig = createSkilletDisplayConfig();
        }
        return skilletDisplayConfig;
    }

    public CuttingBoardDisplayConfig getStoveDisplayConfig() {
        if (stoveDisplayConfig == null) {
            stoveDisplayConfig = createStoveDisplayConfig();
        }
        return stoveDisplayConfig;
    }

    public TrayManager getTrayManager() {
        return trayManager;
    }

    public HandleManager getHandleManager() {
        return handleManager;
    }

    public BuffBossbarManager getBuffBossbarManager() {
        return buffBossbarManager;
    }

    public StoveManager getStoveManager() {
        return stoveManager;
    }

    public SkilletManager getSkilletManager() {
        return skilletManager;
    }

    /** Gathers every proxy display id still referenced by a live block owner (stove / skillet / cutting
     *  board / cooking-pot text). /fd cleanup removes only displays NOT in this set — orphans —
     *  so legitimate, in-use visuals are never touched. */
    public java.util.Set<Integer> collectLiveDisplayIds() {
        java.util.Set<Integer> liveIds = new java.util.HashSet<>();
        if (stoveManager != null) {
            stoveManager.collectLiveDisplayIds(liveIds);
        }
        if (skilletManager != null) {
            skilletManager.collectLiveDisplayIds(liveIds);
        }
        com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior.collectLiveDisplayIds(liveIds);
        com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior.collectLiveDisplayIds(liveIds);
        return liveIds;
    }

    public AdvancementManager getAdvancementManager() {
        return advancementManager;
    }

    /** Lazily-created registry of addon advancement tabs (see FarmersDelightAdvancements). Never null;
     * synchronized so concurrent first calls create only one instance. */
    public synchronized AddonAdvancementRegistry getAddonAdvancementRegistry() {
        if (addonAdvancementRegistry == null) {
            addonAdvancementRegistry = new AddonAdvancementRegistry(this);
        }
        return addonAdvancementRegistry;
    }

    public FoodEatListener getFoodEatListener() {
        return foodEatListener;
    }

    public RecipeDiscoveryManager getRecipeDiscoveryManager() {
        return recipeDiscoveryManager;
    }

    public ItemDisplayManager getItemDisplayManager() {
        return itemDisplayManager;
    }

    public float getSkilletDisplayScale() {
        return skilletDisplayScale;
    }

    public double getSkilletDisplayYOffset() {
        return skilletDisplayYOffset;
    }

    public double getSkilletDisplaySpread() {
        return skilletDisplaySpread;
    }

    public float getStoveDisplayScale() {
        return stoveDisplayScale;
    }

    private CuttingBoardDisplayConfig createSkilletDisplayConfig() {
        return new CuttingBoardDisplayConfig(new CuttingBoardDisplayConfig.DisplayOverride(
                null,
                CuttingBoardDisplayConfig.DisplayStyle.AUTO,
                new Vector3f(0.0F, 0.1F, 0.0F),
                null,
                null,
                new Vector3f(0.5F, 0.5F, 0.5F)
        ), 0.15F);
    }

    private CuttingBoardDisplayConfig createStoveDisplayConfig() {
        return new CuttingBoardDisplayConfig(new CuttingBoardDisplayConfig.DisplayOverride(
                null,
                CuttingBoardDisplayConfig.DisplayStyle.AUTO,
                new Vector3f(0.0F, 0.0F, 0.0F),
                null,
                null,
                new Vector3f(0.375F, 0.375F, 0.375F)
        ), 0.0F);
    }

    private void logStartupSummary() {
        logConfigSummary(I18n.formatConsole("plugin.startup_config"));
    }

    private void logConfigSummary(String label) {
        I18n.logInfo("plugin.config_summary",
                "label", label,
                "scheduler", scheduler != null && scheduler.isFolia() ? "folia" : "bukkit",
                "mode", cuttingBoardInteractionMode.configKey(),
                "hopper", hopperInteractionsEnabled,
                "cooking_pot_hopper", cookingPotHopperInteractionsEnabled,
                "cutting_board_hopper", cuttingBoardHopperInteractionsEnabled,
                "skillet_hopper", skilletHopperInteractionsEnabled,
                "advancements", advancementsEnabled);
    }

    public TickManager getTickManager() {
        return tickManager;
    }

    private org.bukkit.World getPrimaryWorld() {
        List<org.bukkit.World> worlds = getServer().getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }
        String configuredLevelName = getConfiguredPrimaryLevelName();
        if (configuredLevelName != null && !configuredLevelName.isBlank()) {
            org.bukkit.World configuredWorld = getServer().getWorld(configuredLevelName);
            if (configuredWorld != null) {
                return configuredWorld;
            }
            for (org.bukkit.World world : worlds) {
                if (configuredLevelName.equals(world.getName())) {
                    return world;
                }
            }
        }
        for (org.bukkit.World world : worlds) {
            if (world.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                return world;
            }
        }
        return worlds.getFirst();
    }

    private String getConfiguredPrimaryLevelName() {
        // Cached: reads server.properties at most once.
        if (primaryLevelNameResolved) {
            return cachedPrimaryLevelName;
        }
        synchronized (primaryLevelNameLock) {
            if (primaryLevelNameResolved) {
                return cachedPrimaryLevelName;
            }
            cachedPrimaryLevelName = readConfiguredPrimaryLevelName();
            primaryLevelNameResolved = true;
            return cachedPrimaryLevelName;
        }
    }

    private String readConfiguredPrimaryLevelName() {
        Path serverProperties = getServer().getWorldContainer().toPath().resolve("server.properties");
        if (!Files.isRegularFile(serverProperties)) {
            return null;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(serverProperties);
             InputStreamReader reader = new InputStreamReader(input)) {
            properties.load(reader);
            return properties.getProperty("level-name");
        } catch (IOException e) {
            getLogger().fine(I18n.formatConsole("plugin.server_properties_read_failed", "error", e.getMessage()));
            return null;
        }
    }

    /**
     * 检测是否已安装可能冲突的附魔插件。
     * 这些插件通常有自己的背刺/增伤附魔，避免重复注册。
     */
    private static boolean isEnchantmentPluginPresent() {
        String[] knownPlugins = {
            "EcoEnchants", "AdvancedEnchantments", "ExcellentEnchants",
            "EnchantsSquared", "AeEnchants", "Zenchantments",
            "ElementalEnchants", "EnchantmentSolution"
        };
        for (String name : knownPlugins) {
            if (org.bukkit.Bukkit.getPluginManager().getPlugin(name) != null) {
                return true;
            }
        }
        return false;
    }

}
