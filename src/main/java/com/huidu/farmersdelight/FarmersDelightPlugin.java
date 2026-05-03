package com.huidu.farmersdelight;

import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.*;
import com.huidu.farmersdelight.listener.*;
import com.huidu.farmersdelight.command.FarmersDelightCommand;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.config.StrawDropConfig;
import com.huidu.farmersdelight.effect.EffectListener;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.loot.KnifeDropHandler;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.visual.FakeItemDisplayManager;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
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
            "advancements/data/farmersdelight/advancement/main/place_feast.json",
            "advancements/data/farmersdelight/advancement/main/use_cutting_board.json",
            "advancements/data/farmersdelight/advancement/main/plant_rice.json",
            "advancements/data/farmersdelight/advancement/main/plant_all_crops.json",
            "advancements/data/farmersdelight/advancement/main/get_ham.json",
            "advancements/data/farmersdelight/advancement/main/eat_nourishing_food.json",
            "advancements/data/farmersdelight/advancement/main/master_chef.json"
    );

    private static FarmersDelightPlugin instance;
    private static volatile boolean enabled = false;
    private final List<String> behaviorRegistryConflicts = new ArrayList<>();
    private boolean shouldDisable = false;
    private volatile boolean startupSyncCompleted = false;
    private volatile boolean datapackSyncQueued = false;
    private volatile boolean datapackRemovalQueued = false;
    private BukkitTask pendingCraftEngineReloadTask;
    private BukkitTask pendingDatapackReloadTask;
    private BukkitTask pendingDatapackSyncRetryTask;
    private String pendingDatapackReloadReason;
    private String pendingDatapackSyncRetryReason;

    private BlockStorageManager blockStorageManager;
    private TickManager tickManager;
    private TrayManager trayManager;
    private StoveManager stoveManager;
    private SkilletManager skilletManager;
    private ItemDisplayManager fakeItemDisplayManager;
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
    private HopperInteractionListener hopperInteractionListener;
    private AchievementListener achievementListener;
    private EffectListener effectListener;

    private HeatSourceConfig heatSourceConfig;
    private GuiConfig cookingPotGuiConfig;
    private StrawDropConfig strawDropConfig;
    private PetFoodConfig petFoodConfig;
    private ContainerReturnConfig containerReturnConfig;
    private AdvancementManager advancementManager;
    private boolean advancementsEnabled;
    private boolean debugEnabled;
    private boolean showRecipeNameInProgressDisplay;
    private Set<String> debugCategories = Set.of();

    public static FarmersDelightPlugin getInstance() {
        return instance;
    }

    public static boolean isEnabled0() {
        return enabled;
    }

    private boolean areCraftEngineItemsReady() {
        try {
            var itemManager = getCraftEngine().itemManager();
            return itemManager.getCustomItem(Key.of(Constants.ITEM_RICE_PANICLE)).isPresent()
                    || itemManager.getBuildableItem(Key.of(Constants.ITEM_RICE_PANICLE)).isPresent();
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
        if (getServer().getPluginManager().getPlugin("CraftEngine") == null) {
            getLogger().severe("CraftEngine not found! This plugin requires CraftEngine to function.");
            getLogger().severe("Please install CraftEngine before using FarmersDelight.");
            shouldDisable = true;
            return;
        }
        instance = this;
        registerBlockBehaviors();
    }

    @Override
    public void onEnable() {
        if (shouldDisable) {
            getLogger().severe("Plugin disabled due to missing CraftEngine dependency.");
            if (!behaviorRegistryConflicts.isEmpty()) {
                for (String conflict : behaviorRegistryConflicts) {
                    getLogger().severe(conflict);
                }
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (isLateCraftEngineLoad()) {
            getLogger().severe("FarmersDelight was loaded after CraftEngine had already finished initializing.");
            getLogger().severe("This late load path is unsupported because custom block behaviors will not be wired into already-loaded CraftEngine content.");
            getLogger().severe("Restart the server to load FarmersDelight together with CraftEngine. Do not use PlugMan to dynamically load this plugin after startup.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        enabled = true;

        ensureConfigDefaults();
        I18n.init(this);

        blockStorageManager = new BlockStorageManager(this);

        loadConfigs();

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
        horseFeedTemptListener.start();
        hopperInteractionListener = new HopperInteractionListener(this);
        hopperInteractionListener.start();

        effectListener = new EffectListener(this);
        effectListener.start();

        getServer().getPluginManager().registerEvents(this, this);

        tickManager = new TickManager(this);
        tickManager.start();

        fakeItemDisplayManager = new FakeItemDisplayManager(this);
        if (fakeItemDisplayManager.isAvailable()) {
            getLogger().info("CraftEngine FastNMS detected, using CE packet-based item displays for stove and skillet.");
        } else {
            getLogger().warning("CraftEngine FastNMS item display pipeline unavailable, stove and skillet visuals will be disabled.");
        }

        stoveManager = new StoveManager(this);
        skilletManager = new SkilletManager(this);
        trayManager = new TrayManager(this);
        getServer().getPluginManager().registerEvents(new AutoTrayFurnitureListener(this), this);

        chunkLoadListener = new ChunkLoadListener(this);
        getServer().getPluginManager().registerEvents(chunkLoadListener, this);
        chunkLoadListener.loadAlreadyLoadedChunks();

        refreshAdvancementSystem(false);

        getServer().getScheduler().runTask(this, () -> startupSyncCompleted = true);

        FarmersDelightCommand commandHandler = new FarmersDelightCommand(this);
        getCommand("farmersdelight").setExecutor(commandHandler);
        getCommand("farmersdelight").setTabCompleter(commandHandler);

        getLogger().info("FarmersDelight plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        enabled = false;

        if (tickManager != null) {
            tickManager.stop();
            tickManager = null;
        }

        if (effectListener != null) {
            effectListener.stop();
            effectListener = null;
        }

        if (horseFeedTemptListener != null) {
            horseFeedTemptListener.stop();
            horseFeedTemptListener = null;
        }
        if (hopperInteractionListener != null) {
            hopperInteractionListener.stop();
            hopperInteractionListener = null;
        }

        CookingPotGui.cleanupAll();
        RecipeViewGui.cleanupAll();

        saveAllBlockData();

        if (trayManager != null) {
            trayManager.cleanupAll();
            trayManager = null;
        }

        if (stoveManager != null) {
            stoveManager.cleanup();
            stoveManager = null;
        }

        if (skilletManager != null) {
            skilletManager.cleanup();
            skilletManager = null;
        }

        if (fakeItemDisplayManager != null) {
            fakeItemDisplayManager.cleanup();
            fakeItemDisplayManager = null;
        }

        if (blockStorageManager != null) {
            blockStorageManager.shutdown();
        }

        CookingPotBlockBehavior.cleanupAll();
        CuttingBoardBlockBehavior.cleanupAll();
        StoveCookingBlockBehavior.cleanupAll();
        SkilletBlockBehavior.cleanupAll();

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

        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);

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

    private void saveAllBlockData() {
        CookingPotBlockBehavior.saveAllData();
        SkilletBlockBehavior.saveAllData();
        CuttingBoardBlockBehavior.saveAllData();
        StoveCookingBlockBehavior.saveAllData();

        if (blockStorageManager != null) {
            blockStorageManager.saveAll();
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

    @org.bukkit.event.EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.RELOAD) {
            getLogger().warning("Bukkit/PlugMan style hot reload was detected. CraftEngine-based plugins may break with errors like 'zip file closed'.");
            getLogger().warning("Use /fd reload only for FarmersDelight config changes. Restart the server after updating plugin jars or CraftEngine resources.");
        }
    }

    private void queueDatapackReload(String reason) {
        pendingDatapackReloadReason = reason;
        if (pendingDatapackReloadTask != null && !pendingDatapackReloadTask.isCancelled()) {
            return;
        }

        pendingDatapackReloadTask = getServer().getScheduler().runTaskLater(this, () -> {
            pendingDatapackReloadTask = null;
            try {
                String reloadReason = pendingDatapackReloadReason != null
                        ? pendingDatapackReloadReason
                        : "apply FarmersDelight advancement changes";
                pendingDatapackReloadReason = null;
                getLogger().info("Reloading data packs to " + reloadReason + "...");
                getServer().reloadData();
            } finally {
            }
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
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
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

        pendingDatapackSyncRetryTask = getServer().getScheduler().runTaskLater(this, () -> {
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
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
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
        if (!isEnabled() || shouldDisable) {
            return;
        }

        if (pendingCraftEngineReloadTask != null && !pendingCraftEngineReloadTask.isCancelled()) {
            return;
        }

        pendingCraftEngineReloadTask = getServer().getScheduler().runTaskLater(this, () -> {
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
        SkilletBlockEntity.clearRecipeCache();

        if (stoveManager != null) {
            stoveManager.reloadRecipeCache();
        }
        if (skilletManager != null) {
            skilletManager.reloadRecipeCache();
        }
    }

    public void reloadConfigs() {
        ensureConfigDefaults();
        reloadConfig();
        boolean previousAdvancementsEnabled = advancementsEnabled;
        loadConfigs();
        I18n.reload();
        RecipeViewGui.clearConfigCache();
        StoveCookingBlockBehavior.clearRecipeCache();
        SkilletBlockEntity.clearRecipeCache();

        if (knifeDropHandler != null) {
            knifeDropHandler.reload();
        }
        if (trayManager != null) {
            trayManager.reload();
        }
        if (stoveManager != null) {
            stoveManager.reloadRecipeCache();
        }
        if (skilletManager != null) {
            skilletManager.reloadRecipeCache();
        }
        if (foodEatListener != null) {
            foodEatListener.reload();
        }
        if (previousAdvancementsEnabled != advancementsEnabled || advancementsEnabled) {
            refreshAdvancementSystem(true);
        }

        getLogger().info("Configuration reloaded!");
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

        int autoSaveInterval = getConfig().getInt("storage.auto-save-interval", 300);
        if (autoSaveInterval < 0) {
            getLogger().warning("storage.auto-save-interval cannot be negative, using default 300");
            autoSaveInterval = 300;
        }

        ConfigurationSection heatSourceSection = getConfig().getConfigurationSection("heat-sources");
        heatSourceConfig = new HeatSourceConfig();
        HeatSourceConfig.setLogger(getLogger());
        if (heatSourceSection != null) {
            heatSourceConfig.loadFromConfig(heatSourceSection);
        }

        ConfigurationSection cookingPotSection = getConfig().getConfigurationSection("cooking-pot-gui");
        if (cookingPotSection != null) {
            cookingPotGuiConfig = GuiConfig.fromConfig(cookingPotSection);
        }

        ConfigurationSection strawDropSection = getConfig().getConfigurationSection("straw-drops");
        strawDropConfig = new StrawDropConfig();
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
        if (containerReturnSection != null) {
            containerReturnConfig.loadFromConfig(containerReturnSection);
        }

        showRecipeNameInProgressDisplay = getConfig().getBoolean("cooking-pot-progress-display.show-recipe-name", false);
    }

    public boolean isShowRecipeNameInProgressDisplay() {
        return showRecipeNameInProgressDisplay;
    }

    private boolean isLateCraftEngineLoad() {
        if (!isServerTicking()) {
            return false;
        }

        try {
            return getServer().getPluginManager().isPluginEnabled("CraftEngine") && areCraftEngineItemsReady();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isServerTicking() {
        try {
            return org.bukkit.Bukkit.getCurrentTick() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void ensureConfigDefaults() {
        ensureConfigFilePresentAndReadable();
    }

    private void ensureConfigFilePresentAndReadable() {
        Path dataFolder = getDataFolder().toPath();
        Path configPath = dataFolder.resolve("config.yml");
        try {
            Files.createDirectories(dataFolder);
            if (Files.notExists(configPath)) {
                writeBundledConfig(configPath);
                return;
            }

            if (!isYamlReadable(configPath)) {
                backupBrokenConfig(configPath);
                writeBundledConfig(configPath);
                getLogger().warning("Detected an unreadable config.yml and restored the bundled UTF-8 default config.");
            }
        } catch (IOException e) {
            getLogger().warning("Failed to prepare config.yml: " + e.getMessage());
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
        String backupName = "config.invalid." + System.currentTimeMillis() + ".yml";
        Files.copy(configPath, configPath.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeBundledConfig(Path configPath) throws IOException {
        try (InputStream inputStream = getResource("config.yml")) {
            if (inputStream == null) {
                throw new IOException("Bundled config.yml was not found in the plugin jar.");
            }
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(
                    configPath,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        }
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

        getLogger().info("Registered custom block behaviors");
    }

    private void registerBehavior(String key, BlockBehaviorFactory<?> factory) {
        Key keyObj = Key.of(key);
        Object existing = BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(keyObj);

        if (existing == null) {
            BlockBehaviors.register(keyObj, factory);
            getLogger().info("Registered block behavior: " + key);
        } else {
            if (isSameBehaviorRegistration(existing, factory)) {
                getLogger().info("Block behavior already registered: " + key);
                return;
            }

            shouldDisable = true;
            String existingLoader = describeClassLoader(existing.getClass().getClassLoader());
            String currentLoader = describeClassLoader(factory.getClass().getClassLoader());
            behaviorRegistryConflicts.add("Conflicting CraftEngine block behavior registration detected for "
                    + key + ". Existing loader=" + existingLoader + ", current loader=" + currentLoader
                    + ". This usually means FarmersDelight was unloaded and loaded again without restarting the server.");
        }
    }

    private boolean isSameBehaviorRegistration(Object existing, BlockBehaviorFactory<?> factory) {
        ClassLoader existingLoader = existing.getClass().getClassLoader();
        ClassLoader currentLoader = factory.getClass().getClassLoader();
        return existingLoader == currentLoader;
    }

    private String describeClassLoader(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    public BukkitCraftEngine getCraftEngine() {
        return BukkitCraftEngine.instance();
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

    public ItemDisplayManager getFakeItemDisplayManager() {
        return fakeItemDisplayManager;
    }

    public float getSkilletDisplayScale() {
        return (float) getConfig().getDouble("display-visuals.skillet.scale", 0.5D);
    }

    public float getStoveDisplayScale() {
        return (float) getConfig().getDouble("display-visuals.stove.scale", 0.375D);
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
