package com.huidu.farmersdelight;

import com.huidu.farmersdelight.api.util.PluginManagerGuard;
import com.huidu.farmersdelight.listener.BackstabListener;
import com.huidu.farmersdelight.listener.BlockBreakListener;
import com.huidu.farmersdelight.listener.BlockPlaceListener;
import com.huidu.farmersdelight.listener.ChunkLoadListener;
import com.huidu.farmersdelight.listener.CraftEngineWatchdogListener;
import com.huidu.farmersdelight.listener.CropInteractProtectionListener;
import com.huidu.farmersdelight.listener.CuttingBoardDispenseListener;
import com.huidu.farmersdelight.listener.CuttingBoardInteractListener;
import com.huidu.farmersdelight.listener.EnchantmentDatapackInstaller;
import com.huidu.farmersdelight.listener.FoodEatListener;
import com.huidu.farmersdelight.listener.HorseFeedTemptListener;
import com.huidu.farmersdelight.listener.KnifeEnchantFilter;
import com.huidu.farmersdelight.listener.PetFoodListener;
import com.huidu.farmersdelight.listener.RecipeDiscoveryListener;
import com.huidu.farmersdelight.listener.RichSoilHoeListener;
import com.huidu.farmersdelight.listener.RicePlantListener;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.listener.RottenTomatoListener;
import com.huidu.farmersdelight.listener.SkilletLifecycleListener;
import com.huidu.farmersdelight.listener.SkilletPlaceListener;
import com.huidu.farmersdelight.listener.StrawDropListener;
import com.huidu.farmersdelight.listener.TagDatapackInstaller;
import com.huidu.farmersdelight.listener.TatamiBreakListener;
import com.huidu.farmersdelight.listener.worlddata.VillagerTradeListener;
import com.huidu.farmersdelight.effect.EffectListener;
import com.huidu.farmersdelight.manager.BuffBossbarManager;
import com.huidu.farmersdelight.handheld.HandCookedSkewerHooks;
import com.huidu.farmersdelight.migration.LegacyIdMigrationHooks;
import com.huidu.farmersdelight.tool.ToolAttackListener;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the interaction handler registration order.
 *
 *
 * Bukkit delivers an event to same-priority handlers in registration order, so this sequence decides who
 * sees an event first. It used to live inline in the plugin's enable path, where nothing could assert it;
 * it now lives in ListenerRegistry#buildInteractionHandlers(), which is a pure list. Reordering it
 * silently changes behaviour for every handler pair that shares an event, so the expected list below is
 * spelled out rather than derived.
 *
 *
 * The registry is built with a null plugin: none of these handlers touch the plugin while being
 * constructed, which is what makes the order assertable without a server.
 */
class ListenerRegistrationOrderTest {

    private static final List<Class<? extends Listener>> EXPECTED_INTERACTION_ORDER = List.of(
            CraftEngineWatchdogListener.class,
            RecipeDiscoveryListener.class,
            BlockBreakListener.class,
            BlockPlaceListener.class,
            SkilletPlaceListener.class,
            SkilletLifecycleListener.class,
            ToolAttackListener.class,
            RottenTomatoListener.class,
            CuttingBoardInteractListener.class,
            CuttingBoardDispenseListener.class,
            StrawDropListener.class,
            RicePlantListener.class,
            FoodEatListener.class,
            PetFoodListener.class,
            HorseFeedTemptListener.class,
            EffectListener.class,
            LegacyIdMigrationHooks.class,
            // Registered last: it must not see a right-click before the skillet handlers.
            HandCookedSkewerHooks.class);

    @Test
    void interactionHandlersAreBuiltInRegistrationOrder() {
        List<? extends Class<? extends Listener>> actual = new ListenerRegistry(null)
                .buildInteractionHandlers()
                .stream()
                .map(Listener::getClass)
                .toList();

        assertEquals(EXPECTED_INTERACTION_ORDER, actual,
                "The interaction handler order changed. Bukkit runs same-priority handlers in registration"
                        + " order, so this is a behaviour change: check every handler that shares an event"
                        + " before accepting it, then update this list.");
    }

    /**
     * The plugin-manager guard is registered last, after the handlers above, and takes the plugin name at
     * registration time rather than construction time — which is why it is not part of the list above.
     */
    @Test
    void pluginManagerGuardIsRegisteredOutsideTheHandlerList() {
        List<? extends Class<? extends Listener>> built = new ListenerRegistry(null)
                .buildInteractionHandlers()
                .stream()
                .map(Listener::getClass)
                .toList();

        assertEquals(false, built.contains(PluginManagerGuard.class),
                "PluginManagerGuard belongs to the registration step, not the built list.");
    }

    /**
     * The plugin instance must itself be registered, because its onCraftEngineReload,
     * onWorldLoad and onWorldUnload methods are @EventHandlers.
     *
     *
     * Bukkit only dispatches an @EventHandler for an instance that was passed to
     * registerEvents. Declaring the methods is not enough, and nothing warns when the registration is
     * missing: the handlers simply never run. That is exactly what happened when this registry was extracted
     * — the plugin kept its handlers, lost its registration, and startup silently stopped warming CraftEngine
     * content until an explicit /fd reload. This asserts the registration set, not just the built
     * handler list, so the omission fails here instead of in production.
     */
    @Test
    void thePluginInstanceItselfIsRegisteredAsAListener() {
        // Built with a null plugin, so the plugin entry of the registration set is a null element. Asserting
        // that null is present is how the registration is checked without a live server: the list is
        // "handler, handler, ..., plugin", so a null tail means the plugin was included.
        List<Listener> registered = new ListenerRegistry(null).interactionHandlersToRegister();

        List<Class<?>> classes = registered.stream()
                .filter(Objects::nonNull)
                .map(Object::getClass)
                .collect(Collectors.toList());
        List<Class<?>> expected = new ArrayList<>(EXPECTED_INTERACTION_ORDER);

        assertEquals(expected, classes,
                "The registration set must be the built handler list followed by the plugin instance. The"
                        + " plugin declares @EventHandler methods (onCraftEngineReload / onWorldLoad /"
                        + " onWorldUnload), so it has to be registered or those handlers never fire and"
                        + " startup never warms CraftEngine.");

        assertEquals(1, registered.size() - classes.size(),
                "The plugin instance is the only entry that is not a built handler.");
        assertEquals(true, registered.contains(null),
                "The plugin instance must be the last entry of the registration set.");
    }

    private static final List<Class<? extends Listener>> EXPECTED_VISUAL_ORDER = List.of(
            RopeBlockListener.class,
            TatamiBreakListener.class,
            RichSoilHoeListener.class,
            CropInteractProtectionListener.class,
            BackstabListener.class,
            KnifeEnchantFilter.class,
            EnchantmentDatapackInstaller.class,
            TagDatapackInstaller.class,
            VillagerTradeListener.class,
            ChunkLoadListener.class);

    @Test
    void visualAndWorldHandlersAreBuiltInRegistrationOrder() {
        List<? extends Class<? extends Listener>> actual = new ListenerRegistry(null)
                .buildVisualAndWorldHandlers()
                .stream()
                .map(Listener::getClass)
                .toList();

        assertEquals(EXPECTED_VISUAL_ORDER, actual,
                "The world-facing handler order changed. Bukkit runs same-priority handlers in registration"
                        + " order, so this is a behaviour change: check every handler that shares an event"
                        + " before accepting it, then update this list.");
    }

    /**
     * The buff bossbar renderer is built and configured by the plugin and handed in, so it is registered
     * outside the built list. Asserted so a future edit cannot silently move it into the list and change
     * when the renderer starts receiving events relative to the other handlers.
     */
    @Test
    void theBossbarRendererIsNotPartOfTheBuiltWorldHandlerList() {
        List<? extends Class<? extends Listener>> built = new ListenerRegistry(null)
                .buildVisualAndWorldHandlers()
                .stream()
                .map(Listener::getClass)
                .toList();

        assertEquals(false, built.contains(BuffBossbarManager.class),
                "BuffBossbarManager is handed to registerVisualAndWorldHandlers, not built by it.");
    }
}
