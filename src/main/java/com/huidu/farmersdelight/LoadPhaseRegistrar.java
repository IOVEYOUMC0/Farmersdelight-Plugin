package com.huidu.farmersdelight;

import com.huidu.farmersdelight.config.PetFoodConfig;
import com.huidu.farmersdelight.pack.PackSections;
import com.huidu.farmersdelight.registry.BehaviorRegistrar;
import com.huidu.farmersdelight.resource.ResourceInstaller;
import com.huidu.farmersdelight.tool.ToolRegistry;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;

/**
 * Everything the plugin registers during the load phase, before CraftEngine starts parsing its packs.
 *
 *
 * These calls have to happen in onLoad and in this order: CraftEngine dispatches the FarmersDelight
 * pack sections to the parsers registered here while it loads packs in its own onEnable, and WorldGuard
 * locks its flag registry once it enables, so both must be claimed before the enable phase of any other plugin.
 * Keeping them in one place makes that constraint visible instead of leaving it implicit among the plugin's
 * lifecycle code.
 */
final class LoadPhaseRegistrar {

    private final FarmersDelightPlugin plugin;

    LoadPhaseRegistrar(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs the whole load phase after the plugin has claimed its data folder and config files.
     *
     * @return the pack sections this plugin owns, to be published on the plugin (null when another plugin
     *         already took the section ids)
     */
    PackSections register() {
        // Load before CraftEngine's dependent plugins begin their enable phase, so recipes and advancements
        // read the same family-wide tag map.
        CommonTagResolver.reload(plugin);
        new ResourceInstaller(plugin, plugin.pluginJarFile()).installCraftEngineResourcesOnce();

        BehaviorRegistrar.registerBlockBehaviors();
        BehaviorRegistrar.registerItemBehaviors();
        BehaviorRegistrar.registerFunctions();
        BehaviorRegistrar.registerConditions();
        BehaviorRegistrar.registerLootFunctions();
        // Register the farmersdelight:sword settings modifier before CraftEngine parses item YAML files.
        ToolRegistry.register();
        // Register the farmersdelight:pet_food settings modifier before CraftEngine parses item YAML files.
        PetFoodConfig.setLogger(plugin.getLogger());
        PetFoodConfig.registerCraftEngineSetting();

        // Claimed here because CraftEngine hands the sections to the registered parsers during its own
        // onEnable, which runs after this method.
        PackSections sections = PackSections.register();
        // Registered in onLoad because WorldGuard locks its FlagRegistry once it enables; no-op without WG.
        ProtectionCompat.registerFlags();
        return sections;
    }
}
