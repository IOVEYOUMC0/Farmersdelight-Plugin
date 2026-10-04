package com.huidu.farmersdelight;

import com.huidu.farmersdelight.advancement.AddonAdvancementRegistry;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.advancement.AdvancementAvailability;
import com.huidu.farmersdelight.api.advancement.FarmersDelightAdvancements;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionHandler;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionMode;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.api.enchant.FarmersDelightEnchantments;
import com.huidu.farmersdelight.api.event.FarmersDelightReloadEvent;
import com.huidu.farmersdelight.api.util.ShutdownBudget;
import com.huidu.farmersdelight.block.behavior.BlockBehaviorConfigs;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.MushroomColonyBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.WildRiceBlockBehavior;
import com.huidu.farmersdelight.listener.BlockPlaceListener;
import com.huidu.farmersdelight.listener.CraftEngineWatchdogListener;
import com.huidu.farmersdelight.listener.FoodEatListener;
import com.huidu.farmersdelight.listener.HorseFeedTemptListener;
import com.huidu.farmersdelight.listener.PetFoodListener;
import com.huidu.farmersdelight.listener.RecipeDiscoveryListener;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.command.FarmersDelightCommandRegistrar;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.config.ConfigLookup;
import com.huidu.farmersdelight.config.CookingPotExperienceRewardConfig;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.config.StrawDropConfig;
import com.huidu.farmersdelight.config.StationSettings;
import com.huidu.farmersdelight.config.PluginConfigFiles;
import com.huidu.farmersdelight.config.DebugSettings;
import com.huidu.farmersdelight.config.KnifeSettings;
import com.huidu.farmersdelight.compat.AuraSkillsHook;
import com.huidu.farmersdelight.compat.CraftEngineStateUsageMonitor;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.gui.GuiCacheInvalidator;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeIngredientIcons;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.gui.recipebook.RecipeBookListener;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.loot.KnifeDropHandler;
import com.huidu.farmersdelight.manager.BuffBossbarManager;
import com.huidu.farmersdelight.manager.CarrierRestorer;
import com.huidu.farmersdelight.manager.DatapackCoordinator;
import com.huidu.farmersdelight.manager.HandleManager;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.recipe.AdvancedTagGroups;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
import com.huidu.farmersdelight.recipe.RecipeFileLoader;
import com.huidu.farmersdelight.recipe.SpecialRecipeLoader;
import com.huidu.farmersdelight.recipe.SpecialRecipeRegistry;
import com.huidu.farmersdelight.registry.BehaviorRegistrar;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.InteractionDebouncer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import com.huidu.farmersdelight.visual.ProxyItemDisplayManager;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.api.visual.DisplayGroup;
import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.gui.editor.RecipeEditorListener;
import com.huidu.farmersdelight.gui.editor.RecipeEditorView;
import com.huidu.farmersdelight.pack.PackSections;
import com.huidu.farmersdelight.tool.ToolRegistry;
import com.huidu.farmersdelight.compat.PlaceholderApiHook;
import com.huidu.farmersdelight.effect.EffectManager;
import com.huidu.farmersdelight.config.ConfigBootstrap;
import com.huidu.farmersdelight.config.CuttingBoardSounds;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.world.CEWorld;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3f;
import org.bstats.bukkit.Metrics;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class FarmersDelightPlugin extends JavaPlugin implements Listener {

    // After a rebuild (/ce reload, /fd reload) the immediate advancement re-show can be dropped by a client
    // still re-applying CraftEngine's resource pack; force a second re-send after this many ticks.
    private static final long ADVANCEMENT_RESYNC_DELAY_TICKS = 20L;

    private static volatile FarmersDelightPlugin instance;
    private static volatile boolean enabled = false;
    
    private volatile boolean startupSyncCompleted = false;
    private CraftEngineReadinessCoordinator craftEngineReadinessCoordinator;
    private DatapackCoordinator datapackCoordinator;
    // CraftEngine hands pack sections over once per pack load; null when the section ids were taken by
    // another plugin or CraftEngine was not ready during onLoad. Read by the recipe and advancement loaders.
    private volatile PackSections packSections;

    private SchedulerAdapter scheduler;
    private TickManager tickManager;
    private TrayManager trayManager;
    private HandleManager handleManager;
    private BuffBossbarManager buffBossbarManager;
    private StoveManager stoveManager;
    private SkilletManager skilletManager;
    private ItemDisplayManager itemDisplayManager;
    // Restores the vanilla appearance of the vanilla block states the rope fence and its gate borrow.
    private CarrierRestorer carrierRestorer;
    private KnifeDropHandler knifeDropHandler;
    private CookingPotRecipeManager cookingPotRecipeManager;
    private CuttingBoardRecipeManager cuttingBoardRecipeManager;
    private SpecialRecipeRegistry specialRecipeRegistry;
    // Owns every event listener and the reload/stop hooks their state needs; see ListenerRegistry.
    private final ListenerRegistry listeners = new ListenerRegistry(this);
    // Owns the load-phase registrations that must complete before CraftEngine parses its packs.
    private final LoadPhaseRegistrar loadPhaseRegistrar = new LoadPhaseRegistrar(this);
    private final ConfigBootstrap configBootstrap = new ConfigBootstrap(this);
    private final PluginConfigFiles configFiles = new PluginConfigFiles(this, configBootstrap);

    // Lazy-loaded, may be accessed concurrently by multiple region threads (awarding XP when collecting cooking pot results); uses volatile + double-checked locking,
    // consistent with RecipeEditorView.store.
    private volatile AuraSkillsHook auraSkillsHook;

    // volatile: reassigned on reload and read by region threads.
    private volatile HeatSourceConfig heatSourceConfig;
    private GuiConfig cookingPotGuiConfig;
    private Map<String, GuiConfig> customCookingPotGuiConfigs = Map.of();
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
    private volatile StationSettings stationSettings;
    private final List<CuttingBoardInteractionHandler> cuttingBoardInteractionHandlers = new CopyOnWriteArrayList<>();
    private volatile KnifeSettings knifeSettings = new KnifeSettings(Set.of(), Set.of());
    private volatile DebugSettings debugSettings = new DebugSettings(false, Set.of());

    private final PrimaryWorldResolver primaryWorldResolver = new PrimaryWorldResolver(this);

    public static FarmersDelightPlugin getInstance() {
        return instance;
    }

    public static boolean isEnabled0() {
        return enabled;
    }

    /** The plugin jar on disk; JavaPlugin#getFile is protected, so collaborators need this accessor. */
    File pluginJarFile() {
        return getFile();
    }

    void loadRecipeManagers(String logKey) {
        I18n.logDetail("recipe", logKey);
        // Both loads read YAML from disk (the plugin's own files plus a scan of every CraftEngine pack's
        // farmersdelight/ directory) on the calling thread, which is a tick thread. Timed so the cost is
        // attributable: the warmup that follows reports its own number separately.
        long start = System.nanoTime();
        cookingPotRecipeManager.loadRecipes();
        long potNanos = System.nanoTime() - start;
        long boardStart = System.nanoTime();
        cuttingBoardRecipeManager.loadRecipes();
        long boardNanos = System.nanoTime() - boardStart;
        // Recipe set changed: drop the discovery obtain-trigger index so it rebuilds against the new recipes.
        if (recipeDiscoveryManager != null) {
            recipeDiscoveryManager.invalidateIndex();
        }
        I18n.logDetail("recipe", "plugin.recipes_loaded_timing",
                "pot", potNanos / 1_000_000L,
                "board", boardNanos / 1_000_000L,
                "total", (potNanos + boardNanos) / 1_000_000L);
    }

    public boolean isAdvancementsEnabled() {
        return advancementsEnabled;
    }

    // Whether the advancement system may run, reporting the one cause worth a log line. Shared with the
    // readiness gate so both disable the system for the same reason instead of keeping their own checks.
    boolean advancementSystemUsable() {
        AdvancementAvailability availability = FarmersDelightAdvancements.availability();
        if (availability == AdvancementAvailability.AVAILABLE) {
            return true;
        }
        if (availability == AdvancementAvailability.API_PATCH_MISSING) {
            // Registering a tab needs the layout overload, so an unpatched UltimateAdvancementAPI would
            // only throw NoSuchMethodError and silently drop every tree. Say so and run without them.
            I18n.logSevere("advancement.layout_unsupported");
        }
        return false;
    }

    public boolean isBuffSystemEnabled() {
        return buffSystemEnabled;
    }

    void disableAdvancementSystem() {
        listeners.unregisterAchievementListener();
        if (advancementManager != null) {
            advancementManager.dispose();
        }
        advancementManager = null;
        if (addonAdvancementRegistry != null) {
            addonAdvancementRegistry.onSystemDown();
        }
        queueAdvancementDatapackRemoval(I18n.formatConsole("plugin.datapack_reason_remove_disabled_advancements"));
    }

    public void reportContentSummaryWhenReady() {
        if (craftEngineReadinessCoordinator != null) {
            craftEngineReadinessCoordinator.reportContentSummaryWhenReady();
        }
    }

    public void requestContentSummary() {
        if (craftEngineReadinessCoordinator != null) {
            craftEngineReadinessCoordinator.requestContentSummary();
        }
    }

    void refreshAdvancementSystem(boolean reloading) {
        if (!advancementSystemUsable()) {
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

        listeners.registerAchievementListener();

        // Remove any legacy advancement datapacks to avoid their vanilla advancement tree duplicating the UAA tab.
        queueAdvancementDatapackRemoval(I18n.formatConsole("plugin.datapack_reason_remove_legacy_advancements"));

        // FD's own tab is up: (re)build any addon-registered tabs now that UAA + CraftEngine items are ready.
        if (!getAddonAdvancementRegistry().isReady()) {
            getAddonAdvancementRegistry().onSystemReady();
        } else if (reloading) {
            // CraftEngine may have added or removed package content; reload addon definitions before resync.
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
        // ensureConfigDefaults has just guaranteed config.yml exists, so the debug switch is readable this
        // early and the load-phase detail lines below can be surfaced by their category like the rest.
        loadDebugFlags();
        packSections = loadPhaseRegistrar.register();
    }

    private static final String RELOAD_GUARD_PROPERTY = "farmersdelight.enabled.in.this.jvm";
    private boolean enabledSuccessfully = false;

    // FarmersDelight's bStats plugin id.
    private static final int BSTATS_PLUGIN_ID = 32571;
    // Held so the disable sequence can stop bStats' own reporting task.
    private Metrics metrics;

    // Required host platform; excluded from enchantment-conflict detection (it hooks the enchant event to manage
    // its own custom items and is always present, so it is not a competing enchantment system).
    private static final String HOST_PLUGIN_NAME = "CraftEngine";

    @Override
    public void onEnable() {
        if (System.getProperty(RELOAD_GUARD_PROPERTY) != null) {
            I18n.logSevere("plugin.no_hot_reload", "name", "FarmersDelight");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        System.setProperty(RELOAD_GUARD_PROPERTY, "1");

        enabled = true;

        if (BuildFlags.DEBUG_TOOLS) {
            I18n.logWarning("plugin.debug_tools_build");
        }

        // onLoad already ensured the files exist; re-running the install pass here would only repeat its
        // per-file existence and readability checks before migrateConfigKeys() merges the same files.
        configBootstrap.migrateConfigKeys();
        // Startup detail logging reads these flags during I18n initialization and Folia detection.
        loadDebugFlags();
        I18n.init(this);
        configBootstrap.validateConfigTypes();

        scheduler = new SchedulerAdapter(this);
        craftEngineReadinessCoordinator = new CraftEngineReadinessCoordinator(this);
        datapackCoordinator = new DatapackCoordinator(this);
        if (scheduler.isFolia()) {
            // Reported as a field of the startup config summary rather than its own line.
            I18n.logDetail("startup", "plugin.folia_scheduler");
        }

        // Build the protection facade over all installed land plugins (softdepends are enabled by now);
        // WorldGuard flags were already registered in onLoad. Non-WG land plugins gate via AntiGriefLib.
        ProtectionCompat.init(this);

        loadConfigs();
        ToolRegistry.refresh();
        logStartupSummary();

        knifeDropHandler = new KnifeDropHandler(this);
        getServer().getPluginManager().registerEvents(knifeDropHandler, this);

        cookingPotRecipeManager = new CookingPotRecipeManager(this);

        cuttingBoardRecipeManager = new CuttingBoardRecipeManager(this);

        craftEngineReadinessCoordinator.loadRecipesWhenReady("plugin.loading_recipes");

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
            RecipeDiscoveryManager manager = recipeDiscoveryManager;
            // Skip the async hop entirely while the feature is off: there is nothing to flush, and the
            // flag can be turned on by a reload, so the task stays registered rather than being cancelled.
            if (manager != null && manager.isEnabled()) {
                // A refused task is not a lost flush: the state stays dirty and the next round retries.
                scheduler().tryRunAsync(() -> {
                    RecipeDiscoveryManager current = recipeDiscoveryManager;
                    if (current != null && current.isEnabled()) {
                        current.save();
                    }
                });
            }
        }, 6000L, 6000L);

        listeners.registerInteractionHandlers();

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
        carrierRestorer = new CarrierRestorer(this);
        buffBossbarManager = new BuffBossbarManager(this);
        buffBossbarManager.applyConfig(getFirstConfigSection("buff.display", "bossbar"), buffSystemEnabled);
        EffectManager.applyBossbarStyles(
                getFirstConfigSection("buff.display.styles", "bossbar.styles"));
        buffBossbarManager.start();
        listeners.registerVisualAndWorldHandlers(carrierRestorer, buffBossbarManager);

        listeners.registerDamageTypeDatapack();

        // Every call below reads CraftEngine content, which is normally still loading while FarmersDelight
        // enables (FD is declared to load before CraftEngine finishes its packs). The coordinator runs them
        // now when CraftEngine is already up, and otherwise retries until its content exists — so the
        // warm-up does not depend on CraftEngine's reload event arriving.
        craftEngineReadinessCoordinator.startupReadinessWork();

        scheduler.run(() -> startupSyncCompleted = true);

        registerMainCommand();

        // PlaceholderAPI bridge — registers iff PAPI is loaded so HUD plugins (BetterHud, MythicHud,
        // etc.) can read every CustomBuffRegistry entry per player. Soft-dep, no-op when absent.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new PlaceholderApiHook(this).register();
                I18n.logDetail("startup", "plugin.papi_bridge_registered");
            } catch (Throwable t) {
                I18n.logWarning("plugin.papi_bridge_failed", "error", t.getMessage());
            }
        }

        // bStats metrics: anonymous server/plugin stats. Opt out globally via plugins/bStats/config.yml.
        // bStats is bundled and relocated by shadowJar, so its own relocation self-check passes and the
        // copy stays private to this plugin instead of racing other plugins' copies for the shared name.
        if (BSTATS_PLUGIN_ID > 0) {
            try {
                metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            } catch (Throwable t) {
                I18n.logWarning("plugin.bstats_failed", "error", t.getMessage());
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
            I18n.logSevere("plugin.stale_classloader", "name", "FarmersDelight");
        }

        // MUST be first: stop event delivery before tearing down listeners' state. Vanilla code
        // (piston ticks, neighbour updates, scheduled chunk tasks) keeps firing during onDisable,
        // and PaperPluginClassLoader is already draining — late-bound lambda metafactory calls
        // from listener code will hit NoClassDefFoundError. Pulling listeners off the bus first
        // makes the rest of the shutdown order independent of vanilla event timing.
        // One deadline for every best-effort persistence step below. Per-step timeouts would make the
        // worst case their SUM; here the whole tail shares a budget and a spent budget skips the rest
        // instead of hanging the server. Structural teardown (listeners, tasks, caches) is NOT budgeted:
        // it is in-memory, cheap, and skipping it would leave dangling state behind.
        // The budget reports through the same language keys as runDisableStep, so budgeted and unbudgeted
        // steps fail with one wording instead of a second hardcoded English sentence.
        disableBudget = ShutdownBudget.ofMillis(
                        getConfigInt(DEFAULT_SHUTDOWN_WAIT_MILLIS, "performance.shutdown-wait-millis"), getLogger())
                .withMessages(
                        I18n.formatConsole("plugin.disable_step_failed"),
                        I18n.formatConsole("plugin.disable_budget_exhausted"));

        // Order inside ListenerRegistry#stop: event delivery is detached before any listener state is torn
        // down, then the listeners' own tasks stop, then the world/chunk handlers detach.
        runDisableStep("plugin.disable_step_unregister_listeners", listeners::stop);

        // bStats keeps a task of its own; its reports are useless once the plugin is going away, and
        // shutting it down here is what stops it from holding a reference to a disabled plugin instance.
        runDisableStep("plugin.disable_step_shutdown_metrics", () -> {
            Metrics current = metrics;
            metrics = null;
            if (current != null) {
                current.shutdown();
            }
        });

        runDisableStep("plugin.disable_step_stop_tick_manager", () -> {
            if (tickManager != null) {
                tickManager.stop();
                tickManager = null;
            }
        });

        // Stop the block managers' own tick tasks before the budgeted saves below: they read the tick manager
        // that was just cleared (NPE would kill the repeating task) and they must not keep cooking, dropping
        // items or advancing display state after the data they own has been written to disk.
        runDisableStep("plugin.disable_step_suspend_block_tick_tasks", () -> {
            if (stoveManager != null) {
                stoveManager.suspendTickTask();
            }
            if (skilletManager != null) {
                skilletManager.suspendTickTasks();
            }
        });

        runBudgetedDisableStep("plugin.disable_step_save_recipe_discovery", () -> {
            if (recipeDiscoveryManager != null) {
                recipeDiscoveryManager.save();
            }
        });

        runBudgetedDisableStep("plugin.disable_step_save_player_buffs", () -> {
            // A runtime disable (plugin manager, CE watchdog cascade) fires no quit events, so the
            // quit-time buff save never runs; persist every online player's buff state before the
            // effect listener stop below wipes the live maps and unregisters the buffs. On a normal
            // stop players were already kicked (and saved on quit), making this a no-op re-write.
            saveOnlinePlayerBuffs();
        });

        runDisableStep("plugin.disable_step_close_cooking_pot_guis", CookingPotGui::cleanupAll);
        runDisableStep("plugin.disable_step_close_recipe_view_guis", () -> {
            RecipeViewGui.cleanupAll();
            RecipeEditorListener.reset();
            // Same for RecipeBookListener: reset its flag, otherwise after a soft restart click/drag events
            // are no longer cancelled and items can be duped.
            RecipeBookListener.reset();
        });
        runBudgetedDisableStep("plugin.disable_step_save_block_data", this::saveAllBlockData);

        runDisableStep("plugin.disable_step_clear_placement_cache", this::cleanupPlacementCache);
        runDisableStep("plugin.disable_step_clear_interaction_debounce_cache", this::cleanupInteractionDebouncer);

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
            // The carrier displays are real entities rather than packet-only ones, so they are removed
            // here as well: leaving them behind would survive the plugin's own lifetime.
            if (carrierRestorer != null) {
                carrierRestorer.shutdown();
                carrierRestorer = null;
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
            if (craftEngineReadinessCoordinator != null) {
                craftEngineReadinessCoordinator.cancelPendingTasks();
                craftEngineReadinessCoordinator = null;
            }
            if (datapackCoordinator != null) {
                datapackCoordinator.cancelPendingTasks();
                datapackCoordinator = null;
            }
        });

        runDisableStep("plugin.disable_step_shutdown_scheduler", () -> {
            if (scheduler != null) {
                // Drains within whatever is left of the shared budget instead of its own fixed wait.
                scheduler.shutdown(disableBudget);
                scheduler = null;
            }
        });

        knifeDropHandler = null;
        cookingPotRecipeManager = null;
        cuttingBoardRecipeManager = null;
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
        // Do not leave the disabled plugin instance reachable through the public API singleton.
        instance = null;
    }

    // paper-plugin.yml has no "commands:" section, so the command is registered through Paper's
    // lifecycle API instead of getCommand(). BasicCommand maps 1:1 onto the existing
    // CommandExecutor/TabCompleter, so the handler itself is unchanged.
    private void registerMainCommand() {
        FarmersDelightCommandRegistrar.register(this);
    }

    private void runDisableStep(String stepKey, Runnable action, Object... stepArgs) {
        try {
            action.run();
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, disableStepFailure(stepKey, stepArgs), throwable);
        }
    }

    // One sentence for every shutdown step failure: the outer text and the step name both come from the
    // language layer, so the line reads as a sentence in either locale and names the step that failed.
    private String disableStepFailure(String stepKey, Object... stepArgs) {
        return I18n.formatConsole("plugin.disable_step_failed",
                "step", I18n.formatConsole(stepKey, stepArgs));
    }

    // For steps that only persist best-effort state: skipped (with one warning) once the shared
    // shutdown budget is spent, so a stuck write cannot hold the server open.
    private void runBudgetedDisableStep(String stepKey, Runnable action) {
        ShutdownBudget budget = disableBudget;
        if (budget == null) {
            runDisableStep(stepKey, action);
            return;
        }
        budget.step(I18n.formatConsole(stepKey), action);
    }

    private static final int DEFAULT_SHUTDOWN_WAIT_MILLIS = 5000;
    private ShutdownBudget disableBudget;

    private void cleanupPlacementCache() {
        BlockPlaceListener.cleanup();
    }

    private void cleanupInteractionDebouncer() {
        InteractionDebouncer.cleanup();
    }

    private void saveOnlinePlayerBuffs() {
        List<Player> players = List.copyOf(getServer().getOnlinePlayers());
        if (players.isEmpty()) return;
        SchedulerAdapter currentScheduler = scheduler;
        if (currentScheduler == null) {
            for (Player player : players) {
                try {
                    CustomBuffRegistry.saveAll(player);
                } catch (Throwable ignored) {
                    // Best effort fallback when enable failed before the scheduler was created.
                }
            }
            return;
        }
        CountDownLatch pending = new CountDownLatch(players.size());
        for (Player player : players) {
            try {
                currentScheduler.runForEntity(player, () -> {
                    try {
                        CustomBuffRegistry.saveAll(player);
                    } catch (Throwable ignored) {
                        // Per-player isolation; a broken addon must not prevent other saves.
                    } finally {
                        pending.countDown();
                    }
                }, pending::countDown);
            } catch (Throwable ignored) {
                pending.countDown();
            }
        }
        ShutdownBudget budget = disableBudget;
        long waitMillis = budget == null ? DEFAULT_SHUTDOWN_WAIT_MILLIS : budget.remainingMillis();
        try {
            if (!pending.await(Math.max(1L, waitMillis), TimeUnit.MILLISECONDS)) {
                getLogger().warning(I18n.formatConsole("plugin.disable_step_save_player_buffs_timeout"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // Every station manager owns its own state, so each flush is isolated: a manager that cannot write (a
    // missing class, a broken CraftEngine handle) must not skip the managers after it, and the shutdown path
    // has no retry. Each failure is logged with its own step name; the sequence itself never throws.
    private void saveAllBlockData() {
        if (scheduler != null && scheduler.isFolia()) {
            runDisableStep("plugin.disable_step_save_cooking_pot_data", CookingPotBlockBehavior::markAllBlockEntitiesDirty);
            runDisableStep("plugin.disable_step_save_cutting_board_data", CuttingBoardBlockBehavior::markAllBlockEntitiesDirty);
        } else {
            runDisableStep("plugin.disable_step_save_cooking_pot_data", CookingPotBlockBehavior::saveAllData);
            runDisableStep("plugin.disable_step_save_cutting_board_data", CuttingBoardBlockBehavior::saveAllData);
        }
        runDisableStep("plugin.disable_step_save_skillet_data", SkilletBlockBehavior::saveAllData);
        runDisableStep("plugin.disable_step_save_stove_data", StoveCookingBlockBehavior::saveAllData);

        flushCraftEngineWorldData();
    }

    private void flushCraftEngineWorldData() {
        BukkitWorldManager worldManager = BukkitWorldManager.instance();
        if (worldManager == null) {
            return;
        }

        for (World world : getServer().getWorlds()) {
            runDisableStep("plugin.disable_step_flush_craftengine_world", () -> {
                CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
                if (ceWorld != null) {
                    ceWorld.saveChunks();
                    ceWorld.saveSettings();
                }
            }, "world", world.getName());
        }
    }

    // ignoreCancelled: WorldUnloadEvent is cancellable, and this handler passivates + drops skillet/stove
    // entries — running it for an already-cancelled unload would needlessly strip a live world's visuals.
    // A cancellation AFTER this priority still self-heals: the entry-creation flush hooks re-hydrate each
    // block from its controller snapshot on the first interaction.
    @EventHandler(ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        saveWorldBlockData(event.getWorld());

        UUID worldId = event.getWorld().getUID();
        CookingPotBlockBehavior.cleanupWorld(worldId);
        CuttingBoardBlockBehavior.cleanupWorld(worldId);
        SkilletBlockBehavior.cleanupWorld(worldId);
        StoveCookingBlockBehavior.cleanupWorld(worldId);
        if (tickManager != null) {
            tickManager.cleanupWorld(worldId);
        }

        if (itemDisplayManager != null) {
            // Remove all proxy display entities for the unloaded world, so stale entries don't linger in the displays map
            // until a chunk unload event that may never fire.
            itemDisplayManager.cleanupWorld(worldId);
        }
        if (carrierRestorer != null) {
            carrierRestorer.unloadWorld(event.getWorld());
        }
    }

    private void saveWorldBlockData(World world) {
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

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (carrierRestorer != null) {
            carrierRestorer.sweepOrphans(event.getWorld());
        }
        if (!startupSyncCompleted) {
            return;
        }
        World primaryWorld = getPrimaryWorld();
        if (primaryWorld == null || !primaryWorld.getUID().equals(event.getWorld().getUID())) {
            return;
        }
        // Advancements are sent via datapack; no per-world datapack sync.
    }

    public void queueDatapackReload(String reason) {
        if (datapackCoordinator != null) {
            datapackCoordinator.queueReload(reason);
        }
    }

    private void queueAdvancementDatapackRemoval(String reason) {
        if (datapackCoordinator != null) {
            datapackCoordinator.queueRemoval(reason);
        }
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        if (craftEngineReadinessCoordinator != null) {
            craftEngineReadinessCoordinator.queueReloadProcessing();
        }
    }

    public void reloadRecipesWhenReady(String reason) {
        if (craftEngineReadinessCoordinator != null) {
            craftEngineReadinessCoordinator.loadRecipesWhenReady(reason);
        }
    }

    void refreshAfterCraftEngineReload() {
        // Re-apply the registrations other plugins made through ContentRegistration before anything below
        // reads the fresh pack content. CraftEngine keeps its built-in type registries for the whole
        // class-loader lifetime, so this is defence in depth; it is idempotent either way, because ids
        // CraftEngine already reports are skipped.
        BehaviorRegistrar.replayContentRegistrations();
        ReloadCacheInvalidator.clear();
        if (specialRecipeRegistry != null) {
            specialRecipeRegistry.invalidateIndex();
            // Re-apply the special-recipe sources: a CraftEngine pack reload may have changed the cards it
            // declares, and this is the only pass that sees the fresh pack sections.
            SpecialRecipeLoader.load(this, specialRecipeRegistry);
        }

        // Pet food is declared as a CraftEngine item setting, so the scan in loadConfigs() only sees anything
        // once CraftEngine has registered its items. FarmersDelight usually enables first, which left the scan
        // empty and the tempt list at zero until an explicit /fd reload; repeat it here, where CraftEngine is
        // known to be ready. PetFoodListener reads the config per interaction, so only the tempt list needs
        // refreshing.
        petFoodConfig = configFiles.loadPetFood(getConfig().getConfigurationSection("pet-foods"));
        if (listeners.horseFeedTemptListener() != null) {
            listeners.horseFeedTemptListener().reload();
        }

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
        // Registration is idempotent and skips ids CraftEngine already reports, so re-applying it here keeps
        // another plugin's registrations alive across /fd reload without re-registering our own content.
        BehaviorRegistrar.replayContentRegistrations();
        long reloadStart = System.nanoTime();
        // One parse per user file for this whole pass: validation and the load pass read the same four files,
        // and a reload could not see two different contents anyway. Cleared at the start of every reload.
        configBootstrap.beginReload();
        resetReloadTimings();
        long phase = System.nanoTime();
        configBootstrap.ensureConfigDefaults();
        reloadConfig();
        long readConfigNanos = System.nanoTime() - phase;
        phase = System.nanoTime();
        configBootstrap.migrateConfigKeys();
        long migrateNanos = System.nanoTime() - phase;
        phase = System.nanoTime();
        configBootstrap.prefetchReloadFiles();
        configBootstrap.validateConfigTypes();
        long validateNanos = System.nanoTime() - phase;
        boolean previousAdvancementsEnabled = advancementsEnabled;
        phase = System.nanoTime();
        loadConfigs();
        long loadNanos = System.nanoTime() - phase;
        long configNanos = readConfigNanos + migrateNanos + validateNanos + loadNanos;
        phase = System.nanoTime();
        ToolRegistry.refresh();
        listeners.refreshEnchantmentFallback();
        if (reloadLanguages) {
            I18n.reload();
        }
        long toolsNanos = System.nanoTime() - phase;
        phase = System.nanoTime();
        ReloadCacheInvalidator.clear();
        MushroomColonyBehavior.reloadMushroomSupportCache(this);
        long cachesNanos = System.nanoTime() - phase;
        phase = System.nanoTime();

        // drops.yml carries only the straw registry now. The retired mob-extra sections were dropped from the
        // bundled file and are no longer read, so a leftover copy on disk is ignored silently. This phase is
        // the one that reruns every CraftEngine-backed cache (campfire recipe scans, display entities), so it
        // is timed separately: it is the part that scales with how much CE content the server has.
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
        if (carrierRestorer != null) {
            carrierRestorer.reload();
        }
        CuttingBoardBlockBehavior.refreshDisplayEntities();
        if (skilletManager != null) {
            skilletManager.reloadConfig();
            skilletManager.reloadRecipeCache();
        }
        long managersNanos = System.nanoTime() - phase;
        phase = System.nanoTime();
        listeners.reload(buffSystemEnabled);
        if (recipeDiscoveryManager != null) {
            recipeDiscoveryManager.reloadConfig();
        }
        if (previousAdvancementsEnabled != advancementsEnabled) {
            refreshAdvancementSystem(true);
        }
        long listenersNanos = System.nanoTime() - phase;

        reloadConfigNanos.set(configNanos);
        reloadToolsNanos.set(toolsNanos);
        reloadManagersNanos.set(managersNanos);
        reloadListenersNanos.set(listenersNanos);
        // The addon pass is a separate tick and has not run yet; it overwrites this when it lands.
        reloadAddonsNanos.set(0L);

        // A full reload runs synchronously inside one tick, so on a large server it is the single biggest
        // server-thread stall the plugin causes. The split says which phase to attack; it is off unless the
        // `reload` debug category is on, so a normal server never pays for the timing itself.
        I18n.logDetail("reload", "plugin.reload_breakdown",
                "total", (System.nanoTime() - reloadStart) / 1_000_000L,
                "config", configNanos / 1_000_000L,
                "tools", toolsNanos / 1_000_000L,
                "caches", cachesNanos / 1_000_000L,
                "managers", managersNanos / 1_000_000L,
                "listeners", listenersNanos / 1_000_000L);
        // The config bucket on its own does not say whether the cost is disk, merging or validation, and each
        // of those points at a different fix (read off-thread / merge fewer keys / validate fewer files).
        I18n.logDetail("reload", "plugin.reload_breakdown_config",
                "read", readConfigNanos / 1_000_000L,
                "migrate", migrateNanos / 1_000_000L,
                "validate", validateNanos / 1_000_000L,
                "load", loadNanos / 1_000_000L);
    }

    public void reloadAll() {
        reloadCommon(true);
        RecipeFileLoader.resetReportedIssues();
        reloadRecipesWhenReady("plugin.reloading_recipes");
        // The per-type recipe line is on the recipe detail channel, so the reloaded counts would otherwise
        // never reach the operator who just edited a recipe file. The summary dedupes on its counts digest,
        // so a reload that changed nothing stays silent.
        reportContentSummaryWhenReady();

        // Addons rebuild their own content off this event, synchronously. It runs on the next tick so their work
        // does not stack onto this command's tick; see notifyAddonsOfReload for why that is safe.
        notifyAddonsOfReload("reloadAll");
        I18n.logInfo("plugin.configuration_reloaded");
    }

    public void reloadMainConfigOnly() {
        reloadCommon(false);
        I18n.logInfo("plugin.main_configuration_reloaded");
    }

    public void reloadGuiConfig() {
        configBootstrap.ensureConfigDefaults();
        guiConfig = configFiles.loadGui();
        ConfigurationSection cookingPotSection = guiConfig.getConfigurationSection("cooking-pot-gui");
        cookingPotGuiConfig = cookingPotSection != null
                ? GuiConfig.fromConfig(cookingPotSection)
                : GuiConfig.createDefault();
        customCookingPotGuiConfigs = loadCustomCookingPotGuiConfigs(guiConfig);
        RecipeEditorView.reloadGui(guiConfig);
        GuiCacheInvalidator.clearConfigCachesAndCloseOpenGuis();
        I18n.logInfo("plugin.gui_configuration_reloaded", "file", "gui.yml");
    }

    public void reloadLanguageFiles() {
        I18n.reload();
        // GUI item names/lore come from language files and are cached, so invalidate those caches
        // and close open GUIs to force a rebuild in the new language.
        GuiCacheInvalidator.clearConfigCachesAndCloseOpenGuis();
        I18n.logInfo("plugin.language_files_reloaded");
    }

    private void fireReloadEvent(String reason) {
        Bukkit.getPluginManager().callEvent(new FarmersDelightReloadEvent(reason));
    }

    /**
     * Notifies addons that content changed, on the <em>next</em> tick rather than inside the command tick.
     *
     *
     * Four addons listen to this and rebuild their own content synchronously, so calling it inline made
     * /fd reload all block the server for FarmersDelight's own work <em>plus</em> every addon's. The
     * event is a notification hook — nothing in FarmersDelight reads a result back from it — so moving it one
     * tick later keeps the observable behaviour ("the reload happened") while halving the worst-case stall of
     * a single tick. This is the same treatment reloadRecipeFiles already gave it.
     *
     *
     * Uses runLater(..., 1) and not run(...): run executes immediately when it is
     * already called from the primary thread, which is exactly the case here, so it would not defer at all.
     */
    public void notifyAddonsOfReload(String reason) {
        if (scheduler == null) {
            fireReloadEvent(reason);
            return;
        }
        scheduler.runLater(() -> {
            long addonStart = System.nanoTime();
            fireReloadEvent(reason);
            long addonNanos = System.nanoTime() - addonStart;
            reloadAddonsNanos.set(addonNanos);
            // Timed separately because this work is invisible in the reloadCommon breakdown: on a server with
            // several addons it can exceed everything FarmersDelight does itself.
            I18n.logDetail("reload", "plugin.reload_addons", "ms", addonNanos / 1_000_000L);
            // Now that the addon pass has landed, repeat the summary in the console with the complete split.
            I18n.logDetail("reload", "plugin.reload_report",
                    "total", reloadTotalMillis(),
                    "split", reloadTimingSummary());
        }, 1L);
    }

    /**
     * Minimum spacing between two accepted /fd reload runs, in milliseconds; 0 disables the spacing but
     * still refuses a reload while one is running.
     *
     *
     * Read per call rather than cached so an edited config.yml takes effect without a reload of its own. The
     * default covers a full pass plus the addon cascade that follows it on the next tick.
     */
    public long reloadCooldownMillis() {
        return Math.max(0, getConfigInt(2, "reload.cooldown-seconds")) * 1000L;
    }

    /** Phase timings of the last reload, so the command can report them the way CraftEngine does. */
    private final AtomicLong reloadConfigNanos =
            new AtomicLong();
    private final AtomicLong reloadToolsNanos =
            new AtomicLong();
    private final AtomicLong reloadManagersNanos =
            new AtomicLong();
    private final AtomicLong reloadListenersNanos =
            new AtomicLong();
    private final AtomicLong reloadAddonsNanos =
            new AtomicLong();

    private void resetReloadTimings() {
        reloadConfigNanos.set(0L);
        reloadToolsNanos.set(0L);
        reloadManagersNanos.set(0L);
        reloadListenersNanos.set(0L);
        reloadAddonsNanos.set(0L);
    }

    /**
     * Reports the last reload the way CraftEngine reports its own: elapsed milliseconds plus the problems the
     * pass found. The addon figure is only filled in on the following tick; a command that reports before that
     * simply shows the phase timings it has.
     */
    public String reloadTimingSummary() {
        StringBuilder text = new StringBuilder();
        appendTiming(text, "config", reloadConfigNanos.get());
        appendTiming(text, "tools", reloadToolsNanos.get());
        appendTiming(text, "managers", reloadManagersNanos.get());
        appendTiming(text, "listeners", reloadListenersNanos.get());
        appendTiming(text, "addons", reloadAddonsNanos.get());
        return text.toString();
    }

    private void appendTiming(StringBuilder text, String phase, long nanos) {
        if (nanos <= 0) {
            return;
        }
        if (text.length() > 0) {
            text.append(" / ");
        }
        // Deliberately not an i18n key: these are diagnostic phase names for the debug/reload log line, not
        // player-facing text, and a dotted lookup would also look like a missing lang key to the checker.
        text.append(phase).append(' ').append(nanos / 1_000_000L).append("ms");
    }

    /** Total milliseconds the last reload's own pass occupied; the addon pass is reported separately. */
    public long reloadTotalMillis() {
        return (reloadConfigNanos.get() + reloadToolsNanos.get() + reloadManagersNanos.get()
                + reloadListenersNanos.get()) / 1_000_000L;
    }

    /** Problems the last reload found: config type mismatches plus recipe parse/reconcile issues. */
    public int reloadIssueCount() {
        return configBootstrap.validationIssueCount() + RecipeFileLoader.reportedIssueCount();
    }

    public void reloadRecipeFiles() {
        refreshAfterCraftEngineReload();
        RecipeFileLoader.resetReportedIssues();
        reloadRecipesWhenReady("plugin.reloading_recipes");
        // Same reason as reloadAll: the reloaded per-type counts are the only evidence the edited files
        // actually parsed, and the summary suppresses itself when they are unchanged.
        reportContentSummaryWhenReady();
        fireReloadEvent("reloadRecipes");
        I18n.logInfo("plugin.recipe_files_reloaded");
    }

    public void reloadAdvancements() {
        refreshAdvancementSystem(true);
        I18n.logInfo("plugin.advancement_data_reloaded");
    }

    public void reloadTags() {
        CommonTagResolver.reload(this);
        refreshTagDependentRecipes();
        // Refresh the exported vanilla-member tag data pack; Bukkit/Paper reloads it automatically.
        listeners.refreshTagDatapack();
        I18n.logInfo("plugin.tags_reloaded");
    }

    /** Rebuilds recipe state after common-tag membership changes at runtime. */
    public void refreshTagDependentRecipes() {
        if (craftEngineReadinessCoordinator == null) {
            return;
        }
        scheduler().run(() -> {
            RecipeFileLoader.resetReportedIssues();
            reloadRecipesWhenReady("plugin.reloading_recipes_after_tags");
            // The resolved tag/choice caches are stale the moment the tag registry changes. Loading recipes
            // does not populate them (the warm-up pass does), so drop them and rebuild them here: leaving the
            // clear as the last step would hand the entire tag resolution to the next player who opens the
            // recipe book instead of doing it on the reload that invalidated it.
            RecipeIngredientIcons.clearCaches();
            craftEngineReadinessCoordinator.rewarmRecipeIngredientIcons();
        });
    }

    // Loot injections are CraftEngine-native. /fd reload loot only removes the obsolete loot datapack
    // folder and is idempotent.
    public void reloadLootDatapack() {
        listeners.refreshLootDatapack();
    }

    // Installs farmersdelight_damage and migrates damage files out of the obsolete loot datapack.
    // Datapack writes require a server restart and are excluded from general reload passes.
    public void reloadDamageTypeDatapack() {
        if (!listeners.hasDamageTypeDatapack()) {
            I18n.logWarning("plugin.loot_datapack_not_ready");
            return;
        }
        listeners.refreshDamageTypeDatapack();
    }

    private void loadDebugFlags() {
        DebugSettings settings = DebugSettings.load(getConfig());
        debugSettings = settings;
    }

    private void loadConfigs() {
        YamlConfiguration worldDataConfig = configFiles.loadWorldData();
        YamlConfiguration loadedDropsConfig = configFiles.loadDrops();
        dropsConfig = loadedDropsConfig;
        advancementsEnabled = getConfig().getBoolean("advancements.enabled", true);
        // Missing buff.enabled preserves the enabled default.
        buffSystemEnabled = getConfigBoolean(true, "buff.enabled");
        // Mirror the switch into the addon-facing registry so its entry points can degrade to no-ops
        // without reaching back through the plugin singleton from an addon thread.
        CustomBuffRegistry.setSystemEnabled(buffSystemEnabled);
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
        knifeSettings = KnifeSettings.load(getConfig());

        ConfigurationSection heatSourceSection = getConfig().getConfigurationSection("heat-sources");
        // Build the complete configuration locally before publishing it through the volatile field.
        // Region-thread heat checks must not observe partially populated settings during reload.
        HeatSourceConfig newHeatSourceConfig = new HeatSourceConfig();
        HeatSourceConfig.setLogger(getLogger());
        newHeatSourceConfig.loadDefaults();
        if (heatSourceSection != null) {
            newHeatSourceConfig.loadFromConfig(heatSourceSection);
        }
        // The table above is rebuilt from scratch, which would drop every addon registration. Replay
        // them before publishing, so an addon that registered in onEnable survives /fd reload.
        FarmersDelightApi.get().replayAddonHeatSources(newHeatSourceConfig);
        heatSourceConfig = newHeatSourceConfig;

        // Same assign-once publication: the cut sound is resolved on region threads.
        cuttingBoardSounds = CuttingBoardSounds.from(
                getConfig().getConfigurationSection("cutting-board.sounds"));

        guiConfig = configFiles.loadGui();
        ConfigurationSection cookingPotSection = guiConfig.getConfigurationSection("cooking-pot-gui");
        cookingPotGuiConfig = cookingPotSection != null
                ? GuiConfig.fromConfig(cookingPotSection)
                : GuiConfig.createDefault();
        customCookingPotGuiConfigs = loadCustomCookingPotGuiConfigs(guiConfig);
        RecipeEditorView.reloadGui(guiConfig);

        // Build each immutable configuration before publishing it through its volatile field.
        // Region-thread event handlers can then read a complete snapshot during reload.
        ConfigurationSection strawDropSection = loadedDropsConfig.getConfigurationSection("straw");
        StrawDropConfig newStrawDropConfig = new StrawDropConfig();
        // No code-side defaults: the bundled drops.yml carries them and the section is a registry section,
        // so a rule the operator deleted has to stay deleted.
        newStrawDropConfig.loadFromConfig(strawDropSection);
        strawDropConfig = newStrawDropConfig;

        petFoodConfig = configFiles.loadPetFood(getConfig().getConfigurationSection("pet-foods"));

        ConfigurationSection containerReturnSection = getFirstConfigSection("container-returns", "cooking-pot.container-returns");
        ContainerReturnConfig newContainerReturnConfig = new ContainerReturnConfig();
        // Same rule as the straw drops above: bundled defaults only, no code-side re-adding.
        newContainerReturnConfig.loadFromConfig(containerReturnSection);
        containerReturnConfig = newContainerReturnConfig;

        CuttingBoardDisplayConfig newCuttingBoardDisplayConfig = new CuttingBoardDisplayConfig();
        newCuttingBoardDisplayConfig.loadFromConfig(getConfig().getConfigurationSection("cutting-board"));
        newCuttingBoardDisplayConfig.loadOverridesFromFile(configFiles.loadDisplayOverrides());
        cuttingBoardDisplayConfig = newCuttingBoardDisplayConfig;
        CuttingBoardDisplayConfig newSkilletDisplayConfig = createSkilletDisplayConfig();
        newSkilletDisplayConfig.loadFromConfig(getFirstConfigSection("skillet.display", "display-visuals.skillet"));
        skilletDisplayConfig = newSkilletDisplayConfig;
        CuttingBoardDisplayConfig newStoveDisplayConfig = createStoveDisplayConfig();
        newStoveDisplayConfig.loadFromConfig(getFirstConfigSection("stove.display", "display-visuals.stove"));
        stoveDisplayConfig = newStoveDisplayConfig;

        stationSettings = StationSettings.load(this, newSkilletDisplayConfig, newStoveDisplayConfig);

        cookingPotExperienceRewardConfig = new CookingPotExperienceRewardConfig();
        cookingPotExperienceRewardConfig.loadFromConfig(
                getFirstConfigSection("experience-reward", "cooking-pot.experience-reward"));
        WorldDataConfig.reload(this, worldDataConfig);
        // Load the c: common-tag mapping before recipes load; it feeds recipe tag matching/indexing.
        CommonTagResolver.reload(this);
    }

    public ConfigurationSection getFirstConfigSection(String... paths) {
        return ConfigLookup.firstSection(getConfig(), paths);
    }

    public boolean getConfigBoolean(boolean defaultValue, String... paths) {
        return ConfigLookup.booleanValue(getConfig(), defaultValue, paths);
    }

    public double getConfigDouble(double defaultValue, String... paths) {
        return ConfigLookup.doubleValue(getConfig(), defaultValue, paths);
    }

    public int getConfigInt(int defaultValue, String... paths) {
        return ConfigLookup.intValue(getConfig(), defaultValue, paths);
    }

    public List<String> getConfigStringList(String... paths) {
        return ConfigLookup.stringList(getConfig(), paths);
    }

    public boolean isShowRecipeNameInProgressDisplay() {
        return stationSettings.showRecipeName();
    }

    public boolean isCookingPotProgressDisplayEnabled() {
        return stationSettings.progressDisplayEnabled();
    }

    public double getCookingPotProgressDisplayYOffset() {
        return stationSettings.progressYOffset();
    }

    public float getCookingPotProgressDisplayScale() {
        return stationSettings.progressScale();
    }

    public double getCookingPotProgressDisplayVisibilityDistanceSquared() {
        return stationSettings.progressVisibilityDistanceSquared();
    }

    public double getCookingPotProgressDisplayLookDotThreshold() {
        return stationSettings.progressLookDotThreshold();
    }

    public int getCookingPotProgressDisplayUpdateIntervalTicks() {
        return stationSettings.progressUpdateIntervalTicks();
    }

    public int getCookingPotProgressDisplayDisableAboveActivePots() {
        return stationSettings.progressDisableAboveActivePots();
    }

    public boolean isCuttingBoardOffhandInteractionsAllowed() {
        return stationSettings.interactionMode() == CuttingBoardInteractionMode.OFFHAND;
    }

    public boolean isCuttingBoardStackingEnabled() {
        return stationSettings.interactionMode() == CuttingBoardInteractionMode.STACKING;
    }

    public CuttingBoardInteractionMode getCuttingBoardInteractionMode() {
        return stationSettings.interactionMode();
    }

    public boolean isCuttingBoardRecipeOnlyPlacement() {
        return getConfig().getBoolean("cutting-board.recipe-only-placement", false);
    }

    public float getCuttingBoardFailVolume() {
        return stationSettings.failVolume();
    }

    public float getCuttingBoardFailPitch() {
        return stationSettings.failPitch();
    }

    public boolean isCuttingBoardDispenserBehaviorEnabled() {
        return getConfig().getBoolean("cutting-board.dispenser-behavior", true);
    }

    public boolean isCookingPotHopperInteractionsEnabled() {
        return stationSettings.cookingPotHopperAllowed();
    }

    public boolean isCookingPotPackContentsOnBreak() {
        return stationSettings.cookingPotPackContentsOnBreak();
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

    public void callCookingPotExperienceEvent(Player player, ItemStack result, double baseExperience) {
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
        return stationSettings.cuttingBoardHopperAllowed();
    }

    public boolean isSkilletHopperInteractionsEnabled() {
        return stationSettings.skilletHopperAllowed();
    }

    public boolean isSkilletConductorsAllowed() {
        return stationSettings.skilletConductorsAllowed();
    }

    // One rule for every knife check, so the same item counts as a knife in every path: each identity the
    // stack carries (CraftEngine id, mmoitems:<TYPE>:<ID>, vanilla id) is looked up in knife-items.items,
    // then each of the item's tags against knife-items.tags. Taking the stack rather than a single id is
    // what lets a knife from MMOItems - which has no CraftEngine id - be recognized.
    public boolean isKnife(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        for (String id : ItemUtils.getItemIds(item)) {
            if (isKnifeItemId(id)) {
                return true;
            }
        }
        for (String tag : knifeSettings.tagIds()) {
            if (ItemUtils.matchesCustomOrVanillaTag(item, tag)) {
                return true;
            }
        }
        return false;
    }

    public boolean isKnifeItemId(String itemId) {
        if (itemId == null) {
            return false;
        }
        String id = itemId.toLowerCase(Locale.ROOT);
        KnifeSettings settings = knifeSettings;
        if (settings.itemIds().contains(id)) {
            return true;
        }
        // Knives registered by addons through the family tag registry (tag to members) are honored
        // wherever we check knife behavior, so an addon adding its knives to farmersdelight:tools/knives
        // via its own tags.yml works as a cutting tool without editing the central knife-items config.
        Set<String> tags = CommonTagResolver.getTagsForItemId(id);
        for (String tag : settings.tagIds()) {
            if (tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getKnifeItemIds() {
        return knifeSettings.itemIds();
    }

    public Set<String> getKnifeTagIds() {
        return knifeSettings.tagIds();
    }

    public EnchantmentSettings getEnchantmentSettings() {
        return enchantmentSettings;
    }

    public boolean isBackstabEnchantmentEnabled() {
        return backstabEnchantmentEnabled;
    }


    public BukkitCraftEngine getCraftEngine() {
        return BukkitCraftEngine.instance();
    }

    /** Pack sections CraftEngine collected, or null when the parser could not be registered. */
    public PackSections getPackSections() {
        return packSections;
    }

    /** Snapshots of one pack section, empty when the parser could not be registered. */
    public List<PackSections.Section> packSectionsOf(PackSection section) {
        PackSections parser = packSections;
        return parser == null ? List.of() : parser.sectionsOf(section);
    }

    /**
     * The advanced tag groups the packs declare, resolved into flat member lists, empty when the parser
     * could not be registered. Recipe readers publish this snapshot before they parse any ingredient, so an
     * advtag: reference resolves against the packs that are current rather than the previous load.
     */
    public AdvancedTagGroups advancedTagGroups() {
        PackSections parser = packSections;
        return parser == null ? AdvancedTagGroups.EMPTY : parser.advancedTagGroups();
    }

    public SchedulerAdapter scheduler() {
        if (scheduler == null) {
            throw new IllegalStateException("Scheduler is not available");
        }
        return scheduler;
    }

    public boolean isDebugEnabled() {
        return debugSettings.enabled();
    }

    public boolean isDebugEnabled(String category) {
        return debugSettings.enabledFor(category);
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
        return listeners.horseFeedTemptListener();
    }

    RopeBlockListener getRopeBlockListener() {
        return listeners.ropeBlockListener();
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
        return Collections.unmodifiableList(cuttingBoardInteractionHandlers);
    }

    private volatile CuttingBoardSounds cuttingBoardSounds = CuttingBoardSounds.defaults();

    public CuttingBoardSounds getCuttingBoardSounds() {
        return cuttingBoardSounds;
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

    public ConfigurationSection getRecipeViewGuiSection() {
        if (guiConfig == null) {
            guiConfig = configFiles.loadGui();
        }
        return guiConfig.getConfigurationSection("recipe-view-gui");
    }

    public ConfigurationSection getRecipeBookGuiSection() {
        if (guiConfig == null) {
            guiConfig = configFiles.loadGui();
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

    /** Null-tolerant accessor for reload paths that run before the manager exists (or after it is gone). */
    BuffBossbarManager getBuffBossbarManagerOrNull() {
        return buffBossbarManager;
    }

    public StoveManager getStoveManager() {
        return stoveManager;
    }

    public SkilletManager getSkilletManager() {
        return skilletManager;
    }


    public Set<Integer> collectLiveDisplayIds() {
        Set<Integer> liveIds = new HashSet<>();
        if (stoveManager != null) {
            stoveManager.collectLiveDisplayIds(liveIds);
        }
        if (skilletManager != null) {
            skilletManager.collectLiveDisplayIds(liveIds);
        }
        CuttingBoardBlockBehavior.collectLiveDisplayIds(liveIds);
        CookingPotBlockBehavior.collectLiveDisplayIds(liveIds);
        // Addons that hold their displays in a DisplayGroup are covered here, so they need no
        // FarmersDelightCollectLiveDisplaysEvent listener of their own. That event still fires for addons
        // tracking raw handles themselves.
        DisplayGroup.collectLiveHandles(liveIds);
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
        return listeners.foodEatListener();
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
        return stationSettings.skilletDisplayScale();
    }

    public double getSkilletDisplayYOffset() {
        return stationSettings.skilletDisplayYOffset();
    }

    public double getSkilletDisplaySpread() {
        return stationSettings.skilletDisplaySpread();
    }

    public float getStoveDisplayScale() {
        return stationSettings.stoveDisplayScale();
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
                "mode", stationSettings.interactionMode().configKey(),
                "hopper", stationSettings.hopperEnabled(),
                "cooking_pot_hopper", stationSettings.cookingPotHopperEnabled(),
                "cutting_board_hopper", stationSettings.cuttingBoardHopperEnabled(),
                "skillet_hopper", stationSettings.skilletHopperEnabled(),
                "advancements", advancementsEnabled);
    }

    public TickManager getTickManager() {
        return tickManager;
    }

    public World getPrimaryWorld() {
        return primaryWorldResolver.resolve();
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
        for (RegisteredListener listener : PrepareItemEnchantEvent.getHandlerList().getRegisteredListeners()) {
            Plugin other = listener.getPlugin();
            // CraftEngine is our required host, not a competing enchantment system: it hooks this event to manage
            // enchanting of its own custom items and is always present, so treating it as a conflict would disable
            // the feature on every install. Skip it (and its craftengine: namespace below) the same way we skip
            // ourselves; any genuine third-party enchant plugin is still caught.
            if (other != this && other.isEnabled() && !HOST_PLUGIN_NAME.equals(other.getName())) {
                return other.getName();
            }
        }
        var enchantRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (Enchantment enchantment : enchantRegistry) {
            NamespacedKey key = enchantRegistry.getKey(enchantment);
            if (key != null && !"minecraft".equals(key.getNamespace())
                    && !"farmersdelight".equals(key.getNamespace())
                    && !"craftengine".equals(key.getNamespace())
                    && !FarmersDelightEnchantments.isRegistered(key.asString())) {
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
        listeners.disableBackstabOnConflict(current);
    }

    /** Re-writes the enchantment datapack, including addon enchants registered through the API, and
     *  reloads the knife/skillet candidate pool so a newly-registered addon enchant is offered. Called by
     *  FarmersDelightEnchantments.register / addToPool from an addon's onEnable. A datapack registry object
     *  still needs a server restart to become usable — the installer prints its own banner. */
    public void refreshEnchantSystem() {
        listeners.refreshEnchantmentFallback();
        listeners.reloadEnchantCandidates();
    }

}
