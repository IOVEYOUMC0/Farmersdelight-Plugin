package com.huidu.farmersdelight;

import com.huidu.farmersdelight.advancement.AddonAdvancementRegistry;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionHandler;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionMode;
import com.huidu.farmersdelight.block.behavior.BlockBehaviorConfigs;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.MushroomColonyBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.WildRiceBlockBehavior;
import com.huidu.farmersdelight.listener.AchievementListener;
import com.huidu.farmersdelight.listener.BackstabListener;
import com.huidu.farmersdelight.listener.BlockBreakListener;
import com.huidu.farmersdelight.listener.BlockPlaceListener;
import com.huidu.farmersdelight.listener.ChunkLoadListener;
import com.huidu.farmersdelight.listener.CraftEngineWatchdogListener;
import com.huidu.farmersdelight.listener.CropInteractProtectionListener;
import com.huidu.farmersdelight.listener.CuttingBoardDispenseListener;
import com.huidu.farmersdelight.listener.CuttingBoardInteractListener;
import com.huidu.farmersdelight.listener.EnchantmentDatapackInstaller;
import com.huidu.farmersdelight.listener.TagDatapackInstaller;
import com.huidu.farmersdelight.listener.FoodEatListener;
import com.huidu.farmersdelight.listener.HorseFeedTemptListener;
import com.huidu.farmersdelight.listener.KnifeEnchantFilter;
import com.huidu.farmersdelight.listener.PetFoodListener;
import com.huidu.farmersdelight.listener.RecipeDiscoveryListener;
import com.huidu.farmersdelight.listener.RicePlantListener;
import com.huidu.farmersdelight.listener.RichSoilHoeListener;
import com.huidu.farmersdelight.listener.RottenTomatoListener;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.listener.SkilletPlaceListener;
import com.huidu.farmersdelight.listener.StrawDropListener;
import com.huidu.farmersdelight.listener.TatamiBreakListener;
import com.huidu.farmersdelight.tool.ToolAttackListener;
import com.huidu.farmersdelight.command.FarmersDelightCommand;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.config.CookingPotExperienceRewardConfig;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.config.HeatSourceConfig;
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
import com.huidu.farmersdelight.manager.BuffBossbarManager;
import com.huidu.farmersdelight.manager.HandleManager;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
import com.huidu.farmersdelight.recipe.SpecialRecipeLoader;
import com.huidu.farmersdelight.recipe.SpecialRecipeRegistry;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
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
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class FarmersDelightPlugin extends JavaPlugin implements Listener {

    // After a rebuild (/ce reload, /fd reload) the immediate advancement re-show can be dropped by a client
    // still re-applying CraftEngine's resource pack; force a second re-send after this many ticks.
    private static final long ADVANCEMENT_RESYNC_DELAY_TICKS = 20L;

    private static volatile FarmersDelightPlugin instance;
    private static volatile boolean enabled = false;
    
    private volatile boolean startupSyncCompleted = false;
    private volatile boolean datapackSyncQueued = false;
    private volatile boolean datapackRemovalQueued = false;
    // The rope position index survives CraftEngine reloads. Rebuilding every loaded chunk after each reload
    // creates an unbounded region-task burst, so only run the recovery scan once after CE first becomes ready.
    private final AtomicBoolean loadedChunkContentIndexStarted = new AtomicBoolean();
    private final AtomicBoolean contentWarmupCompleted = new AtomicBoolean();
    private PluginTask pendingCraftEngineReloadTask;
    private final AtomicLong craftEngineReloadGeneration = new AtomicLong();
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
    private SpecialRecipeRegistry specialRecipeRegistry;
    private volatile com.huidu.farmersdelight.recipe.RecipeEditorStore recipeEditorStore;
    private BlockBreakListener blockBreakListener;
    private BlockPlaceListener blockPlaceListener;
    private StrawDropListener strawDropListener;
    private ChunkLoadListener chunkLoadListener;
    private RopeBlockListener ropeBlockListener;
    private FoodEatListener foodEatListener;
    private PetFoodListener petFoodListener;
    private HorseFeedTemptListener horseFeedTemptListener;
    private AchievementListener achievementListener;
    private EffectListener effectListener;
    private BackstabListener backstabListener;
    private KnifeEnchantFilter knifeEnchantFilter;
    private EnchantmentDatapackInstaller enchantmentDatapackInstaller;
    private com.huidu.farmersdelight.listener.DamageTypeDatapackInstaller damageTypeDatapackInstaller;
    private TagDatapackInstaller tagDatapackInstaller;
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
    private GuiConfig cookingPotGuiConfig;
    private Map<String, GuiConfig> customCookingPotGuiConfigs = Map.of();
    private volatile RecipeEditorGuiConfig recipeEditorGuiConfig;
    private YamlConfiguration guiConfig;
    private volatile YamlConfiguration dropsConfig;
    private volatile StrawDropConfig strawDropConfig;
    private volatile PetFoodConfig petFoodConfig;
    private volatile ContainerReturnConfig containerReturnConfig;
    private volatile EnchantmentSettings enchantmentSettings = EnchantmentSettings.defaults();
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
    // Reload-written on the reload/command thread, read on world-region threads (including the retrieval
    // right-click path in CuttingBoardBlockBehavior) — volatile for the happens-before edge.
    private volatile float cuttingBoardFailVolume = Constants.CUTTING_BOARD_FAIL_VOLUME;
    private volatile float cuttingBoardFailPitch = Constants.CUTTING_BOARD_FAIL_PITCH;
    private final List<CuttingBoardInteractionHandler> cuttingBoardInteractionHandlers = new CopyOnWriteArrayList<>();
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

    private void indexLoadedChunkContentWhenReady() {
        if (!areCraftEngineItemsReady()) {
            return;
        }
        RopeBlockListener listener = ropeBlockListener;
        if (listener != null && loadedChunkContentIndexStarted.compareAndSet(false, true)) {
            listener.indexRopesInLoadedChunks();
        }
    }

    private void warmUpWhenReady(String reason) {
        if (areCraftEngineItemsReady()) {
            warmUp(reason);
        }
    }

    public void reportContentSummaryWhenReady() {
        if (areCraftEngineItemsReady()) {
            startupSummary.report();
        }
    }

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

    private void warmUp(String reason) {
        try {
            long start = System.nanoTime();
            int items = com.huidu.farmersdelight.util.ItemUtils.warmItems("farmersdelight");
            com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior.warmAll();
            CookingPotGui.warm(this);
            warmRecipeIngredientIcons();
            long ms = (System.nanoTime() - start) / 1_000_000L;
            // The item count and duration are produced here and nowhere else; the consolidated summary
            // reads them back once the rest of the counts are final.
            startupSummary.recordWarmup(items, ms);
            I18n.logDetail("startup", "plugin.warmup_done", "items", items, "ms", ms);
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING, I18n.formatConsole("plugin.warmup_failed"), t);
        }
        contentWarmupCompleted.set(true);
        // Addons prime their own caches now that FD's are warm (see FarmersDelightWarmupEvent).
        org.bukkit.Bukkit.getPluginManager().callEvent(
                new com.huidu.farmersdelight.api.event.FarmersDelightWarmupEvent(reason));
    }

    private void warmRecipeIngredientIcons() {
        if (cookingPotRecipeManager != null) {
            for (var recipe : cookingPotRecipeManager.getAllRecipes()) {
                for (var ingredient : recipe.getIngredients()) {
                    com.huidu.farmersdelight.gui.RecipeIngredientIcons.resolveIngredientOptions(ingredient);
                }
            }
        }
        if (cuttingBoardRecipeManager != null) {
            for (var recipe : cuttingBoardRecipeManager.getRecipes().values()) {
                if (recipe.getInput() != null) {
                    com.huidu.farmersdelight.gui.RecipeIngredientIcons.resolveIngredientOptions(recipe.getInput());
                }
                for (var tool : recipe.getTools()) {
                    if (tool != null && tool.getKey() != null) {
                        com.huidu.farmersdelight.gui.RecipeIngredientIcons.createItemFromKey(tool.getKey());
                    }
                }
            }
        }
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
        if (!getAddonAdvancementRegistry().isReady()) {
            getAddonAdvancementRegistry().onSystemReady();
        }

        // A rebuild (/ce reload) recreates the UAA tab, which drops it from online clients. Re-show FD's own
        // tab to online players (no-op on first load — no one is online yet). Addon tabs re-sync in onSystemReady.
        advancementManager.resyncOnlinePlayers();

        // On a rebuild the first resync packet can land while the client is still re-applying CraftEngine's
        // resource pack, so it is dropped and the tab only returns after the player re-fetches. Force a full
        // re-send a short while later so the settled client receives the tree.
        if (reloading) {
            scheduler().runLater(() -> {
                if (advancementManager != null) {
                    advancementManager.forceResyncOnlinePlayers();
                }
                getAddonAdvancementRegistry().forceResyncOnline();
            }, ADVANCEMENT_RESYNC_DELAY_TICKS);
        }
    }

    @Override
    public void onLoad() {
        instance = this;
        I18n.init(this);
        configBootstrap.ensureConfigDefaults();
        // Load the family-wide tag map before CraftEngine's dependent plugins begin their enable phase.
        com.huidu.farmersdelight.util.CommonTagResolver.reload(this);
        // ensureConfigDefaults has just guaranteed config.yml exists, so the debug switch is readable this
        // early and the load-phase detail lines below can be surfaced by their category like the rest.
        loadDebugFlags();
        new com.huidu.farmersdelight.resource.ResourceInstaller(this, getFile()).installCraftEngineResourcesOnce();
        com.huidu.farmersdelight.registry.BehaviorRegistrar.registerBlockBehaviors();
        com.huidu.farmersdelight.registry.BehaviorRegistrar.registerItemBehaviors();
        com.huidu.farmersdelight.registry.BehaviorRegistrar.registerFunctions();
        // Register the farmersdelight:sword settings modifier before CraftEngine parses item YAML files.
        com.huidu.farmersdelight.tool.ToolRegistry.register();
        // Register the farmersdelight:pet_food settings modifier before CraftEngine parses item YAML files.
        PetFoodConfig.setLogger(getLogger());
        PetFoodConfig.registerCraftEngineSetting();
        // Register the WorldGuard custom region flag here (onLoad): WG locks its FlagRegistry once it
        // enables, so this must run during the load phase. No-op if WorldGuard is absent.
        ProtectionCompat.registerFlags();
    }

    // Registers several transient listeners in one shot. Transient listeners own no long-lived state and are
    // never retained as fields (they are not individually torn down or reloaded), so a bulk-varargs registration
    // keeps the onEnable bootstrap readable without changing behaviour or order.
    private void registerEvents(org.bukkit.event.Listener... listeners) {
        org.bukkit.plugin.java.JavaPlugin plugin = this;
        for (org.bukkit.event.Listener listener : listeners) {
            getServer().getPluginManager().registerEvents(listener, plugin);
        }
    }

    private static final String RELOAD_GUARD_PROPERTY = "farmersdelight.enabled.in.this.jvm";
    private boolean enabledSuccessfully = false;

    // FarmersDelight's bStats plugin id.
    private static final int BSTATS_PLUGIN_ID = 32571;

    // Required host platform; excluded from enchantment-conflict detection (it hooks the enchant event to manage
    // its own custom items and is always present, so it is not a competing enchantment system).
    private static final String HOST_PLUGIN_NAME = "CraftEngine";

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
        configBootstrap.validateConfigTypes();

        scheduler = new SchedulerAdapter(this);
        if (scheduler.isFolia()) {
            // Reported as a field of the startup config summary rather than its own line.
            I18n.logDetail("startup", "plugin.folia_scheduler");
        }

        // Build the protection facade over all installed land plugins (softdepends are enabled by now);
        // WorldGuard flags were already registered in onLoad. Non-WG land plugins gate via AntiGriefLib.
        ProtectionCompat.init(this);

        loadConfigs();
        com.huidu.farmersdelight.tool.ToolRegistry.refresh();
        logStartupSummary();

        knifeDropHandler = new KnifeDropHandler(this);
        knifeDropHandler.loadConfig(dropsConfig);
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

        specialRecipeRegistry = new SpecialRecipeRegistry();
        SpecialRecipeLoader.load(this, specialRecipeRegistry);
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
        MushroomColonyBehavior.reloadMushroomSupportCache(this);
        registerEvents(
                new SkilletPlaceListener(),
                new ToolAttackListener(),
                new RottenTomatoListener(this),
                new CuttingBoardInteractListener(),
                new CuttingBoardDispenseListener(this));

        strawDropListener = new StrawDropListener(this);
        getServer().getPluginManager().registerEvents(strawDropListener, this);

        registerEvents(new RicePlantListener(this));

        // Awards master_chef criteria and preserves addon/legacy food registrations. Built-in food buffs run as CE functions.
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

        registerEvents(this, new com.huidu.farmersdelight.api.util.PluginManagerGuard(getName()));

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
        ropeBlockListener = new RopeBlockListener(this);
        getServer().getPluginManager().registerEvents(ropeBlockListener, this);
        registerEvents(
                new TatamiBreakListener(),
                new RichSoilHoeListener(this),
                new CropInteractProtectionListener());

        backstabListener = new BackstabListener(this);
        getServer().getPluginManager().registerEvents(backstabListener, this);

        knifeEnchantFilter = new KnifeEnchantFilter(this);
        getServer().getPluginManager().registerEvents(knifeEnchantFilter, this);

        enchantmentDatapackInstaller = new EnchantmentDatapackInstaller(this);
        enchantmentDatapackInstaller.installToPrimaryWorld(getPrimaryWorld());
        getServer().getPluginManager().registerEvents(enchantmentDatapackInstaller, this);

        // Registry tags are server-global (shared by every world), so the common-item tag data pack is
        // written once into the primary world's datapacks folder; a per-world inject would be redundant.
        tagDatapackInstaller = new TagDatapackInstaller(this);
        if (tagDatapackInstaller.installToPrimaryWorld(getPrimaryWorld())) {
            queueDatapackReload(I18n.formatConsole("plugin.datapack_reason_apply_tag_changes"));
        }

        // Villager and wandering trader trades (world-data section). Composting chances and furnace burn
        // times now live in the CraftEngine item configurations instead.
        registerEvents(new com.huidu.farmersdelight.listener.worlddata.VillagerTradeListener());

        indexLoadedChunkContentWhenReady();

        chunkLoadListener = new ChunkLoadListener(this);
        getServer().getPluginManager().registerEvents(chunkLoadListener, this);
        chunkLoadListener.loadAlreadyLoadedChunks();

        com.huidu.farmersdelight.listener.DamageTypeDatapackInstaller damageInstaller =
                new com.huidu.farmersdelight.listener.DamageTypeDatapackInstaller(this);
        this.damageTypeDatapackInstaller = damageInstaller;
        damageInstaller.installToPrimaryWorld(getPrimaryWorld());
        // Remove the now-obsolete loot datapack folder (chest/grass/mob injections migrated to CE native loot sources).
        damageInstaller.cleanupLegacyLootDatapack();

        refreshAdvancementSystemWhenReady(false);
        // Warm CE item/GUI/behavior caches now IF CE is already up (FD enabled after CraftEngine). When CE
        // loads after FD, onCraftEngineReload runs the warmup instead — the readiness gate makes them exclusive.
        warmUpWhenReady("enable");

        scheduler.run(() -> startupSyncCompleted = true);

        FarmersDelightCommand commandHandler = new FarmersDelightCommand(this);
        org.bukkit.command.PluginCommand baseCommand = getCommand("farmersdelight");
        if (baseCommand == null) {
            throw new IllegalStateException("Command farmersdelight is missing from plugin.yml");
        }
        baseCommand.setExecutor(commandHandler);
        baseCommand.setTabCompleter(commandHandler);

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
                I18n.logWarning("bstats_failed", "error", t.getMessage());
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
        runDisableStep("plugin.disable_step_clear_behavior_config_lists", BlockBehaviorConfigs::clear);

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
        cookingPotGuiConfig = null;
        cookingPotExperienceRewardConfig = null;
        dropsConfig = null;
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
                CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
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
            // Bukkit's registry-backed tag views are rebuilt by reloadData; drop FD's derived caches
            // so newly installed tags are visible immediately without an expensive full CE reload.
            com.huidu.farmersdelight.util.ItemUtils.clearItemCache();
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

        long generation = craftEngineReloadGeneration.incrementAndGet();
        if (pendingCraftEngineReloadTask != null && !pendingCraftEngineReloadTask.isCancelled()) {
            pendingCraftEngineReloadTask.cancel();
        }
        pendingCraftEngineReloadTask = scheduler.runLater(() -> {
            if (generation != craftEngineReloadGeneration.get()) {
                return;
            }
            try {
                pendingCraftEngineReloadTask = null;
                I18n.reload();
                I18n.logDetail("startup", "plugin.craftengine_reload");
                refreshAfterCraftEngineReload();
                // A late CE enable still needs the one-time recipe load and addon warmup. Once that has
                // completed, CE reloads only invalidate cheap caches; rebuilding plugin recipes or UAA tabs
                // here creates a synchronous spike and sends a full advancement tree to every online player.
                if (!contentWarmupCompleted.get()) {
                    loadRecipeManagersWhenReady("plugin.refreshing_recipes_after_ce");
                    refreshAdvancementSystemWhenReady(false);
                    warmUpWhenReady("reload");
                }
                // A plugin enabled before CraftEngine reaches this path for its one required loaded-chunk rope
                // recovery scan; later CE reloads retain the already-built index.
                indexLoadedChunkContentWhenReady();
                com.huidu.farmersdelight.tool.ToolRegistry.refresh();
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.SEVERE,
                        "Error during CraftEngine reload processing in " + getClass().getSimpleName(), e);
            }
        }, 5L);
    }

    public void reloadRecipesWhenReady(String reason) {
        loadRecipeManagersWhenReady(reason);
    }

    private void refreshAfterCraftEngineReload() {
        com.huidu.farmersdelight.util.ItemUtils.clearItemCache();
        com.huidu.farmersdelight.util.SoundUtils.clearCache();
        RecipeViewGui.clearConfigCache();
        com.huidu.farmersdelight.gui.recipebook.RecipeBookGui.clearConfigCache();

        if (stoveManager != null) {
            stoveManager.reloadRecipeCache();
        }
        if (skilletManager != null) {
            skilletManager.reloadRecipeCache();
        }
    }

    public void reloadConfigs() {
        reloadAll();
    }

    private void reloadCommon(boolean reloadLanguages) {
        configBootstrap.ensureConfigDefaults();
        reloadConfig();
        configBootstrap.migrateConfigKeys();
        configBootstrap.validateConfigTypes();
        boolean previousAdvancementsEnabled = advancementsEnabled;
        loadConfigs();
        com.huidu.farmersdelight.tool.ToolRegistry.refresh();
        if (backstabListener != null) {
            backstabListener.reload(enchantmentSettings, backstabEnchantmentEnabled);
        }
        if (knifeEnchantFilter != null) {
            knifeEnchantFilter.reload(enchantmentSettings, backstabEnchantmentEnabled);
        }
        if (enchantmentDatapackInstaller != null) {
            enchantmentDatapackInstaller.installToPrimaryWorld(getPrimaryWorld());
        }
        if (reloadLanguages) {
            I18n.reload();
        }
        com.huidu.farmersdelight.util.ItemUtils.clearItemCache();
        com.huidu.farmersdelight.util.SoundUtils.clearCache();
        RecipeViewGui.clearConfigCache();
        com.huidu.farmersdelight.gui.recipebook.RecipeBookGui.clearConfigCache();
        MushroomColonyBehavior.reloadMushroomSupportCache(this);

        if (knifeDropHandler != null) {
            knifeDropHandler.loadConfig(dropsConfig, false);
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
        com.huidu.farmersdelight.recipe.RecipeFileLoader.resetReportedIssues();
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
        com.huidu.farmersdelight.recipe.RecipeFileLoader.resetReportedIssues();
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

    public void reloadTags() {
        com.huidu.farmersdelight.util.CommonTagResolver.reload(this);
        // The recipe-book tag icons are cached per tag ingredient; drop them so the edited members
        // take effect on the next render.
        com.huidu.farmersdelight.gui.RecipeIngredientIcons.clearCaches();
        // Refresh the exported vanilla-member tag data pack; Bukkit/Paper reloads it automatically.
        if (tagDatapackInstaller != null) {
            if (tagDatapackInstaller.installToPrimaryWorld(getPrimaryWorld())) {
                queueDatapackReload(I18n.formatConsole("plugin.datapack_reason_apply_tag_changes"));
            }
        }
        I18n.logInfo("plugin.tags_reloaded");
    }

    // Reroutes the historical /fd reload loot: the chest/grass/mob injections now live as CraftEngine
    // native loot sources, so a datapack reinstatement no longer applies. This pass only removes the
    // stale loot datapack folder old builds left in world datapacks/, which is idempotent.
    public void reloadLootDatapack() {
        if (damageTypeDatapackInstaller == null) {
            return;
        }
        damageTypeDatapackInstaller.cleanupLegacyLootDatapack();
    }

    // Re-runs the standalone damage-type datapack install (farmersdelight_damage) plus the legacy
    // file migration out of the loot datapack folder. Also excluded from the plain /fd reload and
    // CraftEngine reload passes for the same reason as the loot datapack: a write only takes effect
    // after a server restart.
    public void reloadDamageTypeDatapack() {
        if (damageTypeDatapackInstaller == null) {
            I18n.logWarning("loot_datapack_not_ready");
            return;
        }
        damageTypeDatapackInstaller.installToPrimaryWorld(getPrimaryWorld());
        damageTypeDatapackInstaller.cleanupLegacyLootDatapack();
    }

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
        YamlConfiguration worldDataConfig = configBootstrap.loadWorldDataConfig();
        YamlConfiguration loadedDropsConfig = configBootstrap.loadDropsConfig();
        dropsConfig = loadedDropsConfig;
        advancementsEnabled = getConfig().getBoolean("advancements.enabled", true);
        // No legacy path: buff.enabled is new, and a config that predates it has the buff system on.
        buffSystemEnabled = getConfigBoolean(true, "buff.enabled");
        // Mirror the switch into the addon-facing registry so its entry points can degrade to no-ops
        // without reaching back through the plugin singleton from an addon thread.
        com.huidu.farmersdelight.api.buff.CustomBuffRegistry.setSystemEnabled(buffSystemEnabled);
        EnchantmentSettings loadedEnchantments = EnchantmentSettings.load(
                getConfig().getConfigurationSection("enchantments"));
        enchantmentSettings = loadedEnchantments;
        // Keep the knife/skillet enchant filter running whatever else is installed: its CraftEngine knives are
        // nether_brick underneath and can be enchanted only through this filter, so disabling it would make them
        // un-enchantable rather than hand them off. Only our custom backstab enchant stands down when a dedicated
        // enchantment plugin is present, so we don't stack a second special enchant onto its system. Admin can
        // force backstab on regardless with enchantments.compatibility.auto-disable-on-conflict: false.
        boolean backstab = resolveBackstabbingCompatibility(loadedEnchantments);
        if (backstab && loadedEnchantments.autoDisableOnConflict()) {
            String conflict = detectEnchantmentConflict();
            if (conflict != null) {
                getLogger().warning("Backstab enchantment disabled: another enchantment plugin is present ("
                        + conflict + "); knife enchanting stays active. Set "
                        + "enchantments.compatibility.auto-disable-on-conflict: false to force backstab on.");
                backstab = false;
            }
        }
        backstabEnchantmentEnabled = backstab;
        // Re-read on every reload so a debug switch edited in config.yml takes effect; the enable path
        // has already read it once, earlier, for the startup lines that precede this method.
        loadDebugFlags();
        knifeItemIds = getConfigStringList("knife-items.items").stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        knifeTagIds = getConfigStringList("knife-items.tags").stream()
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
        ConfigurationSection strawDropSection = loadedDropsConfig.getConfigurationSection("straw");
        StrawDropConfig newStrawDropConfig = new StrawDropConfig();
        newStrawDropConfig.loadDefaults();
        if (strawDropSection != null) {
            newStrawDropConfig.loadFromConfig(strawDropSection);
        }
        strawDropConfig = newStrawDropConfig;

        loadPetFoodConfig();

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
        cuttingBoardFailVolume = (float) Math.max(0.0D, getConfigDouble((double) cuttingBoardFailVolume,
                "cutting-board.sounds.retrieve-volume"));
        cuttingBoardFailPitch = (float) Math.max(0.0D, getConfigDouble((double) cuttingBoardFailPitch,
                "cutting-board.sounds.retrieve-pitch"));
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
        com.huidu.farmersdelight.listener.worlddata.WorldDataConfig.reload(this, worldDataConfig);
        // Load the c: common-tag mapping before recipes load; it feeds recipe tag matching/indexing.
        com.huidu.farmersdelight.util.CommonTagResolver.reload(this);
    }

    private boolean configFileHas(String path) {
        return getConfig().contains(path, true);
    }

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

    public boolean isCuttingBoardRecipeOnlyPlacement() {
        return getConfig().getBoolean("cutting-board.recipe-only-placement", false);
    }

    public float getCuttingBoardFailVolume() {
        return cuttingBoardFailVolume;
    }

    public float getCuttingBoardFailPitch() {
        return cuttingBoardFailPitch;
    }

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
        return !hopperInteractionsEnabled || !skilletHopperInteractionsEnabled;
    }

    public boolean isSkilletConductorsAllowed() {
        return skilletConductorsAllowed;
    }

    public boolean isKnifeItemId(String itemId) {
        if (itemId == null) {
            return false;
        }
        String id = itemId.toLowerCase(Locale.ROOT);
        if (knifeItemIds.contains(id)) {
            return true;
        }
        // Knives registered by addons through the family tag registry (tag to members) are honored
        // wherever we check knife behavior, so an addon adding its knives to farmersdelight:tools/knives
        // via its own tags.yml works as a cutting tool without editing the central knife-items config.
        Set<String> tags = CommonTagResolver.getTagsForItemId(id);
        for (String tag : knifeTagIds) {
            if (tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getKnifeItemIds() {
        return knifeItemIds;
    }

    public Set<String> getKnifeTagIds() {
        return knifeTagIds;
    }

    public EnchantmentSettings getEnchantmentSettings() {
        return enchantmentSettings;
    }

    public boolean isBackstabEnchantmentEnabled() {
        return backstabEnchantmentEnabled;
    }

    private void loadPetFoodConfig() {
        PetFoodConfig newPetFoodConfig = new PetFoodConfig();
        newPetFoodConfig.loadFromCraftEngine();
        ConfigurationSection legacySection = getConfig().getConfigurationSection("pet-foods");
        if (legacySection != null) {
            newPetFoodConfig.mergeFromConfig(legacySection);
        }
        petFoodConfig = newPetFoodConfig;
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

    public ConfigurationSection getDropsConfig() {
        YamlConfiguration current = dropsConfig;
        return current == null ? null : current;
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

    /** Addons' cutting-board right-click handlers, tried in registration order until one consumes. */
    public void registerCuttingBoardInteractionHandler(CuttingBoardInteractionHandler handler) {
        if (handler != null && !cuttingBoardInteractionHandlers.contains(handler)) {
            cuttingBoardInteractionHandlers.add(handler);
        }
    }

    public void unregisterCuttingBoardInteractionHandler(CuttingBoardInteractionHandler handler) {
        cuttingBoardInteractionHandlers.remove(handler);
    }

    public List<CuttingBoardInteractionHandler> getCuttingBoardInteractionHandlers() {
        return cuttingBoardInteractionHandlers;
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

    public SpecialRecipeRegistry getSpecialRecipeRegistry() {
        return specialRecipeRegistry;
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

    public org.bukkit.World getPrimaryWorld() {
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

    private boolean resolveBackstabbingCompatibility(EnchantmentSettings settings) {
        return settings.isBackstabbingConfigured();
    }

    /** A short description of a conflicting enchantment system present on the server, or null when none is.
     *  No hard-coded or configured plugin list — it detects the conflict itself, two ways: first any enabled
     *  plugin other than this one that hooks the enchanting-table offer event (catches lore/handler-based
     *  systems that register no real enchantments), then any enchantment registered outside the minecraft:
     *  and farmersdelight: namespaces (catches registry-based enchant plugins and custom-enchant datapacks).
     *  Startup plugin-enable order is not guaranteed, so the handler-list side is only reliable once every
     *  plugin is up: recheckEnchantmentConflict() re-runs this on ServerLoadEvent, and /fd reload too. */
    private String detectEnchantmentConflict() {
        for (org.bukkit.plugin.RegisteredListener listener :
                org.bukkit.event.enchantment.PrepareItemEnchantEvent.getHandlerList().getRegisteredListeners()) {
            org.bukkit.plugin.Plugin other = listener.getPlugin();
            // CraftEngine is our required host, not a competing enchantment system: it hooks this event to manage
            // enchanting of its own custom items and is always present, so treating it as a conflict would disable
            // the feature on every install. Skip it (and its craftengine: namespace below) the same way we skip
            // ourselves; any genuine third-party enchant plugin is still caught.
            if (other != this && other.isEnabled() && !HOST_PLUGIN_NAME.equals(other.getName())) {
                return other.getName();
            }
        }
        var enchantRegistry = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
        for (org.bukkit.enchantments.Enchantment enchantment : enchantRegistry) {
            org.bukkit.NamespacedKey key = enchantRegistry.getKey(enchantment);
            if (key != null && !"minecraft".equals(key.getNamespace())
                    && !"farmersdelight".equals(key.getNamespace())
                    && !"craftengine".equals(key.getNamespace())
                    && !com.huidu.farmersdelight.api.enchant.FarmersDelightEnchantments.isRegistered(key.asString())) {
                return "custom enchantment " + key;
            }
        }
        return null;
    }

    /** Re-evaluate the enchantment-plugin conflict after every plugin is enabled (ServerLoadEvent), catching a
     *  conflicting plugin that enabled after this one during boot. Stands down only the custom backstab enchant
     *  (the knife/skillet enchant filter stays active so the CraftEngine knives remain enchantable) and reloads
     *  its listeners if a conflict is now present and the admin has not opted out. No-op once backstab is already
     *  off, when auto-disable is off, or when no conflict is found. */
    public void recheckEnchantmentConflict() {
        EnchantmentSettings current = enchantmentSettings;
        if (current == null || !current.autoDisableOnConflict() || !backstabEnchantmentEnabled) {
            return;
        }
        String conflict = detectEnchantmentConflict();
        if (conflict == null) {
            return;
        }
        getLogger().warning("Backstab enchantment disabled: another enchantment plugin is present ("
                + conflict + "); knife enchanting stays active. Set "
                + "enchantments.compatibility.auto-disable-on-conflict: false to force backstab on.");
        backstabEnchantmentEnabled = false;
        if (backstabListener != null) {
            backstabListener.reload(current, false);
        }
        if (knifeEnchantFilter != null) {
            knifeEnchantFilter.reload(current, false);
        }
    }

    /** Re-writes the enchantment datapack (now including any addon enchants registered through the API) and
     *  reloads the knife/skillet candidate pool so a newly-registered addon enchant is offered. Called by
     *  FarmersDelightEnchantments.register / addToPool from an addon's onEnable. A datapack registry object
     *  still needs a server restart to become usable — the installer prints its own banner. */
    public void refreshEnchantSystem() {
        if (enchantmentDatapackInstaller != null) {
            enchantmentDatapackInstaller.installToPrimaryWorld(getPrimaryWorld());
        }
        if (knifeEnchantFilter != null) {
            knifeEnchantFilter.reload(enchantmentSettings, backstabEnchantmentEnabled);
        }
    }

}
