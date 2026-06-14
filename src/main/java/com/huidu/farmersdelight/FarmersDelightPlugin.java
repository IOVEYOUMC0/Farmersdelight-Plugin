package com.huidu.farmersdelight;

import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.*;
import com.huidu.farmersdelight.listener.*;
import com.huidu.farmersdelight.command.FarmersDelightCommand;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.config.CookingPotExperienceRewardConfig;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
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
import com.huidu.farmersdelight.BuildFlags;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
import com.huidu.farmersdelight.storage.LegacyBlockStorageManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.InteractionDebouncer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import com.huidu.farmersdelight.visual.ProxyItemDisplayManager;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FarmersDelightPlugin extends JavaPlugin implements Listener {

    private static final String CRAFTENGINE_RESOURCE_ROOT = "craftengine/farmersdelight";
    private static final Path CRAFTENGINE_RESOURCE_TARGET = Path.of("CraftEngine", "resources", "farmersdelight");
    private static final String[][] CONFIG_KEY_MIGRATIONS = {
            {"knife-drops", "mob-extra-drops"},
            {"entity-extra-drops", "mob-extra-drops"},
            {"knife-drop-tools", "mob-extra-drop-tools"},
            {"entity-extra-drop-tools", "mob-extra-drop-tools"}
    };

    private static final List<String> ADVANCEMENT_RESOURCES = List.of(
            "advancements/pack.mcmeta",
            "advancements/data/farmersdelight/advancement/main/root.json",
            "advancements/data/farmersdelight/advancement/main/craft_knife.json",
            "advancements/data/farmersdelight/advancement/main/place_campfire.json",
            "advancements/data/farmersdelight/advancement/main/use_skillet.json",
            "advancements/data/farmersdelight/advancement/main/get_fd_seed.json",
            "advancements/data/farmersdelight/advancement/main/obtain_netherite_knife.json",
            "advancements/data/farmersdelight/advancement/main/hit_raider_with_rotten_tomato.json",
            "advancements/data/farmersdelight/advancement/main/harvest_straw.json",
            "advancements/data/farmersdelight/advancement/main/place_cooking_pot.json",
            "advancements/data/farmersdelight/advancement/main/place_skillet.json",
            "advancements/data/farmersdelight/advancement/main/place_feast.json",
            "advancements/data/farmersdelight/advancement/main/use_cutting_board.json",
            "advancements/data/farmersdelight/advancement/main/plant_rice.json",
            "advancements/data/farmersdelight/advancement/main/plant_all_crops.json",
            "advancements/data/farmersdelight/advancement/main/get_ham.json",
            "advancements/data/farmersdelight/advancement/main/master_chef.json"
    );

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
    private LegacyBlockStorageManager legacyBlockStorageManager;
    private TickManager tickManager;
    private TrayManager trayManager;
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
    private FoodEatListener foodEatListener;
    private PetFoodListener petFoodListener;
    private HorseFeedTemptListener horseFeedTemptListener;
    private AchievementListener achievementListener;
    private EffectListener effectListener;
    // 懒加载，可能被多个 region 线程并发访问（厨锅取出成品时奖励经验），用 volatile + 双重检查加锁，
    // 与 recipeEditorStore 的写法保持一致。
    private volatile AuraSkillsHook auraSkillsHook;

    // volatile：在 reload 时被重新赋值，并由区域线程读取。
    private volatile HeatSourceConfig heatSourceConfig;
    private GuiConfig cookingPotGuiConfig;
    private Map<String, GuiConfig> customCookingPotGuiConfigs = Map.of();
    private volatile RecipeEditorGuiConfig recipeEditorGuiConfig;
    private YamlConfiguration guiConfig;
    private StrawDropConfig strawDropConfig;
    private PetFoodConfig petFoodConfig;
    private volatile ContainerReturnConfig containerReturnConfig;
    private volatile CuttingBoardDisplayConfig cuttingBoardDisplayConfig;
    private volatile CuttingBoardDisplayConfig skilletDisplayConfig;
    private volatile CuttingBoardDisplayConfig stoveDisplayConfig;
    private volatile CookingPotExperienceRewardConfig cookingPotExperienceRewardConfig;
    private AdvancementManager advancementManager;
    private boolean advancementsEnabled;
    private boolean debugEnabled;
    private boolean showRecipeNameInProgressDisplay;
    private boolean cookingPotProgressDisplayEnabled = true;
    private double cookingPotProgressDisplayYOffset = 1.2D;
    private float cookingPotProgressDisplayScale = 0.5F;
    private double cookingPotProgressDisplayVisibilityDistanceSquared = 100.0D;
    private double cookingPotProgressDisplayLookDotThreshold = 0.95D;
    private int cookingPotProgressDisplayUpdateIntervalTicks = 8;
    private int cookingPotProgressDisplayDisableAboveActivePots = 512;
    private String cuttingBoardInteractionMode;
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
        try {
            return ItemUtils.isCustomItemLoaded(Key.of(Constants.ITEM_RICE_PANICLE));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void loadRecipeManagers(String logKey) {
        I18n.logInfo(logKey);
        cookingPotRecipeManager.loadRecipes();
        cuttingBoardRecipeManager.loadRecipes();
    }

    private void loadRecipeManagersWhenReady(String logKey) {
        if (areCraftEngineItemsReady()) {
            loadRecipeManagers(logKey);
        }
        // 否则：静默延迟处理；CraftEngineReloadEvent 会在 CE 物品加载完成后重试一次。
    }

    public boolean isAdvancementsEnabled() {
        return advancementsEnabled;
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
        queueAdvancementDatapackRemoval(I18n.formatConsole("plugin.datapack_reason_remove_disabled_advancements"));
    }

    // 仅在 CraftEngine 物品加载完成后才构建/刷新进度，否则 icon() 会回退到
    // 原版 Material 图标，而不是使用 CE 物品。
    private void refreshAdvancementSystemWhenReady(boolean reloading) {
        if (!advancementsEnabled || !getServer().getPluginManager().isPluginEnabled("UltimateAdvancementAPI")) {
            disableAdvancementSystem();
            return;
        }
        if (!areCraftEngineItemsReady()) {
            return; // CraftEngineReloadEvent 会在 CE 物品加载完成后重试此操作。
        }
        refreshAdvancementSystem(reloading);
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
            // 运行时缺少 UltimateAdvancementAPI 或版本不兼容 —— 在不启用进度系统的情况下运行。
            advancementManager = null;
            I18n.logWarning("advancement.award_failed", "id", "init", "error", String.valueOf(t.getMessage()));
            return;
        }

        if (achievementListener == null) {
            achievementListener = new AchievementListener();
            getServer().getPluginManager().registerEvents(achievementListener, this);
        }

        // 移除所有旧版进度数据包，避免其原版进度树与 UAA 标签页重复。
        queueAdvancementDatapackRemoval(I18n.formatConsole("plugin.datapack_reason_remove_legacy_advancements"));
    }

    @Override
    public void onLoad() {
        instance = this;
        ensureConfigDefaults();
        I18n.init(this);
        releaseBundledCraftEngineResourcesOnce();
        registerBlockBehaviors();
    }

    @Override
    public void onEnable() {
        enabled = true;

        if (BuildFlags.DEBUG_TOOLS) {
            I18n.logWarning("plugin.debug_tools_build");
        }

        ensureConfigDefaults();
        migrateConfigKeys();
        I18n.init(this);

        scheduler = new SchedulerAdapter(this);
        if (scheduler.isFolia()) {
            I18n.logInfo("plugin.folia_scheduler");
        }

        legacyBlockStorageManager = createLegacyBlockStorageManager();

        loadConfigs();
        logStartupSummary();

        knifeDropHandler = new KnifeDropHandler(this);
        knifeDropHandler.loadConfig();
        getServer().getPluginManager().registerEvents(knifeDropHandler, this);

        cookingPotRecipeManager = new CookingPotRecipeManager(this);

        cuttingBoardRecipeManager = new CuttingBoardRecipeManager(this);

        loadRecipeManagersWhenReady("plugin.loading_recipes");

        blockBreakListener = new BlockBreakListener();
        getServer().getPluginManager().registerEvents(blockBreakListener, this);

        blockPlaceListener = new BlockPlaceListener();
        getServer().getPluginManager().registerEvents(blockPlaceListener, this);
        getServer().getPluginManager().registerEvents(new SkilletPlaceListener(), this);
        getServer().getPluginManager().registerEvents(new SkilletAttackSoundListener(), this);
        getServer().getPluginManager().registerEvents(new CuttingBoardInteractListener(), this);

        strawDropListener = new StrawDropListener(this);
        getServer().getPluginManager().registerEvents(strawDropListener, this);

        getServer().getPluginManager().registerEvents(new RicePlantListener(this), this);
        getServer().getPluginManager().registerEvents(new UpperHalfLootRelayListener(), this);

        // 在食用 FD 菜肴时授予 master_chef 条件，并在配置启用时应用 comfort/nourishment 效果。
        foodEatListener = new FoodEatListener(this);
        getServer().getPluginManager().registerEvents(foodEatListener, this);

        petFoodListener = new PetFoodListener(this);
        getServer().getPluginManager().registerEvents(petFoodListener, this);
        horseFeedTemptListener = new HorseFeedTemptListener(this);
        getServer().getPluginManager().registerEvents(horseFeedTemptListener, this);
        horseFeedTemptListener.start();
        effectListener = new EffectListener(this);
        effectListener.start();

        getServer().getPluginManager().registerEvents(this, this);

        tickManager = new TickManager(this);
        tickManager.start();

        itemDisplayManager = new ProxyItemDisplayManager(this);
        if (itemDisplayManager.isAvailable()) {
            I18n.logInfo("plugin.proxy_display_enabled");
        } else {
            I18n.logWarning("plugin.proxy_display_unavailable");
        }

        stoveManager = new StoveManager(this);
        skilletManager = new SkilletManager(this);
        trayManager = new TrayManager(this);
        getServer().getPluginManager().registerEvents(new StoveInteractListener(), this);
        getServer().getPluginManager().registerEvents(new AutoTrayFurnitureListener(this), this);

        getServer().getPluginManager().registerEvents(new RopeBlockListener(this), this);

        chunkLoadListener = new ChunkLoadListener(this);
        getServer().getPluginManager().registerEvents(chunkLoadListener, this);
        chunkLoadListener.loadAlreadyLoadedChunks();

        refreshAdvancementSystemWhenReady(false);

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

        CraftEngineStateUsageMonitor.logRealStateUsage(this, "startup");

        I18n.logInfo("plugin.enabled");
    }

    @Override
    public void onDisable() {
        enabled = false;
        boolean folia = scheduler != null && scheduler.isFolia();

        runDisableStep("plugin.disable_step_stop_tick_manager", () -> {
            if (tickManager != null) {
                tickManager.stop();
                tickManager = null;
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
            // 编辑器监听器会在下方通过 HandlerList 取消注册；重置其标志位，以便软重启
            // 重新注册一个全新的监听器。
            com.huidu.farmersdelight.gui.editor.RecipeEditorListener.reset();
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

        runDisableStep("plugin.disable_step_cleanup_item_displays", () -> {
            if (itemDisplayManager != null) {
                itemDisplayManager.cleanup();
                itemDisplayManager = null;
            }
        });

        runDisableStep("plugin.disable_step_shutdown_legacy_block_storage", () -> {
            if (legacyBlockStorageManager != null) {
                legacyBlockStorageManager.shutdown();
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

        runDisableStep("plugin.disable_step_unregister_listeners", () -> HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this));

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
        strawDropConfig = null;

        legacyBlockStorageManager = null;
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

    @org.bukkit.event.EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        saveWorldBlockData(event.getWorld());

        UUID worldId = event.getWorld().getUID();
        CookingPotBlockBehavior.cleanupWorld(worldId);
        CuttingBoardBlockBehavior.cleanupWorld(worldId);
        SkilletBlockBehavior.cleanupWorld(worldId);
        StoveCookingBlockBehavior.cleanupWorld(worldId);

        if (legacyBlockStorageManager != null) {
            legacyBlockStorageManager.cleanupWorld(worldId);
        }
        if (trayManager != null) {
            trayManager.cleanupWorld(worldId);
        }
        if (itemDisplayManager != null) {
            // 移除已卸载世界的所有代理显示实体，避免残留条目一直留在 displays 映射中，
            // 直到某个可能永远不会触发的区块卸载事件才被清理。
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
        // 进度通过数据包发送；不进行按世界的数据包同步。
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
                updated = syncAdvancementDatapack(datapackRoot, worldName);
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
                removed = removeAdvancementDatapack(datapackRoot);
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
            I18n.logInfo("plugin.craftengine_reload");
            refreshAfterCraftEngineReload();
            loadRecipeManagersWhenReady("plugin.refreshing_recipes_after_ce");
            // CE 物品现已加载：（重新）构建进度，使图标使用 CE 物品。
            refreshAdvancementSystemWhenReady(true);
            CraftEngineStateUsageMonitor.logRealStateUsage(this, I18n.formatConsole("plugin.craftengine_reload_reason"));
        }, 1L);
    }

    public void reloadRecipesWhenReady(String reason) {
        loadRecipeManagersWhenReady(reason);
    }

    private void refreshAfterCraftEngineReload() {
        RecipeViewGui.clearConfigCache();
        StoveCookingBlockBehavior.clearRecipeCache();
        clearLegacySkilletRecipeCache();

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

    public void reloadAll() {
        ensureConfigDefaults();
        reloadConfig();
        migrateConfigKeys();
        boolean previousAdvancementsEnabled = advancementsEnabled;
        loadConfigs();
        I18n.reload();
        RecipeViewGui.clearConfigCache();
        StoveCookingBlockBehavior.clearRecipeCache();
        clearLegacySkilletRecipeCache();

        if (knifeDropHandler != null) {
            knifeDropHandler.loadConfig(false);
        }
        if (trayManager != null) {
            trayManager.reload();
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
        if (foodEatListener != null) {
            foodEatListener.reload();
        }
        if (horseFeedTemptListener != null) {
            horseFeedTemptListener.reload();
        }
        if (previousAdvancementsEnabled != advancementsEnabled) {
            refreshAdvancementSystem(true);
        }
        reloadRecipesWhenReady("plugin.reloading_recipes");

        I18n.logInfo("plugin.configuration_reloaded");
    }

    public void reloadMainConfigOnly() {
        ensureConfigDefaults();
        reloadConfig();
        migrateConfigKeys();
        boolean previousAdvancementsEnabled = advancementsEnabled;
        loadConfigs();
        RecipeViewGui.clearConfigCache();
        StoveCookingBlockBehavior.clearRecipeCache();
        clearLegacySkilletRecipeCache();

        if (knifeDropHandler != null) {
            knifeDropHandler.loadConfig(false);
        }
        if (trayManager != null) {
            trayManager.reload();
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
        if (foodEatListener != null) {
            foodEatListener.reload();
        }
        if (horseFeedTemptListener != null) {
            horseFeedTemptListener.reload();
        }
        if (previousAdvancementsEnabled != advancementsEnabled) {
            refreshAdvancementSystem(true);
        }
        I18n.logInfo("plugin.main_configuration_reloaded");
    }

    public void reloadGuiConfig() {
        ensureConfigDefaults();
        guiConfig = loadGuiConfig();
        ConfigurationSection cookingPotSection = guiConfig.getConfigurationSection("cooking-pot-gui");
        cookingPotGuiConfig = cookingPotSection != null
                ? GuiConfig.fromConfig(cookingPotSection)
                : GuiConfig.createDefault();
        customCookingPotGuiConfigs = loadCustomCookingPotGuiConfigs(guiConfig);
        recipeEditorGuiConfig = RecipeEditorGuiConfig.fromConfig(guiConfig);
        RecipeViewGui.clearConfigCache();
        CookingPotGui.closeAllOpenGuis();
        RecipeViewGui.closeAllOpenGuis();
        I18n.logInfo("plugin.gui_configuration_reloaded", "file", "gui.yml");
    }

    public void reloadLanguageFiles() {
        I18n.reload();
        // GUI 物品名称/lore 来源于语言文件并被缓存，因此需使这些缓存失效，
        // 并关闭已打开的 GUI，以强制用新语言重新构建。
        RecipeViewGui.clearConfigCache();
        CookingPotGui.closeAllOpenGuis();
        RecipeViewGui.closeAllOpenGuis();
        I18n.logInfo("plugin.language_files_reloaded");
    }

    public void reloadRecipeFiles() {
        refreshAfterCraftEngineReload();
        reloadRecipesWhenReady("plugin.reloading_recipes");
        I18n.logInfo("plugin.recipe_files_reloaded");
    }

    public void reloadAdvancements() {
        refreshAdvancementSystem(true);
        I18n.logInfo("plugin.advancement_data_reloaded");
    }

    private void loadConfigs() {
        advancementsEnabled = getConfig().getBoolean("advancements.enabled", true);
        debugEnabled = getConfig().getBoolean("debug", false)
                || getConfig().getBoolean("debug.enabled", false);
        debugCategories = getConfig().getStringList("debug.categories").stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        knifeItemIds = getConfig().getStringList("knife-config.items").stream()
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        knifeTagIds = getConfig().getStringList("knife-config.tags").stream()
                .map(String::trim)
                .map(s -> s.startsWith("#") ? s.substring(1) : s)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (knifeTagIds.isEmpty()) {
            knifeTagIds = Set.of(Constants.TAG_KNIVES.toLowerCase(Locale.ROOT));
        }

        ConfigurationSection heatSourceSection = getConfig().getConfigurationSection("heat-sources");
        heatSourceConfig = new HeatSourceConfig();
        HeatSourceConfig.setLogger(getLogger());
        heatSourceConfig.loadDefaults();
        if (heatSourceSection != null) {
            heatSourceConfig.loadFromConfig(heatSourceSection);
        }

        guiConfig = loadGuiConfig();
        ConfigurationSection cookingPotSection = guiConfig.getConfigurationSection("cooking-pot-gui");
        cookingPotGuiConfig = cookingPotSection != null
                ? GuiConfig.fromConfig(cookingPotSection)
                : GuiConfig.createDefault();
        customCookingPotGuiConfigs = loadCustomCookingPotGuiConfigs(guiConfig);
        recipeEditorGuiConfig = RecipeEditorGuiConfig.fromConfig(guiConfig);

        ConfigurationSection strawDropSection = getConfig().getConfigurationSection("straw-drops");
        strawDropConfig = new StrawDropConfig();
        strawDropConfig.loadDefaults();
        if (strawDropSection != null) {
            strawDropConfig.loadFromConfig(strawDropSection);
        }

        ConfigurationSection petFoodSection = getConfig().getConfigurationSection("pet-foods");
        petFoodConfig = new PetFoodConfig();
        if (petFoodSection != null) {
            petFoodConfig.loadFromConfig(petFoodSection);
        }

        ConfigurationSection containerReturnSection = getFirstConfigSection("cooking-pot.container-returns", "container-returns");
        containerReturnConfig = new ContainerReturnConfig();
        containerReturnConfig.loadDefaults();
        if (containerReturnSection != null) {
            containerReturnConfig.loadFromConfig(containerReturnSection);
        }

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
        cookingPotExperienceRewardConfig.loadFromConfig(getConfig().getConfigurationSection("cooking-pot.experience-reward"));
        cuttingBoardInteractionMode = normalizeCuttingBoardInteractionMode(
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
        skilletConductorsAllowed = getConfigBoolean(false,
                "skillet.heat.allow-conductors",
                "heat-sources.skillet.allow-conductors");
        skilletDisplayScale = skilletDisplayConfig.getDefaultUniformScale(0.5F);
        skilletDisplayYOffset = skilletDisplayConfig.getDefaultOffset().y();
        skilletDisplaySpread = skilletDisplayConfig.getItemSpread();
        stoveDisplayScale = stoveDisplayConfig.getDefaultUniformScale(0.375F);
    }

    public ConfigurationSection getFirstConfigSection(String... paths) {
        for (String path : paths) {
            ConfigurationSection section = getConfig().getConfigurationSection(path);
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    public boolean getConfigBoolean(boolean defaultValue, String... paths) {
        for (String path : paths) {
            if (getConfig().contains(path)) {
                return getConfig().getBoolean(path, defaultValue);
            }
        }
        return defaultValue;
    }

    public double getConfigDouble(double defaultValue, String... paths) {
        for (String path : paths) {
            if (getConfig().contains(path)) {
                return getConfig().getDouble(path, defaultValue);
            }
        }
        return defaultValue;
    }

    public int getConfigInt(int defaultValue, String... paths) {
        for (String path : paths) {
            if (getConfig().contains(path)) {
                return getConfig().getInt(path, defaultValue);
            }
        }
        return defaultValue;
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
        return "offhand".equals(cuttingBoardInteractionMode);
    }

    public boolean isCuttingBoardStackingEnabled() {
        return "stacking".equals(cuttingBoardInteractionMode);
    }

    public String getCuttingBoardInteractionMode() {
        return cuttingBoardInteractionMode;
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

    private void ensureConfigDefaults() {
        Path dataFolder = getDataFolder().toPath();
        Path configPath = dataFolder.resolve("config.yml");
        Path guiPath = dataFolder.resolve("gui.yml");
        try {
            Files.createDirectories(dataFolder);
            if (Files.notExists(configPath)) {
                writeBundledConfig(configPath);
            }
            writeBundledResourceIfMissing("gui.yml", guiPath);

            if (shouldRestoreConfig(configPath)) {
                backupBrokenConfig(configPath);
                writeBundledConfig(configPath);
                getLogger().warning("Restored unreadable config file from bundled defaults: config.yml");
            }
            if (shouldRestoreConfig(guiPath)) {
                backupBrokenConfig(guiPath);
                writeBundledResource("gui.yml", guiPath, true);
                getLogger().warning("Restored unreadable config file from bundled defaults: gui.yml");
            }
        } catch (IOException e) {
            getLogger().warning("Failed to prepare bundled config files: " + e.getMessage());
        }
    }

    private void migrateConfigKeys() {
        boolean changed = false;
        for (String[] migration : CONFIG_KEY_MIGRATIONS) {
            changed |= migrateConfigSection(migration[0], migration[1]);
        }
        if (changed) {
            saveConfig();
            reloadConfig();
        }
    }

    private boolean migrateConfigSection(String oldPath, String newPath) {
        if (getConfig().isSet(newPath) || !getConfig().isSet(oldPath)) {
            return false;
        }
        ConfigurationSection oldSection = getConfig().getConfigurationSection(oldPath);
        if (oldSection != null) {
            ConfigurationSection newSection = getConfig().createSection(newPath);
            copyConfigSection(oldSection, newSection);
        } else {
            getConfig().set(newPath, getConfig().get(oldPath));
        }
        getConfig().set(oldPath, null);
        getLogger().info("Migrated config key '" + oldPath + "' to '" + newPath + "'.");
        return true;
    }

    private void copyConfigSection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                copyConfigSection(child, target.createSection(key));
            } else {
                target.set(key, source.get(key));
            }
        }
    }

    private boolean shouldRestoreConfig(Path configPath) {
        if (!isYamlReadable(configPath)) {
            return true;
        }
        try {
            return Files.readString(configPath, StandardCharsets.UTF_8).indexOf('\uFFFD') >= 0;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean isYamlReadable(Path configPath) {
        try {
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(configPath), StandardCharsets.UTF_8)) {
                yaml.load(reader);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void backupBrokenConfig(Path configPath) throws IOException {
        String fileName = configPath.getFileName().toString();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String backupName = fileName + "." + timestamp + ".bak";
        Files.copy(configPath, configPath.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeBundledConfig(Path configPath) throws IOException {
        writeBundledResource("config.yml", configPath, true);
    }

    private void writeBundledResourceIfMissing(String resourcePath, Path targetPath) throws IOException {
        if (Files.notExists(targetPath)) {
            writeBundledResource(resourcePath, targetPath, false);
        }
    }

    private void writeBundledResource(String resourcePath, Path targetPath, boolean replace) throws IOException {
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Bundled " + resourcePath + " was not found in the plugin jar.");
            }
            String content = decodeUtf8Resource(inputStream.readAllBytes(), resourcePath);
            if (isYamlResource(resourcePath) && !isYamlContentReadable(content)) {
                throw new IOException("Bundled " + resourcePath + " is not valid YAML.");
            }
            writeStringAtomically(targetPath, content, replace);
        }
    }

    private String decodeUtf8Resource(byte[] bytes, String resourcePath) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Bundled " + resourcePath + " is not valid UTF-8.", e);
        }
    }

    private boolean isYamlResource(String resourcePath) {
        return resourcePath != null
                && (resourcePath.equals("config.yml") || resourcePath.equals("gui.yml"));
    }

    private boolean isYamlContentReadable(String content) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(content);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void writeStringAtomically(Path targetPath, String content, boolean replace) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!replace && Files.exists(targetPath)) {
            throw new IOException("Target already exists: " + targetPath);
        }

        Path tempFile = parent == null
                ? Files.createTempFile(targetPath.getFileName().toString(), ".tmp")
                : Files.createTempFile(parent, targetPath.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                if (replace) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException ignored) {
                if (replace) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(tempFile, targetPath);
                }
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private void releaseBundledCraftEngineResourcesOnce() {
        Path pluginsFolder = getDataFolder().toPath().getParent();
        if (pluginsFolder == null) {
            I18n.logWarning("plugin.craftengine_resources_release_failed",
                    "error", "Unable to resolve the plugins folder.");
            return;
        }

        Path targetRoot = pluginsFolder.resolve(CRAFTENGINE_RESOURCE_TARGET);
        try {
            int copiedFiles;
            if (Files.exists(targetRoot)) {
                // 首次释放是无条件进行的；该开关仅控制在后续启动时是否重新补全缺失的文件。
                if (!getConfig().getBoolean("craftengine-resources.auto-completion", true)) {
                    return;
                }
                copiedFiles = copyMissingBundledResourceFiles(CRAFTENGINE_RESOURCE_ROOT, targetRoot);
            } else {
                copiedFiles = copyBundledResourceDirectory(CRAFTENGINE_RESOURCE_ROOT, targetRoot);
            }
            if (copiedFiles > 0) {
                I18n.logInfo("plugin.craftengine_resources_released",
                        "path", targetRoot,
                        "count", copiedFiles);
            }
        } catch (IOException e) {
            I18n.logWarning("plugin.craftengine_resources_release_failed", "error", e.getMessage());
        }
    }

    private int copyMissingBundledResourceFiles(String resourceRoot, Path targetRoot) throws IOException {
        List<String> resourcePaths = listBundledResourceFiles(resourceRoot);
        if (resourcePaths.isEmpty()) {
            throw new IOException("No bundled CraftEngine resources found at " + resourceRoot);
        }

        int copiedFiles = 0;
        Files.createDirectories(targetRoot);
        for (String resourcePath : resourcePaths) {
            String relativePath = resourcePath.substring(resourceRoot.length() + 1);
            Path targetPath = resolveSafeChild(targetRoot, relativePath);
            if (Files.exists(targetPath)) {
                continue;
            }

            Files.createDirectories(Objects.requireNonNull(targetPath.getParent(), "targetPath parent"));
            Path tempFile = Files.createTempFile(targetPath.getParent(), "fd-ce-resource-", ".tmp");
            boolean moved = false;
            try (InputStream inputStream = getResource(resourcePath)) {
                if (inputStream == null) {
                    throw new IOException("Bundled " + resourcePath + " was not found in the plugin jar.");
                }
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(tempFile, targetPath);
                }
                moved = true;
                copiedFiles++;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(tempFile);
                }
            }
        }
        return copiedFiles;
    }

    private int copyBundledResourceDirectory(String resourceRoot, Path targetRoot) throws IOException {
        List<String> resourcePaths = listBundledResourceFiles(resourceRoot);
        if (resourcePaths.isEmpty()) {
            throw new IOException("No bundled CraftEngine resources found at " + resourceRoot);
        }

        Path parent = Objects.requireNonNull(targetRoot.getParent(), "targetRoot parent");
        Files.createDirectories(parent);
        Path tempRoot = Files.createTempDirectory(parent, targetRoot.getFileName() + "-");
        boolean moved = false;

        try {
            int copiedFiles = 0;
            for (String resourcePath : resourcePaths) {
                String relativePath = resourcePath.substring(resourceRoot.length() + 1);
                Path targetPath = resolveSafeChild(tempRoot, relativePath);
                Files.createDirectories(Objects.requireNonNull(targetPath.getParent(), "targetPath parent"));
                try (InputStream inputStream = getResource(resourcePath)) {
                    if (inputStream == null) {
                        throw new IOException("Bundled " + resourcePath + " was not found in the plugin jar.");
                    }
                    Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    copiedFiles++;
                }
            }

            try {
                Files.move(tempRoot, targetRoot, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempRoot, targetRoot);
            }
            moved = true;
            return copiedFiles;
        } finally {
            if (!moved) {
                deleteTreeQuietly(tempRoot);
            }
        }
    }

    private Path resolveSafeChild(Path root, String relativePath) throws IOException {
        Path normalizedRoot = root.normalize();
        Path targetPath = normalizedRoot.resolve(relativePath).normalize();
        if (!targetPath.startsWith(normalizedRoot)) {
            throw new IOException("Invalid bundled resource path: " + relativePath);
        }
        return targetPath;
    }

    private List<String> listBundledResourceFiles(String resourceRoot) throws IOException {
        // 主要策略：直接扫描插件 jar 的条目。jar 中不保证一定存在目录条目 ——
        // 经 ProGuard 混淆的 Folia jar（obfuscateFoliaJar）会删除它们 ——
        // 因此 getClassLoader().getResource(<directory>) 会返回 null，下方基于 URL 的遍历
        // 也找不到任何内容（"No bundled CraftEngine resources found"）。按前缀读取文件条目
        // 不受缺失目录条目以及各平台类加载器差异的影响。
        List<String> fromJar = listJarFileResourceFiles(resourceRoot);
        if (fromJar != null) {
            return fromJar;
        }

        // 针对解压目录/IDE/测试运行场景的回退方案，此时插件未被打包为 jar 文件。
        URL resourceUrl = getClass().getClassLoader().getResource(resourceRoot);
        if (resourceUrl == null) {
            return List.of();
        }

        try {
            URI resourceUri = resourceUrl.toURI();
            if ("file".equals(resourceUrl.getProtocol())) {
                return listFileResourceFiles(resourceRoot, Path.of(resourceUri));
            }
            if ("jar".equals(resourceUrl.getProtocol())) {
                return listJarResourceFiles(resourceRoot, resourceUri);
            }
            throw new IOException("Unsupported bundled resource protocol: " + resourceUrl.getProtocol());
        } catch (URISyntaxException e) {
            throw new IOException("Invalid bundled resource URI for " + resourceRoot, e);
        }
    }

    /**
     * 通过扫描插件 jar 的条目，列出 resourceRoot 下的内置资源文件。
     * 当插件不是从可读的 jar 文件运行时（例如解压的 IDE/测试运行），返回 null
     * （而非空列表），以便调用方回退到基于类加载器的发现方式。
     */
    private List<String> listJarFileResourceFiles(String resourceRoot) throws IOException {
        File pluginJar = getFile();
        if (pluginJar == null || !pluginJar.isFile()) {
            return null;
        }

        String prefix = resourceRoot.endsWith("/") ? resourceRoot : resourceRoot + "/";
        List<String> resourcePaths = new ArrayList<>();
        try (JarFile jarFile = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith(prefix)) {
                    resourcePaths.add(name);
                }
            }
        }
        resourcePaths.sort(Comparator.naturalOrder());
        return resourcePaths;
    }

    private List<String> listFileResourceFiles(String resourceRoot, Path rootPath) throws IOException {
        try (Stream<Path> stream = Files.walk(rootPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(rootPath::relativize)
                    .map(path -> resourceRoot + "/" + path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private List<String> listJarResourceFiles(String resourceRoot, URI resourceUri) throws IOException {
        String uriText = resourceUri.toString();
        int separatorIndex = uriText.indexOf("!/");
        if (separatorIndex < 0) {
            throw new IOException("Invalid jar resource URI: " + resourceUri);
        }

        URI jarUri = URI.create(uriText.substring(0, separatorIndex));
        FileSystem fileSystem = null;
        boolean closeFileSystem = false;

        try {
            try {
                fileSystem = FileSystems.newFileSystem(jarUri, Map.of());
                closeFileSystem = true;
            } catch (FileSystemAlreadyExistsException ignored) {
                fileSystem = FileSystems.getFileSystem(jarUri);
            }

            Path rootPath = fileSystem.getPath("/" + resourceRoot);
            try (Stream<Path> stream = Files.walk(rootPath)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(rootPath::relativize)
                        .map(path -> resourceRoot + "/" + path.toString().replace('\\', '/'))
                        .sorted()
                        .toList();
            }
        } finally {
            if (closeFileSystem && fileSystem != null) {
                fileSystem.close();
            }
        }
    }

    private void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
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

    private void registerBlockBehaviors() {
        registerBehavior(Constants.BEHAVIOR_COOKING_POT, CookingPotBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_CUTTING_BOARD, CuttingBoardBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_SKILLET, SkilletBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_STOVE, StoveCookingBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TALL_CROP, TallCropBlockBehavior.FACTORY);
        // 暂时搁置的开发中功能（tatami 配对）—— 在完成之前不注册该 behavior。
        // registerBehavior(Constants.BEHAVIOR_TATAMI, TatamiPairingBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_UPPER_HALF_LOOT_RELAY, UpperHalfLootRelayBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_RICE, WildRiceBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_ROPE, RopeBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_MUSHROOM_COLONY, MushroomColonyBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_PLANT, WildPlantBlockBehavior.FACTORY);

        getLogger().info(I18n.formatConsole("plugin.registered_block_behaviors"));
    }

    private void registerBehavior(String key, BlockBehaviorFactory<?> factory) {
        Key keyObj = Key.of(key);
        if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(keyObj) == null) {
            BlockBehaviors.register(keyObj, factory);
        }
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

    private LegacyBlockStorageManager createLegacyBlockStorageManager() {
        Path legacyStoragePath = getDataFolder().toPath().resolve("block_storage.yml");
        if (Files.notExists(legacyStoragePath)) {
            return null;
        }
        I18n.logInfo("plugin.legacy_storage_found");
        return new LegacyBlockStorageManager(this);
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

    public LegacyBlockStorageManager getLegacyBlockStorageManager() {
        return legacyBlockStorageManager;
    }

    public KnifeDropHandler getKnifeDrops() {
        if (knifeDropHandler == null) {
            throw new IllegalStateException("Plugin is not enabled");
        }
        return knifeDropHandler;
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

    public StoveManager getStoveManager() {
        return stoveManager;
    }

    public SkilletManager getSkilletManager() {
        return skilletManager;
    }

    public AdvancementManager getAdvancementManager() {
        return advancementManager;
    }

    public ItemDisplayManager getItemDisplayManager() {
        return itemDisplayManager;
    }

    public ItemDisplayManager getProxyItemDisplayManager() {
        return getItemDisplayManager();
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

    private String normalizeCuttingBoardInteractionMode(String value) {
        String mode = value == null ? "stacking" : value.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("stacking") && !mode.equals("offhand")) {
            I18n.logWarning("plugin.invalid_cutting_board_mode", "value", value);
            return "stacking";
        }
        return mode;
    }

    private void logStartupSummary() {
        logConfigSummary(I18n.formatConsole("plugin.startup_config"));
    }

    private void logConfigSummary(String label) {
        I18n.logInfo("plugin.config_summary",
                "label", label,
                "mode", cuttingBoardInteractionMode,
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
        // 已缓存：最多只读取一次 server.properties。
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

    private boolean syncAdvancementDatapack(Path datapackRoot, String worldName) {
        if (datapackRoot == null || worldName == null || worldName.isBlank()) {
            return false;
        }

        boolean updated = pruneObsoleteAdvancementFiles(datapackRoot);

        for (String resourcePath : ADVANCEMENT_RESOURCES) {
            Path target = mapAdvancementResourceTarget(datapackRoot, resourcePath);
            try {
                updated |= copyResourceIfChanged(resourcePath, target);
            } catch (IOException e) {
                I18n.logWarning("plugin.advancement_resource_sync_failed",
                        "resource", resourcePath,
                        "world", worldName,
                        "error", e.getMessage());
            }
        }

        return updated;
    }

    private boolean removeAdvancementDatapack(Path datapackRoot) {
        if (datapackRoot == null || !Files.exists(datapackRoot)) {
            return false;
        }

        boolean removed = false;
        removed |= deleteIfExists(datapackRoot.resolve("pack.mcmeta"));
        removed |= deleteTree(datapackRoot.resolve(Path.of("data", "farmersdelight")));

        pruneEmptyDirectories(datapackRoot.resolve("data"), datapackRoot);
        deleteEmptyDirectory(datapackRoot);
        return removed;
    }

    private boolean deleteIfExists(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            I18n.logWarning("plugin.advancement_file_delete_failed", "path", path, "error", e.getMessage());
            return false;
        }
    }

    private boolean deleteTree(Path root) {
        if (!Files.exists(root)) {
            return false;
        }
        boolean removed = false;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                removed |= deleteIfExists(path);
            }
        } catch (IOException e) {
            I18n.logWarning("plugin.advancement_directory_delete_failed", "path", root, "error", e.getMessage());
        }
        return removed;
    }

    private void pruneEmptyDirectories(Path start, Path boundary) {
        Path current = start;
        while (current != null && !current.equals(boundary)) {
            if (!deleteEmptyDirectory(current)) {
                return;
            }
            current = current.getParent();
        }
    }

    private boolean deleteEmptyDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                return false;
            }
            Files.deleteIfExists(directory);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean pruneObsoleteAdvancementFiles(Path datapackRoot) {
        Set<Path> expectedFiles = ADVANCEMENT_RESOURCES.stream()
                .filter(path -> path.startsWith("advancements/data/farmersdelight/advancement/main/"))
                .map(path -> mapAdvancementResourceTarget(datapackRoot, path).normalize())
                .collect(Collectors.toSet());

        boolean updated = false;
        for (Path mainDir : List.of(
                datapackRoot.resolve(Path.of("data", "farmersdelight", "advancements", "main")),
                datapackRoot.resolve(Path.of("data", "farmersdelight", "advancement", "main"))
        )) {
            if (!Files.isDirectory(mainDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(mainDir)) {
                for (Path file : stream.toList()) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    Path normalized = file.normalize();
                    if (!expectedFiles.contains(normalized)) {
                        Files.deleteIfExists(normalized);
                        updated = true;
                    }
                }
            } catch (IOException e) {
                I18n.logWarning("plugin.advancement_prune_failed", "path", mainDir, "error", e.getMessage());
            }
        }
        return updated;
    }

    private Path mapAdvancementResourceTarget(Path datapackRoot, String resourcePath) {
        String relativePath = resourcePath.substring("advancements/".length());
        return datapackRoot.resolve(relativePath);
    }

    private boolean copyResourceIfChanged(String resourcePath, Path target) throws IOException {
        try (InputStream input = getResource(resourcePath)) {
            if (input == null) {
                I18n.logWarning("plugin.advancement_resource_missing", "resource", resourcePath);
                return false;
            }

            byte[] newBytes = input.readAllBytes();
            if (Files.exists(target)) {
                byte[] existingBytes = Files.readAllBytes(target);
                if (java.util.Arrays.equals(existingBytes, newBytes)) {
                    return false;
                }
            }

            Files.createDirectories(Objects.requireNonNull(target.getParent()));
            Path tempFile = Files.createTempFile(target.getParent(), "fd-adv", ".tmp");
            try {
                Files.write(tempFile, newBytes);
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempFile);
            }
            return true;
        }
    }
}
