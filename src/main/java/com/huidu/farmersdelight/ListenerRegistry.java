package com.huidu.farmersdelight;

import com.huidu.farmersdelight.api.util.PluginManagerGuard;
import com.huidu.farmersdelight.block.behavior.MushroomColonyBehavior;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.effect.EffectListener;
import com.huidu.farmersdelight.effect.EffectManager;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.listener.AchievementListener;
import com.huidu.farmersdelight.listener.BackstabListener;
import com.huidu.farmersdelight.listener.BlockBreakListener;
import com.huidu.farmersdelight.listener.BlockPlaceListener;
import com.huidu.farmersdelight.listener.ChunkLoadListener;
import com.huidu.farmersdelight.listener.CraftEngineWatchdogListener;
import com.huidu.farmersdelight.listener.CropInteractProtectionListener;
import com.huidu.farmersdelight.listener.CuttingBoardDispenseListener;
import com.huidu.farmersdelight.listener.CuttingBoardInteractListener;
import com.huidu.farmersdelight.listener.DamageTypeDatapackInstaller;
import com.huidu.farmersdelight.listener.EnchantmentDatapackInstaller;
import com.huidu.farmersdelight.listener.FoodEatListener;
import com.huidu.farmersdelight.listener.HorseFeedTemptListener;
import com.huidu.farmersdelight.listener.KnifeEnchantFilter;
import com.huidu.farmersdelight.listener.PetFoodListener;
import com.huidu.farmersdelight.listener.RecipeDiscoveryListener;
import com.huidu.farmersdelight.listener.RicePlantListener;
import com.huidu.farmersdelight.listener.RichSoilHoeListener;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.listener.RottenTomatoListener;
import com.huidu.farmersdelight.listener.SkilletLifecycleListener;
import com.huidu.farmersdelight.listener.SkilletPlaceListener;
import com.huidu.farmersdelight.listener.StrawDropListener;
import com.huidu.farmersdelight.listener.TagDatapackInstaller;
import com.huidu.farmersdelight.listener.TatamiBreakListener;
import com.huidu.farmersdelight.listener.worlddata.VillagerTradeListener;
import com.huidu.farmersdelight.manager.BuffBossbarManager;
import com.huidu.farmersdelight.handheld.HandCookedSkewerHooks;
import com.huidu.farmersdelight.migration.LegacyIdMigrationHooks;
import com.huidu.farmersdelight.tool.ToolAttackListener;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns every event listener the plugin registers, and the reload/stop hooks their state needs.
 *
 *
 * The plugin previously held one field per listener and registered them inline in its enable path, which
 * made that path the place every new listener had to be threaded through. Listeners that nothing outside the
 * registration block referenced are private here and have no accessor at all; the four that other components
 * read (strawDropListener(), foodEatListener(), horseFeedTemptListener(),
 * ropeBlockListener()) are exposed explicitly.
 *
 *
 * Registration order is part of the behaviour: event priority decides who runs first, and CraftEngine's
 * pack parsers and the command registrar are claimed elsewhere in the enable path. The three
 * register* methods keep the original grouping and relative position against the manager
 * construction that used to sit between them.
 */
final class ListenerRegistry {

    private final FarmersDelightPlugin plugin;

    // Event handlers with no timer of their own.
    private BlockBreakListener blockBreakListener;
    private BlockPlaceListener blockPlaceListener;
    private StrawDropListener strawDropListener;
    private FoodEatListener foodEatListener;
    private PetFoodListener petFoodListener;
    private BackstabListener backstabListener;
    private KnifeEnchantFilter knifeEnchantFilter;
    private RopeBlockListener ropeBlockListener;

    // Handlers that drive their own repeating task.
    private HorseFeedTemptListener horseFeedTemptListener;
    private EffectListener effectListener;
    private LegacyIdMigrationHooks legacyIdMigrationHooks;
    private HandCookedSkewerHooks handCookedSkewerHooks;
    private ChunkLoadListener chunkLoadListener;

    // Datapack installers: they also listen, so they are installed and registered together.
    private EnchantmentDatapackInstaller enchantmentDatapackInstaller;
    private DamageTypeDatapackInstaller damageTypeDatapackInstaller;
    private TagDatapackInstaller tagDatapackInstaller;

    // Registered only while the advancement system is up (see FarmersDelightPlugin#refreshAdvancementSystem).
    private AchievementListener achievementListener;

    ListenerRegistry(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Builds the block and interaction handlers, in registration order, up to and including the ones that
     * start their own tasks.
     *
     *
     * Construction is kept apart from registration so the order below can be asserted by a test without a
     * running server: registerInteractionHandlers() registers this exact list. Bukkit orders delivery
     * within a priority by registration sequence, so this order is behaviour, not style — do not reorder it
     * without checking every handler that shares an event.
     */
    List<Listener> buildInteractionHandlers() {
        return List.of(
                new CraftEngineWatchdogListener(plugin),
                new RecipeDiscoveryListener(plugin),
                new BlockBreakListener(plugin),
                new BlockPlaceListener(plugin),
                new SkilletPlaceListener(plugin),
                new SkilletLifecycleListener(plugin),
                new ToolAttackListener(),
                new RottenTomatoListener(plugin),
                new CuttingBoardInteractListener(plugin),
                new CuttingBoardDispenseListener(plugin),
                new StrawDropListener(plugin),
                new RicePlantListener(plugin),
                // Awards master_chef criteria and preserves addon/legacy food registrations. Built-in food
                // buffs run as CE functions.
                new FoodEatListener(plugin),
                new PetFoodListener(plugin),
                new HorseFeedTemptListener(plugin),
                new EffectListener(plugin),
                // Rewrites addon-declared legacy ids in stacks that are already in the world; its id table is
                // empty until an addon registers one, so it does nothing on its own.
                new LegacyIdMigrationHooks(plugin),
                // Handheld skewer cooking. Inert until the switch is on and a result is
                // configured; its position matters because a right-click reaches the skillet
                // handlers first, which own the portable-skillet trigger.
                new HandCookedSkewerHooks(plugin));
    }

    /**
     * The instances registerInteractionHandlers() puts in front of the event system, in order.
     *
     *
     * Separate from the built list because the last entry is the plugin instance itself, which carries the
     * world load/unload persistence and the CraftEngine reload hook. An @EventHandler method only
     * fires for an instance that was passed to registerEvents, so the plugin has to be part of the
     * registered set rather than merely declaring the handlers. This was silently dropped once during the
     * registry extraction, which disabled all three of those handlers; the test asserts this list, so it
     * cannot happen again unnoticed.
     */
    List<Listener> interactionHandlersToRegister() {
        List<Listener> listeners = new ArrayList<>(buildInteractionHandlers());
        listeners.add(plugin);
        return listeners;
    }

    /**
     * Registers interactionHandlersToRegister() and starts the tasks those handlers own.
     */
    void registerInteractionHandlers() {
        for (Listener listener : interactionHandlersToRegister()) {
            storeHandler(listener);
            register(listener);
        }

        MushroomColonyBehavior.reloadMushroomSupportCache(plugin);
        // FoodEatListener defers its config read to here, so the load happens once, at startup.
        foodEatListener.reload();
        horseFeedTemptListener.start();
        effectListener.start();
        // Reads the migration switches and schedules the one-off sweep over already loaded inventories.
        legacyIdMigrationHooks.start();
        // Reads its own switch: a disabled path schedules no tick task.
        handCookedSkewerHooks.start();

        register(new PluginManagerGuard(plugin.getName()));
    }

    /**
     * Records the handlers this registry later has to consult or tear down. The list is walked in
     * buildInteractionHandlers() order, so one pass is enough.
     */
    private void storeHandler(Listener listener) {
        switch (listener) {
            case BlockBreakListener value -> blockBreakListener = value;
            case BlockPlaceListener value -> blockPlaceListener = value;
            case StrawDropListener value -> strawDropListener = value;
            case FoodEatListener value -> foodEatListener = value;
            case PetFoodListener value -> petFoodListener = value;
            case HorseFeedTemptListener value -> horseFeedTemptListener = value;
            case EffectListener value -> effectListener = value;
            case LegacyIdMigrationHooks value -> legacyIdMigrationHooks = value;
            case HandCookedSkewerHooks value -> handCookedSkewerHooks = value;
            default -> {
            }
        }
    }

    /**
     * Builds the remaining handlers apart from the bossbar renderer the plugin owns, in registration order.
     *
     *
     * Same contract as buildInteractionHandlers(): construction is pure, so the order can be
     * asserted without a server, and registerVisualAndWorldHandlers installs and registers exactly
     * this list. Called after the tick and display managers exist, because the rope tracker and the chunk
     * loader are used by those code paths.
     */
    List<Listener> buildVisualAndWorldHandlers() {
        return List.of(
                new RopeBlockListener(plugin),
                new TatamiBreakListener(),
                new RichSoilHoeListener(plugin),
                new CropInteractProtectionListener(),
                new BackstabListener(plugin),
                new KnifeEnchantFilter(plugin),
                new EnchantmentDatapackInstaller(plugin),
                new TagDatapackInstaller(plugin),
                // Villager and wandering trader trades use the world-data section. Composting chances and
                // furnace burn times are configured in CraftEngine item definitions.
                new VillagerTradeListener(),
                new ChunkLoadListener(plugin));
    }

    /**
     * Installs and registers the world-facing handlers, then runs the startup work they own: the enchantment
     * and common-tag data packs are written to the primary world, and the chunk loader back-fills the chunks
     * that are already loaded.
     *
     *
     * The buff bossbar renderer is handed in rather than built here because its configuration and start belong
     * to the plugin's manager lifecycle, not to the registration order below.
     */
    void registerVisualAndWorldHandlers(BuffBossbarManager bars) {
        register(bars);

        for (Listener listener : buildVisualAndWorldHandlers()) {
            storeVisualHandler(listener);
            register(listener);
        }

        enchantmentDatapackInstaller.installToPrimaryWorld(plugin.getPrimaryWorld());
        // Registry tags are server-global (shared by every world), so the common-item tag data pack is
        // written once into the primary world's datapacks folder; a per-world inject would be redundant.
        if (tagDatapackInstaller.installToPrimaryWorld(plugin.getPrimaryWorld())) {
            plugin.queueDatapackReload(I18n.formatConsole("plugin.datapack_reason_apply_tag_changes"));
        }
        chunkLoadListener.loadAlreadyLoadedChunks();
    }

    /** Records the world-facing handlers this registry later has to consult or tear down. */
    private void storeVisualHandler(Listener listener) {
        switch (listener) {
            case RopeBlockListener value -> ropeBlockListener = value;
            case BackstabListener value -> backstabListener = value;
            case KnifeEnchantFilter value -> knifeEnchantFilter = value;
            case EnchantmentDatapackInstaller value -> enchantmentDatapackInstaller = value;
            case TagDatapackInstaller value -> tagDatapackInstaller = value;
            case ChunkLoadListener value -> chunkLoadListener = value;
            default -> {
            }
        }
    }

    /**
     * Installs and registers the damage-type datapack, and drops the obsolete loot datapack folder: chest,
     * grass and mob injections use CE-native loot sources.
     */
    void registerDamageTypeDatapack() {
        DamageTypeDatapackInstaller installer = new DamageTypeDatapackInstaller(plugin);
        damageTypeDatapackInstaller = installer;
        installer.installToPrimaryWorld(plugin.getPrimaryWorld());
        installer.cleanupLegacyLootDatapack();
    }

    private <T extends Listener> T register(T listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        return listener;
    }

    private void register(Listener... listeners) {
        for (Listener listener : listeners) {
            register(listener);
        }
    }

    private ConfigurationSection configuredBuffSection() {
        return plugin.getFirstConfigSection("buff.display", "bossbar");
    }

    StrawDropListener strawDropListener() {
        return strawDropListener;
    }

    FoodEatListener foodEatListener() {
        return foodEatListener;
    }

    HorseFeedTemptListener horseFeedTemptListener() {
        return horseFeedTemptListener;
    }

    RopeBlockListener ropeBlockListener() {
        return ropeBlockListener;
    }

    ChunkLoadListener chunkLoadListener() {
        return chunkLoadListener;
    }

    DamageTypeDatapackInstaller damageTypeDatapackInstaller() {
        return damageTypeDatapackInstaller;
    }

    TagDatapackInstaller tagDatapackInstaller() {
        return tagDatapackInstaller;
    }

    EnchantmentDatapackInstaller enchantmentDatapackInstaller() {
        return enchantmentDatapackInstaller;
    }

    AchievementListener achievementListener() {
        return achievementListener;
    }

    /** Registers the advancement criteria handler once the advancement system is available. */
    AchievementListener registerAchievementListener() {
        if (achievementListener == null) {
            achievementListener = register(new AchievementListener(plugin));
        }
        return achievementListener;
    }

    /** Detaches the advancement criteria handler; the rest of the shutdown sequence is driven by the plugin. */
    void unregisterAchievementListener() {
        if (achievementListener != null) {
            HandlerList.unregisterAll(achievementListener);
            achievementListener = null;
        }
    }

    /**
     * Re-applies config to the handlers that cache it. Called from the plugin's common reload path after the
     * config values themselves have been republished. The pet-tempt handler is refreshed separately once
     * CraftEngine has registered its item settings, so it is not included here.
     */
    void reload(boolean buffSystemEnabled) {
        if (backstabListener != null) {
            backstabListener.reload(backstabSettings(), backstabEnabled());
        }
        if (knifeEnchantFilter != null) {
            knifeEnchantFilter.reload(backstabSettings(), backstabEnabled());
        }
        if (enchantmentDatapackInstaller != null) {
            enchantmentDatapackInstaller.installToPrimaryWorld(plugin.getPrimaryWorld());
        }
        BuffBossbarManager bars = plugin.getBuffBossbarManagerOrNull();
        if (bars != null) {
            bars.applyConfig(configuredBuffSection(), buffSystemEnabled);
            EffectManager.applyBossbarStyles(
                    plugin.getFirstConfigSection("buff.display.styles", "bossbar.styles"));
        }
        // The buff ticker is armed on demand, so a reload that switches the system back on has to re-arm it
        // for players who still hold a buff, and a reload that switches it off has to stop the running pass.
        if (effectListener != null) {
            effectListener.applySystemEnabled(buffSystemEnabled);
        }
        if (foodEatListener != null) {
            foodEatListener.reload();
        }
        if (horseFeedTemptListener != null) {
            horseFeedTemptListener.reload();
        }
        if (legacyIdMigrationHooks != null && legacyIdMigrationHooks.service() != null) {
            legacyIdMigrationHooks.service().reloadConfig();
        }
        if (handCookedSkewerHooks != null) {
            // Re-reads handheld-skewer.*: a disabled switch stops the path, an enabled one arms it.
            handCookedSkewerHooks.start();
        }
    }

    private EnchantmentSettings backstabSettings() {
        return plugin.getEnchantmentSettings();
    }

    private boolean backstabEnabled() {
        return plugin.isBackstabEnchantmentEnabled();
    }

    /** Re-installs the enchantment datapack so the primary world picks up the current settings. */
    void refreshEnchantmentFallback() {
        if (enchantmentDatapackInstaller != null) {
            enchantmentDatapackInstaller.installToPrimaryWorld(plugin.getPrimaryWorld());
        }
    }

    /** Stands the backstabbing gate down on a detected enchantment-plugin conflict, leaving knife enchanting on. */
    void disableBackstabOnConflict(EnchantmentSettings settings) {
        if (backstabListener != null) {
            backstabListener.reload(settings, false);
        }
        if (knifeEnchantFilter != null) {
            knifeEnchantFilter.reload(settings, false);
        }
    }

    /** Re-offers the knife/skillet enchant candidate pool after an addon registers an enchantment. */
    void reloadEnchantCandidates() {
        if (knifeEnchantFilter != null) {
            knifeEnchantFilter.reload(backstabSettings(), backstabEnabled());
        }
    }

    boolean hasDamageTypeDatapack() {
        return damageTypeDatapackInstaller != null;
    }

    /** Re-installs the common-tag datapack; true when it changed and a datapack reload was queued. */
    boolean refreshTagDatapack() {
        if (tagDatapackInstaller == null) {
            return false;
        }
        if (tagDatapackInstaller.installToPrimaryWorld(plugin.getPrimaryWorld())) {
            plugin.queueDatapackReload(I18n.formatConsole("plugin.datapack_reason_apply_tag_changes"));
            return true;
        }
        return false;
    }

    void refreshLootDatapack() {
        if (damageTypeDatapackInstaller == null) {
            return;
        }
        damageTypeDatapackInstaller.cleanupLegacyLootDatapack();
    }

    void refreshDamageTypeDatapack() {
        if (damageTypeDatapackInstaller == null) {
            return;
        }
        damageTypeDatapackInstaller.installToPrimaryWorld(plugin.getPrimaryWorld());
        damageTypeDatapackInstaller.cleanupLegacyLootDatapack();
    }

    /**
     * Detaches event delivery before any listener state is torn down, then stops the owned tasks.
     *
     *
     * Unregistering first is what makes the rest of the shutdown order independent of vanilla event
     * timing: during onDisable, piston ticks, neighbour updates and scheduled chunk tasks keep firing, and
     * late-bound lambda metafactory calls from listener code would hit NoClassDefFoundError once the plugin
     * classloader starts draining.
     */
    void stop() {
        HandlerList.unregisterAll((Plugin) plugin);
        RicePlantListener.shutdownActive();
        if (ropeBlockListener != null) {
            ropeBlockListener.shutdown();
        }
        // Handheld skewer sessions rewrite the player's own inventory slot on the way out. Dropping those
        // bars here (each close also resends the real slot) is what stops a handler from outliving the plugin.
        if (handCookedSkewerHooks != null) {
            handCookedSkewerHooks.shutdown();
            handCookedSkewerHooks = null;
        }
        // A reload pass that is still registering recipes must not keep holding state past disable.
        plugin.cancelRecipeRegistrations();
        if (effectListener != null) {
            effectListener.stop();
            effectListener = null;
        }
        if (horseFeedTemptListener != null) {
            horseFeedTemptListener.stop();
            horseFeedTemptListener = null;
        }
        if (chunkLoadListener != null) {
            chunkLoadListener.shutdown();
        }
        blockBreakListener = null;
        blockPlaceListener = null;
        strawDropListener = null;
        foodEatListener = null;
        petFoodListener = null;
        backstabListener = null;
        knifeEnchantFilter = null;
        achievementListener = null;
    }
}
