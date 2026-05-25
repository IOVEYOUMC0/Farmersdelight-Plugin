package com.huidu.farmersdelight;

import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.*;
import com.huidu.farmersdelight.listener.*;
import com.huidu.farmersdelight.command.FarmersDelightCommand;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.config.StrawDropConfig;
import com.huidu.farmersdelight.effect.EffectListener;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.gui.GuiConfig;
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
import com.huidu.farmersdelight.storage.BlockStorageManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.InteractionDebouncer;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import com.huidu.farmersdelight.visual.ProxyItemDisplayManager;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FarmersDelightPlugin extends JavaPlugin implements Listener {

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
            "advancements/data/farmersdelight/advancement/main/eat_comfort_food.json",
            "advancements/data/farmersdelight/advancement/main/place_feast.json",
            "advancements/data/farmersdelight/advancement/main/use_cutting_board.json",
            "advancements/data/farmersdelight/advancement/main/plant_rice.json",
            "advancements/data/farmersdelight/advancement/main/plant_all_crops.json",
            "advancements/data/farmersdelight/advancement/main/get_ham.json",
            "advancements/data/farmersdelight/advancement/main/eat_nourishing_food.json",
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
    private BlockStorageManager blockStorageManager;
    private TickManager tickManager;
    private TrayManager trayManager;
    private StoveManager stoveManager;
    private SkilletManager skilletManager;
    private ItemDisplayManager itemDisplayManager;
    private KnifeDropHandler knifeDropHandler;
    private CookingPotRecipeManager cookingPotRecipeManager;
    private CuttingBoardRecipeManager cuttingBoardRecipeManager;
    private BlockBreakListener blockBreakListener;
    private BlockPlaceListener blockPlaceListener;
    private StrawDropListener strawDropListener;
    private ChunkLoadListener chunkLoadListener;
    private FoodEatListener foodEatListener;
    private PetFoodListener petFoodListener;
    private HorseFeedTemptListener horseFeedTemptListener;
    private AchievementListener achievementListener;
    private EffectListener effectListener;

    private HeatSourceConfig heatSourceConfig;
    private GuiConfig cookingPotGuiConfig;
    private YamlConfiguration guiConfig;
    private StrawDropConfig strawDropConfig;
    private PetFoodConfig petFoodConfig;
    private ContainerReturnConfig containerReturnConfig;
    private CuttingBoardDisplayConfig cuttingBoardDisplayConfig;
    private AdvancementManager advancementManager;
    private boolean advancementsEnabled;
    private boolean debugEnabled;
    private boolean showRecipeNameInProgressDisplay;
    private String cuttingBoardInteractionMode;
    private boolean hopperInteractionsEnabled;
    private boolean cookingPotHopperInteractionsEnabled;
    private boolean cuttingBoardHopperInteractionsEnabled;
    private boolean skilletConductorsAllowed;
    private float skilletDisplayScale = 0.5F;
    private float stoveDisplayScale = 0.375F;
    private Set<String> knifeItemIds = Set.of();
    private Set<String> knifeTagIds = Set.of();
    private Set<String> debugCategories = Set.of();

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

    private void loadRecipeManagers(String logMessage) {
        getLogger().info(logMessage);
        cookingPotRecipeManager.loadRecipes();
        cuttingBoardRecipeManager.loadRecipes();
    }

    private void loadRecipeManagersWhenReady(String logMessage) {
        if (areCraftEngineItemsReady()) {
            loadRecipeManagers(logMessage);
        } else {
            getLogger().info("Skipping recipe load for now because CraftEngine custom items are not ready yet.");
        }
    }

    public boolean isAdvancementsEnabled() {
        return advancementsEnabled;
    }

    private void disableAdvancementSystem() {
        if (achievementListener != null) {
            HandlerList.unregisterAll(achievementListener);
            achievementListener = null;
        }
        advancementManager = null;
        queueAdvancementDatapackRemoval("remove disabled FarmersDelight advancements");
    }

    private void refreshAdvancementSystem(boolean reloading) {
        if (!advancementsEnabled) {
            disableAdvancementSystem();
            return;
        }

        if (advancementManager == null) {
            advancementManager = new AdvancementManager(this);
            advancementManager.load();
        } else if (reloading) {
            advancementManager.reload();
        }

        if (achievementListener == null) {
            achievementListener = new AchievementListener();
            getServer().getPluginManager().registerEvents(achievementListener, this);
        }

        queueDatapackSync(reloading
                ? "apply FarmersDelight advancement config changes"
                : "apply FarmersDelight advancements");
    }

    @Override
    public void onLoad() {
        instance = this;
        registerBlockBehaviors();
    }

    @Override
    public void onEnable() {
        enabled = true;

        if (BuildFlags.DEBUG_TOOLS) {
            getLogger().warning("This FarmersDelight build includes debug tools and test-only command paths.");
        }

        ensureConfigDefaults();
        I18n.init(this);

        scheduler = new SchedulerAdapter(this);
        if (scheduler.isFolia()) {
            getLogger().info("Folia scheduler detected; FarmersDelight will route scheduled work through region/global schedulers.");
        }

        blockStorageManager = new BlockStorageManager(this);

        loadConfigs();
        logStartupSummary();

        knifeDropHandler = new KnifeDropHandler(this);
        knifeDropHandler.loadConfig();
        getServer().getPluginManager().registerEvents(knifeDropHandler, this);

        cookingPotRecipeManager = new CookingPotRecipeManager(this);

        cuttingBoardRecipeManager = new CuttingBoardRecipeManager(this);

        loadRecipeManagersWhenReady("Loading recipes...");

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
            getLogger().info("CraftEngine proxy item displays enabled for stove and skillet.");
        } else {
            getLogger().warning("CraftEngine proxy item display pipeline unavailable, stove and skillet visuals will be disabled.");
        }

        stoveManager = new StoveManager(this);
        skilletManager = new SkilletManager(this);
        trayManager = new TrayManager(this);
        getServer().getPluginManager().registerEvents(new StoveInteractListener(), this);
        getServer().getPluginManager().registerEvents(new AutoTrayFurnitureListener(this), this);

        getServer().getPluginManager().registerEvents(new RopeBlockListener(this), this);
        getServer().getPluginManager().registerEvents(new RopeFallListener(), this);

        chunkLoadListener = new ChunkLoadListener(this);
        getServer().getPluginManager().registerEvents(chunkLoadListener, this);
        chunkLoadListener.loadAlreadyLoadedChunks();

        refreshAdvancementSystem(false);

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

        getLogger().info("FarmersDelight plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        enabled = false;
        boolean folia = scheduler != null && scheduler.isFolia();

        runDisableStep("stop tick manager", () -> {
            if (tickManager != null) {
                tickManager.stop();
                tickManager = null;
            }
        });

        runDisableStep("stop effect listener", () -> {
            if (effectListener != null) {
                effectListener.stop();
                effectListener = null;
            }
        });

        runDisableStep("stop horse feed tempt listener", () -> {
            if (horseFeedTemptListener != null) {
                horseFeedTemptListener.stop();
                horseFeedTemptListener = null;
            }
        });
        runDisableStep("close cooking pot GUIs", CookingPotGui::cleanupAll);
        runDisableStep("close recipe view GUIs", RecipeViewGui::cleanupAll);
        runDisableStep("save block data", this::saveAllBlockData);

        runDisableStep("clear placement cache", this::cleanupPlacementCache);
        runDisableStep("clear interaction debounce cache", this::cleanupInteractionDebouncer);

        runDisableStep("shutdown chunk loader", () -> {
            if (chunkLoadListener != null) {
                chunkLoadListener.shutdown();
            }
        });

        runDisableStep("cleanup trays", () -> {
            if (trayManager != null) {
                trayManager.cleanupAll();
                trayManager = null;
            }
        });

        runDisableStep("cleanup stoves", () -> {
            if (stoveManager != null) {
                stoveManager.cleanup();
                stoveManager = null;
            }
        });

        runDisableStep("cleanup skillets", () -> {
            if (skilletManager != null) {
                skilletManager.cleanup();
                skilletManager = null;
            }
        });

        runDisableStep("cleanup item displays", () -> {
            if (itemDisplayManager != null) {
                itemDisplayManager.cleanup();
                itemDisplayManager = null;
            }
        });

        runDisableStep("shutdown block storage", () -> {
            if (blockStorageManager != null) {
                blockStorageManager.shutdown();
            }
        });

        runDisableStep("cleanup cooking pot block entities", () -> CookingPotBlockBehavior.cleanupAll(!folia));
        runDisableStep("cleanup cutting board block entities", () -> CuttingBoardBlockBehavior.cleanupAll(!folia));
        runDisableStep("cleanup stove block entities", StoveCookingBlockBehavior::cleanupAll);
        runDisableStep("cleanup skillet block entities", SkilletBlockBehavior::cleanupAll);
        runDisableStep("cleanup tall crops", TallCropBlockBehavior::cleanupAll);
        runDisableStep("cleanup mushroom colonies", MushroomColonyBehavior::cleanupAll);
        runDisableStep("cleanup wild rice blocks", WildRiceBlockBehavior::cleanupAll);
        runDisableStep("clear stove recipe cache", StoveCookingBlockBehavior::clearRecipeCache);
        runDisableStep("clear skillet recipe cache", this::clearLegacySkilletRecipeCache);

        runDisableStep("cancel pending tasks", () -> {
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

        runDisableStep("unregister listeners", () -> HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this));

        runDisableStep("shutdown scheduler", () -> {
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

        heatSourceConfig = null;
        cookingPotGuiConfig = null;
        strawDropConfig = null;

        I18n.cleanup();

        blockStorageManager = null;
        advancementManager = null;

        getLogger().info("FarmersDelight plugin has been disabled!");
    }

    private void runDisableStep(String step, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Failed to " + step + " during disable; continuing shutdown.", throwable);
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

        if (blockStorageManager != null) {
            blockStorageManager.saveAll();
        }
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
                    ceWorld.save();
                }
            } catch (Throwable throwable) {
                getLogger().log(Level.WARNING, "Failed to flush CraftEngine world data for " + world.getName(), throwable);
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

        if (blockStorageManager != null) {
            blockStorageManager.cleanupWorld(worldId);
        }
        if (trayManager != null) {
            trayManager.cleanupWorld(worldId);
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
        queueDatapackSync("syncing FarmersDelight advancements for world " + event.getWorld().getName());
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
                    : "apply FarmersDelight advancement changes";
            pendingDatapackReloadReason = null;
            getLogger().info("Reloading data packs to " + reloadReason + "...");
            getServer().reloadData();
        }, 10L);
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
            getLogger().info("CraftEngine reload detected, refreshing FarmersDelight CE caches and recipes...");
            refreshAfterCraftEngineReload();
            loadRecipeManagersWhenReady("Refreshing recipes after CraftEngine reload...");
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
        boolean previousAdvancementsEnabled = advancementsEnabled;
        loadConfigs();
        I18n.reload();
        RecipeViewGui.clearConfigCache();
        StoveCookingBlockBehavior.clearRecipeCache();
        clearLegacySkilletRecipeCache();

        if (knifeDropHandler != null) {
            knifeDropHandler.reload();
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
        reloadRecipesWhenReady("Reloading recipes...");

        logReloadSummary("Configuration reloaded");
        getLogger().info("Configuration reloaded!");
    }

    public void reloadMainConfigOnly() {
        ensureConfigDefaults();
        reloadConfig();
        boolean previousAdvancementsEnabled = advancementsEnabled;
        loadConfigs();
        RecipeViewGui.clearConfigCache();
        StoveCookingBlockBehavior.clearRecipeCache();
        clearLegacySkilletRecipeCache();

        if (knifeDropHandler != null) {
            knifeDropHandler.reload();
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
        logReloadSummary("Main configuration reloaded");
        getLogger().info("Main configuration reloaded!");
    }

    public void reloadGuiConfig() {
        ensureConfigDefaults();
        guiConfig = loadGuiConfig();
        ConfigurationSection cookingPotSection = guiConfig.getConfigurationSection("cooking-pot-gui");
        cookingPotGuiConfig = cookingPotSection != null
                ? GuiConfig.fromConfig(cookingPotSection)
                : GuiConfig.createDefault();
        RecipeViewGui.clearConfigCache();
        getLogger().info("GUI configuration reloaded: gui.yml");
    }

    public void reloadLanguageFiles() {
        I18n.reload();
        getLogger().info("Language files reloaded!");
    }

    public void reloadRecipeFiles() {
        refreshAfterCraftEngineReload();
        reloadRecipesWhenReady("Reloading recipes...");
        getLogger().info("Recipe files reloaded!");
    }

    public void reloadAdvancements() {
        refreshAdvancementSystem(true);
        getLogger().info("Advancement data reloaded!");
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

        int autoSaveInterval = getConfig().getInt("storage.auto-save-interval", 300);
        if (autoSaveInterval < 0) {
            getLogger().warning("storage.auto-save-interval cannot be negative, using default 300");
            autoSaveInterval = 300;
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

        ConfigurationSection containerReturnSection = getConfig().getConfigurationSection("container-returns");
        containerReturnConfig = new ContainerReturnConfig();
        containerReturnConfig.loadDefaults();
        if (containerReturnSection != null) {
            containerReturnConfig.loadFromConfig(containerReturnSection);
        }

        cuttingBoardDisplayConfig = new CuttingBoardDisplayConfig();
        cuttingBoardDisplayConfig.loadFromConfig(getConfig().getConfigurationSection("cutting-board"));

        showRecipeNameInProgressDisplay = getConfig().getBoolean("cooking-pot-progress-display.show-recipe-name", false);
        cuttingBoardInteractionMode = normalizeCuttingBoardInteractionMode(
                getConfig().getString("cutting-board.interaction-mode", "stacking")
        );
        hopperInteractionsEnabled = getConfig().getBoolean("hopper-interactions.enabled", false);
        cookingPotHopperInteractionsEnabled = getConfig().getBoolean("hopper-interactions.cooking-pot", true);
        cuttingBoardHopperInteractionsEnabled = getConfig().getBoolean("hopper-interactions.cutting-board", true);
        skilletConductorsAllowed = getConfig().getBoolean("heat-sources.skillet.allow-conductors", false);
        skilletDisplayScale = (float) getConfig().getDouble("display-visuals.skillet.scale", 0.5D);
        stoveDisplayScale = (float) getConfig().getDouble("display-visuals.stove.scale", 0.375D);
    }

    public boolean isShowRecipeNameInProgressDisplay() {
        return showRecipeNameInProgressDisplay;
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

    public boolean isCuttingBoardHopperInteractionsEnabled() {
        return hopperInteractionsEnabled && cuttingBoardHopperInteractionsEnabled;
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

            if (!isYamlReadable(configPath)) {
                backupBrokenConfig(configPath);
                writeBundledConfig(configPath);
                getLogger().warning("Detected an unreadable config.yml; backed up the original as config.yml.<timestamp>.bak and restored the bundled UTF-8 default config.");
            }
            if (!isYamlReadable(guiPath)) {
                backupBrokenConfig(guiPath);
                writeBundledResource("gui.yml", guiPath, true);
                getLogger().warning("Detected an unreadable gui.yml; backed up the original as gui.yml.<timestamp>.bak and restored the bundled UTF-8 default gui config.");
            }
        } catch (IOException e) {
            getLogger().warning("Failed to prepare configuration files: " + e.getMessage());
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
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            if (replace) {
                Files.writeString(
                        targetPath,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            } else {
                Files.writeString(
                        targetPath,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
            }
        }
    }

    private YamlConfiguration loadGuiConfig() {
        Path guiPath = getDataFolder().toPath().resolve("gui.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.notExists(guiPath)) {
            if (isDebugEnabled("config")) {
                getLogger().info("gui.yml is missing, using bundled defaults in memory until reload writes it back.");
            }
            return yaml;
        }
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(guiPath), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (Exception e) {
            getLogger().warning("Failed to load gui.yml: " + e.getMessage());
        }
        return yaml;
    }

    private void registerBlockBehaviors() {
        registerBehavior(Constants.BEHAVIOR_COOKING_POT, CookingPotBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_CUTTING_BOARD, CuttingBoardBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_SKILLET, SkilletBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_STOVE, StoveCookingBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TALL_CROP, TallCropBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_TATAMI, TatamiPairingBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_UPPER_HALF_LOOT_RELAY, UpperHalfLootRelayBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_WILD_RICE, WildRiceBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_ROPE, RopeBlockBehavior.FACTORY);
        registerBehavior(Constants.BEHAVIOR_MUSHROOM_COLONY, MushroomColonyBehavior.FACTORY);

        getLogger().info("Registered custom block behaviors");
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

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public boolean isDebugEnabled(String category) {
        if (!debugEnabled) {
            return false;
        }
        if (debugCategories.isEmpty()) {
            return true;
        }
        if (category == null || category.isBlank()) {
            return false;
        }

        String normalized = category.trim().toLowerCase();
        return debugCategories.contains("*")
                || debugCategories.contains("all")
                || debugCategories.contains(normalized);
    }

    public BlockStorageManager getBlockStorageManager() {
        return blockStorageManager;
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

    public ConfigurationSection getRecipeViewGuiSection() {
        if (guiConfig == null) {
            guiConfig = loadGuiConfig();
        }
        return guiConfig.getConfigurationSection("recipe-view-gui");
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

    public float getStoveDisplayScale() {
        return stoveDisplayScale;
    }

    private String normalizeCuttingBoardInteractionMode(String value) {
        String mode = value == null ? "stacking" : value.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("stacking") && !mode.equals("offhand")) {
            getLogger().warning("Invalid cutting-board.interaction-mode '" + value + "', falling back to stacking.");
            return "stacking";
        }
        return mode;
    }

    private void logStartupSummary() {
        getLogger().info(
                "Startup config: cutting-board mode=" + cuttingBoardInteractionMode
                        + ", hopper=" + hopperInteractionsEnabled
                        + ", cooking-pot-hopper=" + cookingPotHopperInteractionsEnabled
                        + ", cutting-board-hopper=" + cuttingBoardHopperInteractionsEnabled
                        + ", advancements=" + advancementsEnabled
        );
    }

    private void logReloadSummary(String label) {
        getLogger().info(
                label + ": cutting-board mode=" + cuttingBoardInteractionMode
                        + ", hopper=" + hopperInteractionsEnabled
                        + ", cooking-pot-hopper=" + cookingPotHopperInteractionsEnabled
                        + ", cutting-board-hopper=" + cuttingBoardHopperInteractionsEnabled
                        + ", advancements=" + advancementsEnabled
        );
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
            getLogger().fine("Failed to read server.properties level-name: " + e.getMessage());
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
                getLogger().warning("Failed to sync advancement resource " + resourcePath + " for world " + worldName + ": " + e.getMessage());
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
            getLogger().warning("Failed to delete advancement datapack file " + path + ": " + e.getMessage());
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
            getLogger().warning("Failed to delete advancement datapack directory " + root + ": " + e.getMessage());
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
                getLogger().warning("Failed to prune obsolete advancement files in " + mainDir + ": " + e.getMessage());
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
                getLogger().warning("Missing bundled advancement resource: " + resourcePath);
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
