package com.huidu.farmersdelight;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.config.CuttingBoardSounds;
import com.huidu.farmersdelight.config.CookingPotExperienceRewardConfig;
import com.huidu.farmersdelight.config.CuttingBoardDisplayConfig;
import com.huidu.farmersdelight.config.DebugSettings;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.config.KnifeSettings;
import com.huidu.farmersdelight.config.StationSettings;
import com.huidu.farmersdelight.config.StrawDropConfig;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.editor.RecipeEditorView;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.KaleidoscopeCompat;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every configuration file into the settings the plugin publishes. Each object is built completely before it
 * is assigned to its volatile field, so a region thread asking for a setting during a reload sees either the old
 * complete snapshot or the new one, never a half-populated one.
 */
final class PluginConfigAssembly {

    private final FarmersDelightPlugin plugin;

    PluginConfigAssembly(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads the new config and publishes it, but restores the previously published fields when the pass throws.
     * Most reload failures come from reading/parsing a file, i.e. before the first field is published; a failure
     * later in the pass still restores every field captured here instead of leaving a half-new configuration.
     * Static side effects (GUI caches, common tags, world data) cannot be rolled back by a field assignment and
     * are re-applied by the next reload/restart.
     */
    void applyWithRollback() {
        ConfigRollback rollback = new ConfigRollback();
        var oldDrops = plugin.dropsConfig;
        rollback.capture(() -> plugin.dropsConfig = oldDrops);
        var oldEnchantments = plugin.enchantmentSettings;
        rollback.capture(() -> plugin.enchantmentSettings = oldEnchantments);
        var oldBackstab = plugin.backstabEnchantmentEnabled;
        rollback.capture(() -> plugin.backstabEnchantmentEnabled = oldBackstab);
        var oldDebug = plugin.debugSettings;
        rollback.capture(() -> plugin.debugSettings = oldDebug);
        var oldKnife = plugin.knifeSettings;
        rollback.capture(() -> plugin.knifeSettings = oldKnife);
        var oldHeat = plugin.heatSourceConfig;
        rollback.capture(() -> plugin.heatSourceConfig = oldHeat);
        var oldCuttingBoardSounds = plugin.cuttingBoardSounds;
        rollback.capture(() -> plugin.cuttingBoardSounds = oldCuttingBoardSounds);
        var oldGui = plugin.guiConfig;
        rollback.capture(() -> plugin.guiConfig = oldGui);
        var oldCookingPotGui = plugin.cookingPotGuiConfig;
        rollback.capture(() -> plugin.cookingPotGuiConfig = oldCookingPotGui);
        var oldCustomCookingPotGuis = plugin.customCookingPotGuiConfigs;
        rollback.capture(() -> plugin.customCookingPotGuiConfigs = oldCustomCookingPotGuis);
        var oldStraw = plugin.strawDropConfig;
        rollback.capture(() -> plugin.strawDropConfig = oldStraw);
        var oldPetFood = plugin.petFoodConfig;
        rollback.capture(() -> plugin.petFoodConfig = oldPetFood);
        var oldContainerReturn = plugin.containerReturnConfig;
        rollback.capture(() -> plugin.containerReturnConfig = oldContainerReturn);
        var oldCuttingBoardDisplay = plugin.cuttingBoardDisplayConfig;
        rollback.capture(() -> plugin.cuttingBoardDisplayConfig = oldCuttingBoardDisplay);
        var oldSkilletDisplay = plugin.skilletDisplayConfig;
        rollback.capture(() -> plugin.skilletDisplayConfig = oldSkilletDisplay);
        var oldStoveDisplay = plugin.stoveDisplayConfig;
        rollback.capture(() -> plugin.stoveDisplayConfig = oldStoveDisplay);
        var oldStationSettings = plugin.stationSettings;
        rollback.capture(() -> plugin.stationSettings = oldStationSettings);
        var oldExperienceReward = plugin.cookingPotExperienceRewardConfig;
        rollback.capture(() -> plugin.cookingPotExperienceRewardConfig = oldExperienceReward);
        boolean oldAdvancements = plugin.advancementSystem.isEnabled();
        rollback.capture(() -> plugin.advancementSystem.setEnabled(oldAdvancements));
        boolean oldBuffs = plugin.buffSystem.isEnabled();
        rollback.capture(() -> plugin.buffSystem.setEnabled(oldBuffs));

        rollback.run(this::apply);
    }

    /** Reads every config file once; the enable path and every reload pass share this sequence. */
    void apply() {        YamlConfiguration worldDataConfig = plugin.configFiles.loadWorldData();
        YamlConfiguration loadedDropsConfig = plugin.configFiles.loadDrops();
        plugin.dropsConfig = loadedDropsConfig;
        plugin.advancementSystem.setEnabled(plugin.getConfig().getBoolean("advancements.enabled", true));
        // Missing buff.enabled preserves the enabled default.
        plugin.buffSystem.setEnabled(plugin.getConfigBoolean(true, "buff.enabled"));
        EnchantmentSettings loadedEnchantments = EnchantmentSettings.load(
                plugin.getConfig().getConfigurationSection("enchantments"));
        plugin.enchantmentSettings = loadedEnchantments;
        // Keep the knife/skillet enchant filter running whatever else is installed: its CraftEngine knives are
        // nether_brick underneath and only this filter can enchant them. Only the custom backstab enchant stands
        // down when a dedicated enchantment plugin is present;
        // enchantments.compatibility.auto-disable-on-conflict: false forces it back on.
        boolean backstab = plugin.resolveBackstabbingCompatibility(loadedEnchantments);
        if (backstab) {
            String conflict = plugin.detectEnchantmentTableConflict();
            List<String> foreign = plugin.detectForeignEnchantments();
            if (conflict != null && loadedEnchantments.autoDisableOnConflict()) {
                I18n.logWarning("plugin.backstab_disabled_conflict", "conflict", conflict);
                backstab = false;
            } else if (conflict == null && !foreign.isEmpty() && loadedEnchantments.autoDisableOnConflict()
                    && loadedEnchantments.disableOnCustomEnchantments()) {
                I18n.logWarning("plugin.backstab_disabled_custom_enchantments", "enchantments",
                        String.join(", ", foreign));
                backstab = false;
            } else if (conflict != null && !loadedEnchantments.autoDisableOnConflict()) {
                // The operator asked for the enchant to stay on; say what was detected instead of the
                // "nothing conflicts" wording below.
                I18n.logInfo("plugin.backstab_forced_on", "conflict", conflict);
            } else if (conflict == null && !foreign.isEmpty()) {
                // Reported, not acted on: the knives keep the backstab enchant and the operator can opt into
                // the strict behaviour instead.
                I18n.logInfo("plugin.backstab_kept_custom_enchantments", "enchantments",
                        String.join(", ", foreign));
            }
        }
        plugin.backstabEnchantmentEnabled = backstab;
        // Re-read on every reload so a debug switch edited in config.yml takes effect; the enable path
        // has already read it once, earlier, for the startup lines that precede this method.
        loadDebugFlags();
        plugin.knifeSettings = KnifeSettings.load(plugin.getConfig());

        ConfigurationSection heatSourceSection = plugin.getConfig().getConfigurationSection("heat-sources");
        // Build the complete configuration locally before publishing it through the volatile field.
        // Region-thread heat checks must not observe partially populated settings during reload.
        HeatSourceConfig newHeatSourceConfig = new HeatSourceConfig();
        HeatSourceConfig.setLogger(plugin.getLogger());
        newHeatSourceConfig.loadDefaults();
        if (heatSourceSection != null) {
            newHeatSourceConfig.loadFromConfig(heatSourceSection);
        }
        // The table above is rebuilt from scratch, which would drop every addon registration. Replay
        // them before publishing, so an addon that registered in onEnable survives /fd reload.
        FarmersDelightApi.get().replayAddonHeatSources(newHeatSourceConfig);
        plugin.heatSourceConfig = newHeatSourceConfig;

        // Same assign-once publication: the cut sound is resolved on region threads.
        plugin.cuttingBoardSounds = CuttingBoardSounds.from(
                plugin.getConfig().getConfigurationSection("cutting-board.sounds"));

        plugin.guiConfig = plugin.configFiles.loadGui();
        ConfigurationSection cookingPotSection = plugin.guiConfig.getConfigurationSection("cooking-pot-gui");
        plugin.cookingPotGuiConfig = cookingPotSection != null
                ? GuiConfig.fromConfig(cookingPotSection)
                : GuiConfig.createDefault();
        plugin.customCookingPotGuiConfigs = loadCustomCookingPotGuiConfigs(plugin.guiConfig);
        RecipeEditorView.reloadGui(plugin.guiConfig);

        // Build each immutable configuration before publishing it through its volatile field.
        // Region-thread event handlers can then read a complete snapshot during reload.
        ConfigurationSection strawDropSection = loadedDropsConfig.getConfigurationSection("straw");
        StrawDropConfig newStrawDropConfig = new StrawDropConfig();
        // No code-side defaults: the bundled drops.yml carries them and the section is a registry section,
        // so a rule the operator deleted has to stay deleted.
        newStrawDropConfig.loadFromConfig(strawDropSection);
        plugin.strawDropConfig = newStrawDropConfig;

        plugin.petFoodConfig = plugin.configFiles.loadPetFood(
                plugin.getConfig().getConfigurationSection("pet-foods"));

        ConfigurationSection containerReturnSection =
                plugin.getFirstConfigSection("container-returns", "cooking-pot.container-returns");
        ContainerReturnConfig newContainerReturnConfig = new ContainerReturnConfig();
        // Same rule as the straw drops above: bundled defaults only, no code-side re-adding.
        newContainerReturnConfig.loadFromConfig(containerReturnSection);
        plugin.containerReturnConfig = newContainerReturnConfig;

        CuttingBoardDisplayConfig newCuttingBoardDisplayConfig = new CuttingBoardDisplayConfig();
        newCuttingBoardDisplayConfig.loadFromConfig(plugin.getConfig().getConfigurationSection("cutting-board"));
        newCuttingBoardDisplayConfig.loadOverridesFromFile(plugin.configFiles.loadDisplayOverrides());
        plugin.cuttingBoardDisplayConfig = newCuttingBoardDisplayConfig;
        CuttingBoardDisplayConfig newSkilletDisplayConfig = createSkilletDisplayConfig();
        newSkilletDisplayConfig.loadFromConfig(
                plugin.getFirstConfigSection("skillet.display", "display-visuals.skillet"));
        plugin.skilletDisplayConfig = newSkilletDisplayConfig;
        CuttingBoardDisplayConfig newStoveDisplayConfig = createStoveDisplayConfig();
        newStoveDisplayConfig.loadFromConfig(
                plugin.getFirstConfigSection("stove.display", "display-visuals.stove"));
        plugin.stoveDisplayConfig = newStoveDisplayConfig;

        plugin.stationSettings = StationSettings.load(plugin, newSkilletDisplayConfig, newStoveDisplayConfig);

        plugin.cookingPotExperienceRewardConfig = new CookingPotExperienceRewardConfig();
        plugin.cookingPotExperienceRewardConfig.loadFromConfig(
                plugin.getFirstConfigSection("experience-reward", "cooking-pot.experience-reward"));
        ItemUtils.configureExternalItemSources(plugin.getConfig().getStringList("external-item-sources"));
        KaleidoscopeCompat.refresh(plugin);
        WorldDataConfig.reload(plugin, worldDataConfig);
        // Load the c: common-tag mapping before recipes load; it feeds recipe tag matching/indexing.
        CommonTagResolver.reload(plugin);
    }

    void loadDebugFlags() {
        DebugSettings settings = DebugSettings.load(plugin.getConfig());
        plugin.debugSettings = settings;
        java.util.List<String> unknownCategories = settings.unknownCategories();
        if (!unknownCategories.isEmpty()) {
            I18n.logWarning("plugin.debug_unknown_categories",
                    "categories", String.join(", ", unknownCategories));
        }
    }

    Map<String, GuiConfig> loadCustomCookingPotGuiConfigs(YamlConfiguration config) {
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

    CuttingBoardDisplayConfig createSkilletDisplayConfig() {
        return new CuttingBoardDisplayConfig(new CuttingBoardDisplayConfig.DisplayOverride(
                null,
                CuttingBoardDisplayConfig.DisplayStyle.AUTO,
                new Vector3f(0.0F, 0.1F, 0.0F),
                null,
                null,
                new Vector3f(0.5F, 0.5F, 0.5F)
        ), 0.15F);
    }

    CuttingBoardDisplayConfig createStoveDisplayConfig() {
        return new CuttingBoardDisplayConfig(new CuttingBoardDisplayConfig.DisplayOverride(
                null,
                CuttingBoardDisplayConfig.DisplayStyle.AUTO,
                new Vector3f(0.0F, 0.0F, 0.0F),
                null,
                null,
                new Vector3f(0.375F, 0.375F, 0.375F)
        ), 0.0F);
    }
}
